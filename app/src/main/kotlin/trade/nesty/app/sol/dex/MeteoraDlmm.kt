package trade.nesty.app.sol.dex

import trade.nesty.app.evm.Side
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.Layouts
import trade.nesty.app.sol.QuoteAsset
import trade.nesty.app.sol.Reader
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.bytesOf
import trade.nesty.app.sol.concat
import trade.nesty.app.sol.hasDiscriminator
import trade.nesty.app.sol.dex.MeteoraMath.U64_MAX
import trade.nesty.app.sol.dex.MeteoraMath.fitU64
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** The fee's settings and the volatility state it moves with. */
data class DlmmFee(
    val baseFactor: Int,
    val powerFactor: Int,
    val filterPeriod: Int,
    val decayPeriod: Int,
    val reductionFactor: Int,
    val variableFeeControl: Long,
    val maxVolatility: Long,
    val volatility: Long,
    val volatilityReference: Long,
    val indexReference: Int,
    val lastUpdate: Long,
)

/** Limit orders resting in a bin: selling X (ask) or Y (bid). */
data class DlmmOrders(val ask: Boolean, val open: BigInteger, val processed: BigInteger)

/** A bin with something in it. */
data class DlmmBin(
    val id: Int,
    val x: BigInteger,
    val y: BigInteger,
    /** Token Y per token X, in Q64.64. */
    val price: BigInteger,
    val orders: DlmmOrders?,
)

data class DlmmArray(val index: Int, val bins: List<DlmmBin>)

data class MeteoraDlmmVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    /** Whether the coin is the pair's token X (else token Y). */
    val coinIsX: Boolean,
    val reserveX: String,
    val reserveY: String,
    val oracle: String,
    val status: Int,
    val pairType: Int,
    val activationType: Int,
    val activationPoint: BigInteger,
    /** The chain's clock when the pair was read: the variable fee decays with it. */
    val clock: Layouts.ChainClock,
    val binStep: Int,
    val activeId: Int,
    val fee: DlmmFee,
    /** Whether bins fill limit orders after market makers' liquidity. */
    val limitOrders: Boolean,
    /** Fees in token Y only: off the output when Y comes out. */
    val feeInY: Boolean,
    /** The arrays holding liquidity a swap walks, nearest first: down as X goes in, up as Y. */
    val down: List<DlmmArray>,
    val up: List<DlmmArray>,
) : DexVenue {
    override val dex get() = DexKind.METEORA_DLMM
}

/**
 * Meteora DLMM (lb_clmm): liquidity in discrete bins, each trading at its own fixed price, 70 bins
 * to an array. Facts from Meteora's SDK (MeteoraAg/dlmm-sdk, its exact-input quote): a swap
 * empties bins one at a time from the active bin, market makers' liquidity first and then the
 * bin's limit orders (processed, then open); the fee is a base fee set by the bin step plus a
 * variable fee that grows with the bins crossed since the last quiet spell, and comes off the
 * input, or off the output when a pool collects only token Y and Y comes out. Ported from
 * dex/meteora-dlmm.ts.
 */
object MeteoraDlmm : DexModule<MeteoraDlmmVenue>(DexKind.METEORA_DLMM, "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo", 200_000) {
    private val EVENT_AUTHORITY by lazy { SolAddr.pda(program, SolAddr.seed("__event_authority")) }
    const val MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"

    private val PAIR_DISC = bytesOf(33, 11, 49, 98, 181, 101, 177, 13)
    private val ARRAY_DISC = bytesOf(92, 142, 92, 220, 5, 148, 70, 181)
    private val SWAP2 = bytesOf(65, 75, 63, 76, 235, 91, 91, 136)
    private const val PAIR_SIZE = 904
    private const val BINS = 70
    private const val BIN_SIZE = 144
    private const val ARRAY_SIZE = 56 + BINS * BIN_SIZE
    private const val MIN_BIN_ID = -443636
    private const val MAX_BIN_ID = 443636

    /** The pair's own bitmap covers arrays -512..511; past them an extension account does. */
    private const val BITMAP_HALF = 512

    /** Arrays read each way from the active bin: a swap that needs more is not quoted. */
    private const val ARRAYS_EACH_WAY = 3
    private val FEE_PRECISION = BigInteger.valueOf(1_000_000_000)
    private val MAX_FEE_RATE = BigInteger.valueOf(100_000_000)
    private const val BPS = 10_000L

    /** Pair types whose swaps wait for the activation point. */
    private val PERMISSIONED = setOf(1, 2)
    private const val LIMIT_ORDER = 2
    private const val UNDETERMINED = 0

    /**
     * A trade's compute, measured on mainnet for whole transactions (accounts and wrapping
     * included): up to about 61k through one bin, and 5.5k to 5.8k more for each further bin it
     * takes liquidity from. Stepping across an empty bin went unmeasured, so it is given some.
     */
    private const val UNITS = 200_000
    private const val UNITS_FIRST_BIN = 70_000
    private const val UNITS_PER_BIN = 6_000
    private const val UNITS_PER_STEP = 400

    private val ONE = BigInteger.ONE
    private val ZERO = BigInteger.ZERO
    private val Q64 = BigInteger.ONE.shiftLeft(64)

    class PairState(
        val fee: DlmmFee,
        val functionType: Int,
        val collectFeeMode: Int,
        val pairType: Int,
        val activeId: Int,
        val binStep: Int,
        val status: Int,
        val activationType: Int,
        val mintX: String,
        val mintY: String,
        val reserveX: String,
        val reserveY: String,
        val rewardMints: List<String>,
        val oracle: String,
        val bitmap: BigInteger,
        val activationPoint: BigInteger,
        val flagX: Int,
        val flagY: Int,
    )

    private fun byteAt(data: ByteArray, i: Int) = data.getOrNull(i)?.toInt()?.and(0xff) ?: 0

    /** LbPair: zero-copy, so at fixed offsets after the discriminator. */
    fun decodeDlmmPair(data: ByteArray): PairState? {
        if (data.size != PAIR_SIZE || !hasDiscriminator(data, PAIR_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        var bitmap = ZERO
        for (i in 0 until 16) bitmap = bitmap.or(at(584 + i * 8).u64().shiftLeft(64 * i))
        return PairState(
            fee = DlmmFee(
                baseFactor = at(8).u16(),
                filterPeriod = at(10).u16(),
                decayPeriod = at(12).u16(),
                reductionFactor = at(14).u16(),
                variableFeeControl = at(16).u32(),
                maxVolatility = at(20).u32(),
                powerFactor = byteAt(data, 34),
                volatility = at(40).u32(),
                volatilityReference = at(44).u32(),
                indexReference = at(48).i32(),
                lastUpdate = at(56).i64(),
            ),
            functionType = byteAt(data, 35),
            collectFeeMode = byteAt(data, 36),
            pairType = byteAt(data, 75),
            activeId = at(76).i32(),
            binStep = at(80).u16(),
            status = byteAt(data, 82),
            activationType = byteAt(data, 86),
            mintX = at(88).pubkey(),
            mintY = at(120).pubkey(),
            reserveX = at(152).pubkey(),
            reserveY = at(184).pubkey(),
            rewardMints = listOf(at(264).pubkey(), at(408).pubkey()),
            oracle = at(552).pubkey(),
            bitmap = bitmap,
            activationPoint = at(816).u64(),
            flagX = byteAt(data, 880),
            flagY = byteAt(data, 881),
        )
    }

    /** A bin array of `pair`'s, its bins with anything in them; null when it is none of its. */
    private fun decodeBinArray(data: ByteArray, pair: String, limitOrders: Boolean): DlmmArray? {
        if (data.size != ARRAY_SIZE || !hasDiscriminator(data, ARRAY_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        if (at(24).pubkey() != pair) return null
        val index = at(8).i64().toInt()
        val bins = ArrayList<DlmmBin>()
        for (i in 0 until BINS) {
            val o = 56 + i * BIN_SIZE
            val x = at(o).u64()
            val y = at(o + 8).u64()
            // Without limit orders these bytes hold other things.
            val open = if (limitOrders) at(o + 112).u64() else ZERO
            val processed = if (limitOrders) at(o + 128).u64() else ZERO
            if (x.signum() == 0 && y.signum() == 0 && open.signum() == 0 && processed.signum() == 0) continue
            bins += DlmmBin(
                id = index * BINS + i,
                x = x,
                y = y,
                price = at(o + 16).u128(),
                orders = if (open.signum() > 0 || processed.signum() > 0) DlmmOrders(byteAt(data, o + 140) != 0, open, processed) else null,
            )
        }
        return DlmmArray(index, bins)
    }

    /** The index as 8 little-endian bytes, two's complement. */
    private fun i64le(n: Long) = ByteArray(8) { (n shr (8 * it)).toByte() }

    fun binArrayAddress(pair: String, index: Int): String =
        SolAddr.pda(program, SolAddr.seed("bin_array"), pair, i64le(index.toLong()))

    private fun arrayIndexOf(binId: Int) = Math.floorDiv(binId, BINS)

    /** The first `n` arrays holding liquidity from `start` one way, by the pair's bitmap. */
    private fun withLiquidity(bitmap: BigInteger, start: Int, down: Boolean, n: Int): List<Int> {
        val out = ArrayList<Int>()
        var i = start
        while (out.size < n && i >= -BITMAP_HALF && i < BITMAP_HALF) {
            if (bitmap.testBit(i + BITMAP_HALF)) out += i
            i += if (down) -1 else 1
        }
        return out
    }

    private fun flagProgram(flag: Int) = when (flag) {
        0 -> SolAddr.TOKEN_PROGRAM
        1 -> SolAddr.TOKEN_2022_PROGRAM
        else -> null
    }

    /** Slot or Unix time, as the pair's activation counts. */
    private fun pointOf(v: MeteoraDlmmVenue) = if (v.activationType == 0) v.clock.slot else v.clock.unixTime

    /** Whether `side` sells token X for token Y. */
    private fun forY(v: MeteoraDlmmVenue, side: Side) = (side == Side.SELL) == v.coinIsX

    /** The fee rate (of 10^9) at a volatility, capped at 10%. */
    private fun feeRate(v: MeteoraDlmmVenue, f: DlmmFee, volatility: Long): BigInteger {
        val step = BigInteger.valueOf(v.binStep.toLong())
        val base = BigInteger.valueOf(f.baseFactor.toLong()) * step * BigInteger.TEN * BigInteger.TEN.pow(f.powerFactor)
        val crossed = BigInteger.valueOf(volatility) * step
        val variable = if (f.variableFeeControl > 0) {
            (BigInteger.valueOf(f.variableFeeControl) * crossed * crossed + BigInteger.valueOf(99_999_999_999)) / BigInteger.valueOf(100_000_000_000)
        } else {
            ZERO
        }
        val total = base + variable
        return if (total < MAX_FEE_RATE) total else MAX_FEE_RATE
    }

    /** The fee within an amount that includes it, rounded up (`compute_fee_from_amount`). */
    private fun feeWithin(amount: BigInteger, rate: BigInteger) = fitU64((amount * rate + FEE_PRECISION - ONE) / FEE_PRECISION)

    /** The fee to add on top of an amount, rounded up (`compute_fee`). */
    private fun feeOnTop(amount: BigInteger, rate: BigInteger) = fitU64((amount * rate + FEE_PRECISION - rate - ONE) / (FEE_PRECISION - rate))

    private fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger = if (b.signum() == 0) refuse() else (a + b - ONE) / b

    /** What `amountIn` buys at a bin's price, rounded down. */
    private fun amountOut(amountIn: BigInteger, price: BigInteger, swapForY: Boolean): BigInteger =
        fitU64(if (swapForY) (price * amountIn).shiftRight(64) else if (price.signum() == 0) refuse() else amountIn.shiftLeft(64) / price)

    /** What buying `out` costs at a bin's price, rounded up. */
    private fun amountIn(out: BigInteger, price: BigInteger, swapForY: Boolean): BigInteger =
        fitU64(if (swapForY) ceilDiv(out.shiftLeft(64), price) else ceilDiv(out * price, Q64))

    private class Filled(val used: BigInteger, val paid: BigInteger)

    /** How much of `amount` one layer of a bin's liquidity takes, and what it pays. */
    private fun fill(amount: BigInteger, available: BigInteger, price: BigInteger, swapForY: Boolean): Filled {
        if (available.signum() == 0) return Filled(ZERO, ZERO)
        val need = amountIn(available, price, swapForY)
        return if (amount >= need) Filled(need, available) else Filled(amount, amountOut(amount, price, swapForY))
    }

    /** The limit orders of a bin that `swapForY` fills: bids as X goes in, asks as Y does. */
    private fun ordersFor(bin: DlmmBin, swapForY: Boolean): DlmmOrders? = bin.orders?.takeIf { it.ask != swapForY }

    /** A bin's layers in turn: market makers, then processed orders, then open ones. */
    private fun fillBin(v: MeteoraDlmmVenue, bin: DlmmBin, amount: BigInteger, swapForY: Boolean): Filled {
        val mm = fill(amount, if (swapForY) bin.y else bin.x, bin.price, swapForY)
        var used = mm.used
        var paid = mm.paid
        val orders = if (v.limitOrders) ordersFor(bin, swapForY) else null
        if (orders != null && amount > used) {
            for (available in listOf(orders.processed, orders.open)) {
                if (amount == used) break
                val layer = fill(amount - used, available, bin.price, swapForY)
                used += layer.used
                paid = fitU64(paid + layer.paid)
            }
        }
        return Filled(used, paid)
    }

    private class BinSwap(val amountIn: BigInteger, val amountOut: BigInteger)

    /** One bin's share of an exact-input swap (`swap_exact_in_quote_at_bin`). */
    private fun swapAtBin(v: MeteoraDlmmVenue, rate: BigInteger, bin: DlmmBin, inAmount: BigInteger, swapForY: Boolean): BinSwap {
        val feeOnInput = !(v.feeInY && swapForY)
        var net = if (feeOnInput) inAmount - feeWithin(inAmount, rate) else inAmount
        val f = fillBin(v, bin, net, swapForY)
        var gross = inAmount
        // A bin emptied before the input ran out takes only what filled it, and its fee.
        if (net > f.used) {
            net = f.used
            gross = if (feeOnInput) fitU64(net + feeOnTop(net, rate)) else net
        }
        return BinSwap(gross, if (feeOnInput) f.paid else f.paid - feeWithin(f.paid, rate))
    }

    class Walk(val out: BigInteger, val filled: Int, val steps: Int)

    /**
     * An exact-input swap of `amount`, bin by bin as the program walks them: what it pays, and the
     * bins it took liquidity from and stepped across (its compute grows with both).
     */
    fun walk(v: MeteoraDlmmVenue, amount: BigInteger, swapForY: Boolean): Walk {
        val f = v.fee
        var indexReference = f.indexReference
        var volatilityReference = f.volatilityReference
        // update_references: a quiet spell resets the reference to the active bin, and decays.
        val elapsed = v.clock.unixTime - BigInteger.valueOf(f.lastUpdate)
        if (elapsed >= BigInteger.valueOf(f.filterPeriod.toLong())) {
            indexReference = v.activeId
            if (elapsed < BigInteger.valueOf(f.decayPeriod.toLong())) {
                val decayed = f.volatility * f.reductionFactor
                if (decayed > 0xffff_ffffL) refuse()
                volatilityReference = decayed / BPS
            } else {
                volatilityReference = 0
            }
        }
        val arrays = if (swapForY) v.down else v.up
        var left = amount
        var out = ZERO
        var active = v.activeId
        var filled = 0
        var steps = 0
        var next = 0
        while (left.signum() > 0) {
            val a = arrays.getOrNull(next++) ?: refuse()
            val lower = a.index * BINS
            val upper = lower + BINS - 1
            // Past an empty stretch, the swap starts at the array's near edge.
            if (arrayIndexOf(active) != a.index) active = if (swapForY) upper else lower
            val bins = a.bins.associateBy { it.id }
            while (active in lower..upper && left.signum() > 0) {
                val bin = bins[active]
                val orders = if (bin != null && v.limitOrders) ordersFor(bin, swapForY) else null
                val available = if (bin != null) (if (swapForY) bin.y else bin.x) + (if (orders != null) orders.open + orders.processed else ZERO) else ZERO
                if (bin != null && available.signum() > 0) {
                    val volatility = minOf(volatilityReference + abs(indexReference.toLong() - active) * BPS, f.maxVolatility)
                    val step = swapAtBin(v, feeRate(v, f, volatility), bin, left, swapForY)
                    if (step.amountIn > left) refuse()
                    left -= step.amountIn
                    out = fitU64(out + step.amountOut)
                    filled++
                }
                if (left.signum() > 0) {
                    active += if (swapForY) -1 else 1
                    steps++
                    if (active < MIN_BIN_ID || active > MAX_BIN_ID) refuse()
                }
            }
        }
        return Walk(out, filled, steps)
    }

    /** Each pair's arrays read last time, to read along with it next time. */
    private val lastRead = ConcurrentHashMap<String, List<Int>>()

    override fun unitsFor(v: MeteoraDlmmVenue, side: Side, amountIn: BigInteger): Int = try {
        val w = walk(v, amountIn, forY(v, side))
        UNITS_FIRST_BIN + UNITS_PER_BIN * max(0, w.filled - 1) + UNITS_PER_STEP * w.steps
    } catch (_: Exception) {
        // A swap the pool cannot fill fails on chain whatever it is given.
        UNITS
    }

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): MeteoraDlmmVenue? {
        val before = lastRead[pool] ?: emptyList()
        val accs = ctx.read(listOf(pool, SolAddr.SYSVAR_CLOCK) + before.map { binArrayAddress(pool, it) })
        val pairAcc = accs.getOrNull(0)
        val clockAcc = accs.getOrNull(1)
        if (pairAcc?.owner != program) return null
        val s = decodeDlmmPair(pairAcc.data)
        val clock = clockAcc?.let { Layouts.decodeClock(it.data) }
        if (s == null || clock == null || s.collectFeeMode > 1 || s.functionType > LIMIT_ORDER) return null
        val coinIsX = s.mintX == mint
        if (!coinIsX && s.mintY != mint) return null
        if (flagProgram(if (coinIsX) s.flagX else s.flagY) != ctx.coinProgram) return null
        val quote = ctx.quoteAsset(if (coinIsX) s.mintY else s.mintX) ?: return null
        if (quote.program != flagProgram(if (coinIsX) s.flagY else s.flagX)) return null
        // Bins past the pair's own bitmap need its extension account: not quoted.
        val active = arrayIndexOf(s.activeId)
        if (active < -BITMAP_HALF || active >= BITMAP_HALF) return null

        val downIndexes = withLiquidity(s.bitmap, active, true, ARRAYS_EACH_WAY)
        val upIndexes = withLiquidity(s.bitmap, active, false, ARRAYS_EACH_WAY)
        val needed = (downIndexes + upIndexes).distinct()
        val got = HashMap<Int, trade.nesty.app.sol.SolAccount?>()
        before.forEachIndexed { i, index -> got[index] = accs.getOrNull(2 + i) }
        val missing = needed.filter { it !in got }
        if (missing.isNotEmpty()) {
            val more = ctx.read(missing.map { binArrayAddress(pool, it) })
            missing.forEachIndexed { i, index -> got[index] = more.getOrNull(i) }
        }
        val limitOrders = s.functionType == LIMIT_ORDER || (s.functionType == UNDETERMINED && s.rewardMints.all { it == SolAddr.DEFAULT_KEY })
        val arrays = HashMap<Int, DlmmArray>()
        for (index in needed) {
            val acc = got[index]
            val a = if (acc?.owner == program) decodeBinArray(acc.data, pool, limitOrders) else null
            if (a == null || a.index != index) return null
            arrays[index] = a
        }
        if (lastRead.size > 200) lastRead.clear()
        lastRead[pool] = needed
        fun pick(indexes: List<Int>) = indexes.mapNotNull { arrays[it] }
        return MeteoraDlmmVenue(
            pool = pool,
            mint = mint,
            tokenProgram = ctx.coinProgram,
            quote = quote,
            coinIsX = coinIsX,
            reserveX = s.reserveX,
            reserveY = s.reserveY,
            oracle = s.oracle,
            status = s.status,
            pairType = s.pairType,
            activationType = s.activationType,
            activationPoint = s.activationPoint,
            clock = clock,
            binStep = s.binStep,
            activeId = s.activeId,
            fee = s.fee,
            limitOrders = limitOrders,
            feeInY = s.collectFeeMode == 1,
            down = pick(downIndexes),
            up = pick(upIndexes),
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeDlmmPair(data)?.let { SolAddr.coinOfPair(it.mintX, it.mintY) }

    override fun label(v: MeteoraDlmmVenue) = "Meteora DLMM"

    override fun quote(v: MeteoraDlmmVenue, side: Side, amountIn: BigInteger): BigInteger = quoteOrZero {
        if (amountIn.signum() > 0 && amountIn <= U64_MAX) walk(v, amountIn, forY(v, side)).out else ZERO
    }

    override fun spot(v: MeteoraDlmmVenue): Double {
        val price = (1 + v.binStep.toDouble() / BPS).pow(v.activeId)
        return if (v.coinIsX) price else if (price > 0) 1 / price else 0.0
    }

    override fun idle(v: MeteoraDlmmVenue, nowSec: Double): String? {
        if (v.status != 0) return "Meteora has disabled swaps on this pool."
        if (v.pairType in PERMISSIONED && pointOf(v) < v.activationPoint) return "This Meteora pool has not opened yet."
        return null
    }

    override fun swap(v: MeteoraDlmmVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val (source, destination) = userAccounts(v, side, user)
        val coin = QuoteAsset(v.mint, v.tokenProgram)
        val quote = QuoteAsset(v.quote.mint, v.quote.program)
        val (x, y) = if (v.coinIsX) coin to quote else quote to coin
        val keys = mutableListOf(
            meta(v.pool, true),
            // No bitmap extension (quotes stay within the pair's own bitmap) and no host fee: the
            // program's own address stands in for each.
            meta(program),
            meta(v.reserveX, true),
            meta(v.reserveY, true),
            meta(source, true),
            meta(destination, true),
            meta(x.mint),
            meta(y.mint),
            meta(v.oracle, true),
            meta(program),
            meta(user, false, true),
            meta(x.program),
            meta(y.program),
            meta(MEMO_PROGRAM),
            meta(EVENT_AUTHORITY),
            meta(program),
        )
        // The bin arrays the swap may walk, in order.
        keys += (if (forY(v, side)) v.down else v.up).map { meta(binArrayAddress(v.pool, it.index), true) }
        // No transfer-hook accounts: an empty list of slices.
        return Instruction(program, keys, concat(SWAP2, u64(amountIn), u64(minOut), bytesOf(0, 0, 0, 0)))
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 28 || !hasDiscriminator(data, SWAP2)) return null
        val r = Reader(data, 8)
        val amounts = SwapAmounts(r.u64(), r.u64())
        return if (r.u32() == 0L) amounts else null
    }
}
