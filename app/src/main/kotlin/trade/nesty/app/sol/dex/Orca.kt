package trade.nesty.app.sol.dex

import trade.nesty.app.evm.Side
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.Layouts
import trade.nesty.app.sol.QuoteAsset
import trade.nesty.app.sol.Reader
import trade.nesty.app.sol.SolAccount
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.bytesOf
import trade.nesty.app.sol.concat
import trade.nesty.app.sol.hasDiscriminator
import trade.nesty.app.sol.dex.WhirlpoolMath.MAX_SQRT_PRICE
import trade.nesty.app.sol.dex.WhirlpoolMath.MAX_TICK
import trade.nesty.app.sol.dex.WhirlpoolMath.MIN_SQRT_PRICE
import trade.nesty.app.sol.dex.WhirlpoolMath.MIN_TICK
import trade.nesty.app.sol.dex.WhirlpoolMath.U64_MAX
import trade.nesty.app.sol.dex.WhirlpoolMath.computeSwapIn
import trade.nesty.app.sol.dex.WhirlpoolMath.sqrtPriceFromTick
import trade.nesty.app.sol.dex.WhirlpoolMath.tickFromSqrtPrice
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Adaptive fee settings and state, from the pool's oracle account. */
data class AdaptiveFee(
    val filterPeriod: BigInteger,
    val decayPeriod: BigInteger,
    val reductionFactor: BigInteger,
    val controlFactor: BigInteger,
    val maxVolatility: Long,
    val tickGroupSize: Int,
    val lastReferenceUpdate: BigInteger,
    val lastMajorSwap: BigInteger,
    val volatilityReference: Long,
    val tickGroupReference: Int,
    val volatility: Long,
)

/** An initialized tick: where a swap stops, and the liquidity it adds. */
data class OrcaTick(val offset: Int, val net: BigInteger)

/** A tick array's initialized ticks. */
data class TickArray(val start: Int, val ticks: List<OrcaTick>)

data class OrcaVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    /** Whether the coin is the pool's token A (else token B). */
    val coinIsA: Boolean,
    val vaultA: String,
    val vaultB: String,
    val tickSpacing: Int,
    /** Millionths. */
    val feeRate: Int,
    val liquidity: BigInteger,
    val sqrtPrice: BigInteger,
    val tick: Int,
    /** The chain's clock when the pool was read: the adaptive fee decays with it. */
    val clock: Layouts.ChainClock,
    val adaptive: AdaptiveFee?,
    /** When an adaptive pool opens to trading (Unix seconds). */
    val tradeFrom: BigInteger,
    /** The arrays around the current tick, both ways, as far as a swap reads them. */
    val arrays: List<TickArray>,
) : DexVenue {
    override val dex get() = DexKind.ORCA
}

/**
 * Orca's Whirlpools: concentrated liquidity over ticks, kept in arrays of 88 that a swap walks
 * three at a time. Facts from its source (orca-so/whirlpools): an exact-input swap steps from
 * initialized tick to initialized tick, taking the fee from each step's input; pools with an
 * adaptive fee add a volatility fee that grows as the swap crosses tick groups and decays with
 * time since the last large swap. Ported from dex/orca.ts.
 */
object Orca : DexModule<OrcaVenue>(DexKind.ORCA, "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc", 200_000) {
    private val POOL_DISC = bytesOf(63, 149, 209, 12, 225, 128, 99, 9)
    private val FIXED_ARRAY_DISC = bytesOf(69, 97, 189, 190, 110, 7, 66, 187)
    private val DYNAMIC_ARRAY_DISC = bytesOf(17, 216, 246, 142, 225, 199, 218, 56)
    private val ORACLE_DISC = bytesOf(139, 194, 131, 179, 140, 179, 229, 244)
    private val SWAP_V2 = bytesOf(43, 4, 237, 11, 26, 201, 30, 98)
    private const val POOL_SIZE = 653
    private const val ORACLE_SIZE = 254
    private const val TICKS = 88
    private const val TICK_SIZE = 113

    /**
     * A trade's compute, measured on mainnet for whole transactions (accounts and wrapping
     * included): up to about 68k in one step, about 7k more for each tick it crosses, and 8k to 9k
     * for each further step an adaptive fee splits it into.
     */
    private const val UNITS = 200_000
    private const val UNITS_FIRST_STEP = 75_000
    private const val UNITS_PER_STEP = 9_000
    private const val UNITS_PER_CROSS = 3_000

    /** The config anyone opens pools under, and the tick spacings of its fixed fee tiers. */
    const val WHIRLPOOLS_CONFIG = "2LecshUwdy9xi7meFgHtFJQNSKk4KdTrcpvaB56dP2NQ"
    private val FEE_TIER_SPACINGS = listOf(1, 2, 4, 8, 16, 64, 96, 128, 256)

    private const val VOLATILITY_SCALE = 10_000L
    private val FEE_RATE_HARD_LIMIT = BigInteger.valueOf(100_000)
    private val MAX_REFERENCE_AGE = BigInteger.valueOf(3_600)
    private val ZERO = BigInteger.ZERO

    class PoolState(
        val tickSpacing: Int,
        val feeRate: Int,
        val liquidity: BigInteger,
        val sqrtPrice: BigInteger,
        val tick: Int,
        val mintA: String,
        val vaultA: String,
        val mintB: String,
        val vaultB: String,
    )

    /** Whirlpool: Borsh, fixed-size, so at fixed offsets after the discriminator. */
    fun decodeWhirlpool(data: ByteArray): PoolState? {
        if (data.size != POOL_SIZE || !hasDiscriminator(data, POOL_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        return PoolState(
            tickSpacing = at(41).u16(),
            feeRate = at(45).u16(),
            liquidity = at(49).u128(),
            sqrtPrice = at(65).u128(),
            tick = at(81).i32(),
            mintA = at(101).pubkey(),
            vaultA = at(133).pubkey(),
            mintB = at(181).pubkey(),
            vaultB = at(213).pubkey(),
        )
    }

    private class OracleState(val tradeFrom: BigInteger, val fee: AdaptiveFee)

    /**
     * Oracle (packed): the pool, when trading opens, the adaptive fee's constants (34 bytes from 48)
     * and its variables (44 bytes from 82), then reserved space.
     */
    private fun decodeOracle(data: ByteArray): OracleState? {
        if (data.size < ORACLE_SIZE || !hasDiscriminator(data, ORACLE_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        return OracleState(
            tradeFrom = at(40).u64(),
            fee = AdaptiveFee(
                filterPeriod = BigInteger.valueOf(at(48).u16().toLong()),
                decayPeriod = BigInteger.valueOf(at(50).u16().toLong()),
                reductionFactor = BigInteger.valueOf(at(52).u16().toLong()),
                controlFactor = BigInteger.valueOf(at(54).u32()),
                maxVolatility = at(58).u32(),
                tickGroupSize = at(62).u16(),
                lastReferenceUpdate = at(82).u64(),
                lastMajorSwap = at(90).u64(),
                volatilityReference = at(98).u32(),
                tickGroupReference = at(102).i32(),
                volatility = at(106).u32(),
            ),
        )
    }

    /** A tick array account, or null when it is none of this pool's. */
    private fun decodeTickArray(data: ByteArray, pool: String): TickArray? {
        fun at(o: Int) = Reader(data, o)
        val start = at(8).i32()
        val ticks = ArrayList<OrcaTick>()
        if (hasDiscriminator(data, FIXED_ARRAY_DISC)) {
            if (data.size != 8 + 36 + TICK_SIZE * TICKS || at(12 + TICK_SIZE * TICKS).pubkey() != pool) return null
            for (i in 0 until TICKS) {
                val o = 12 + i * TICK_SIZE
                if (data[o].toInt() != 0) ticks += OrcaTick(i, at(o + 1).i128())
            }
            return TickArray(start, ticks)
        }
        if (hasDiscriminator(data, DYNAMIC_ARRAY_DISC)) {
            if (at(12).pubkey() != pool) return null
            val bitmap = at(44).u128()
            // Each tick is one byte when uninitialized, the byte and its data when initialized.
            var o = 60
            for (i in 0 until TICKS) {
                if (!bitmap.testBit(i)) {
                    o += 1
                    continue
                }
                ticks += OrcaTick(i, if (data.getOrNull(o)?.toInt() == 1) at(o + 1).i128() else ZERO)
                o += TICK_SIZE
            }
            return TickArray(start, ticks)
        }
        return null
    }

    fun tickArrayAddress(pool: String, start: Int): String =
        SolAddr.pda(program, SolAddr.seed("tick_array"), pool, SolAddr.seed(start.toString()))

    fun oracleAddress(pool: String): String = SolAddr.pda(program, SolAddr.seed("oracle"), pool)

    /**
     * Where that config's fixed-fee pools of `x` and `y` are (most do not exist), from their
     * addresses alone: the pool's seeds name the two mints in byte order, then the spacing.
     */
    fun whirlpoolAddresses(x: String, y: String): List<String> {
        val bx = SolAddr.addressBytes(x)
        val by = SolAddr.addressBytes(y)
        val i = bx.indices.firstOrNull { bx[it] != by[it] }
        val (a, b) = if (i != null && (bx[i].toInt() and 0xff) > (by[i].toInt() and 0xff)) y to x else x to y
        return FEE_TIER_SPACINGS.map { s ->
            SolAddr.pda(program, SolAddr.seed("whirlpool"), WHIRLPOOLS_CONFIG, a, b, bytesOf(s and 0xff, s shr 8))
        }
    }

    private fun validStart(start: Int, spacing: Int): Boolean {
        val span = TICKS * spacing
        if (start < MIN_TICK || start > MAX_TICK) {
            if (start > MIN_TICK) return false
            return start == MIN_TICK - ((MIN_TICK % span) + span)
        }
        return start % span == 0
    }

    /** The arrays a swap from `tick` walks, in order (the program's `get_start_tick_indexes`). */
    private fun startIndexes(tick: Int, spacing: Int, aToB: Boolean): List<Int> {
        val span = TICKS * spacing
        val base = Math.floorDiv(tick, span) * span
        val offsets = if (aToB) listOf(0, -1, -2) else if (tick + spacing >= base + span) listOf(1, 2, 3) else listOf(0, 1, 2)
        return offsets.map { base + it * span }.filter { validStart(it, spacing) }
    }

    /** Every array either direction may walk from `tick`. */
    private fun aroundIndexes(tick: Int, spacing: Int) = (startIndexes(tick, spacing, true) + startIndexes(tick, spacing, false)).distinct()

    /** Whether `side` puts token A into the pool. */
    private fun aIn(v: OrcaVenue, side: Side) = (side == Side.SELL) == v.coinIsA

    private fun bigMin(a: BigInteger, b: BigInteger) = if (a < b) a else b
    private fun bigMax(a: BigInteger, b: BigInteger) = if (a > b) a else b

    /** The program's `FeeRateManager`: a fixed rate, or the adaptive fee as a swap moves. */
    private class FeeRates(private val aToB: Boolean, tick: Int, now: BigInteger, private val base: Int, adaptive: AdaptiveFee?) {
        private var group = 0L
        private var lower: Pair<Long, BigInteger>? = null
        private var upper: Pair<Long, BigInteger>? = null
        private val on = adaptive != null
        private val size: Int = adaptive?.tickGroupSize ?: 1
        private val controlFactor = adaptive?.controlFactor ?: ZERO
        private val maxVolatility = adaptive?.maxVolatility ?: 0
        private var volatility = adaptive?.volatility ?: 0
        private var volatilityReference = adaptive?.volatilityReference ?: 0
        private var tickGroupReference: Long = adaptive?.tickGroupReference?.toLong() ?: 0

        init {
            if (adaptive != null) {
                val f = adaptive
                if (size == 0) refuse()
                group = Math.floorDiv(tick, size).toLong()
                // update_reference
                val latest = if (f.lastReferenceUpdate > f.lastMajorSwap) f.lastReferenceUpdate else f.lastMajorSwap
                if (now < latest) refuse()
                if (now - f.lastReferenceUpdate > MAX_REFERENCE_AGE) {
                    tickGroupReference = group
                    volatilityReference = 0
                } else if (now - latest >= f.filterPeriod) {
                    tickGroupReference = group
                    volatilityReference = if (now - latest < f.decayPeriod) {
                        (BigInteger.valueOf(f.volatility) * f.reductionFactor / BigInteger.valueOf(10_000)).toLong()
                    } else {
                        0
                    }
                }
                if (volatilityReference > maxVolatility) refuse()
                val delta = (maxVolatility - volatilityReference + VOLATILITY_SCALE - 1) / VOLATILITY_SCALE
                val lowerIndex = tickGroupReference - delta
                val upperIndex = tickGroupReference + delta
                val lowerTick = lowerIndex * size
                val upperTick = upperIndex * size + size
                if (lowerTick > MIN_TICK) lower = lowerIndex to sqrtPriceFromTick(lowerTick.toInt())
                if (upperTick < MAX_TICK) upper = upperIndex to sqrtPriceFromTick(upperTick.toInt())
            }
        }

        fun updateVolatility() {
            if (!on) return
            val v = volatilityReference + abs(tickGroupReference - group) * VOLATILITY_SCALE
            volatility = min(v, maxVolatility)
        }

        fun total(): BigInteger {
            if (!on) return BigInteger.valueOf(base.toLong())
            val crossed = BigInteger.valueOf(volatility) * BigInteger.valueOf(size.toLong())
            val p = controlFactor * crossed * crossed
            val d = BigInteger.valueOf(100_000L * 10_000L * 10_000L)
            val (div, rem) = p.divideAndRemainder(d)
            var adaptive = if (rem.signum() != 0) div + BigInteger.ONE else div
            if (adaptive > FEE_RATE_HARD_LIMIT) adaptive = FEE_RATE_HARD_LIMIT
            val total = BigInteger.valueOf(base.toLong()) + adaptive
            return if (total > FEE_RATE_HARD_LIMIT) FEE_RATE_HARD_LIMIT else total
        }

        /** The step's target, bounded to the current tick group, and whether stepping was skipped. */
        fun bounded(target: BigInteger, liquidity: BigInteger): Pair<BigInteger, Boolean> {
            if (!on) return target to false
            if (controlFactor.signum() == 0 || liquidity.signum() == 0) return target to true
            val lo = lower
            val hi = upper
            if (lo != null && group < lo.first) return (if (aToB) target else bigMin(target, lo.second)) to true
            if (hi != null && group > hi.first) return (if (aToB) bigMax(target, hi.second) else target) to true
            val edge = if (aToB) group * size else group * size + size
            val edgePrice = sqrtPriceFromTick(min(max(edge, MIN_TICK.toLong()), MAX_TICK.toLong()).toInt())
            return (if (aToB) bigMax(target, edgePrice) else bigMin(target, edgePrice)) to false
        }

        fun advance(skipped: Boolean, price: BigInteger, nextTickPrice: BigInteger, nextTick: Int) {
            if (!on) return
            if (skipped) {
                val tick: Int
                val onEdge: Boolean
                if (price == nextTickPrice) {
                    tick = nextTick
                    onEdge = nextTick % size == 0
                } else {
                    tick = tickFromSqrtPrice(price)
                    onEdge = tick % size == 0 && price == sqrtPriceFromTick(tick)
                }
                val last = (if (onEdge && !aToB) tick / size - 1 else Math.floorDiv(tick, size)).toLong()
                if ((aToB && last < group) || (!aToB && last > group)) {
                    group = last
                    updateVolatility()
                }
            }
            group += if (aToB) -1 else 1
        }
    }

    /** The arrays a swap walks, as the program builds its sequence from the accounts given. */
    private fun sequence(v: OrcaVenue, aToB: Boolean): List<TickArray> {
        val out = ArrayList<TickArray>()
        for (start in startIndexes(v.tick, v.tickSpacing, aToB)) {
            val a = v.arrays.firstOrNull { it.start == start } ?: break
            out += a
        }
        return if (out.isNotEmpty()) out else refuse()
    }

    /** The next initialized tick a swap stops at (`get_next_initialized_tick_index`). */
    private fun nextTickOf(seq: List<TickArray>, tick: Int, spacing: Int, aToB: Boolean, from: Int): Pair<Int, Int> {
        val span = TICKS * spacing
        var search = tick
        var i = from
        while (true) {
            val a = seq.getOrNull(i) ?: refuse()
            // The search range of an array, shifted one tick spacing left when moving right.
            val lower = a.start - (if (aToB) 0 else spacing)
            if (search < lower || search >= lower + span) refuse()
            var offset = Math.floorDiv(search - a.start, spacing) + (if (aToB) 0 else 1)
            val stops = a.ticks.map { it.offset }.toSet()
            while (offset in 0 until TICKS) {
                if (offset in stops) return i to a.start + offset * spacing
                offset += if (aToB) -1 else 1
            }
            if (aToB && a.start <= MIN_TICK) return i to MIN_TICK
            if (!aToB && a.start + span > MAX_TICK) return i to MAX_TICK
            if (i + 1 == seq.size) return i to (if (aToB) a.start else a.start + span - 1)
            search = if (aToB) a.start - 1 else a.start + span - 1
            i++
        }
    }

    /** The liquidity a crossed tick adds or removes, or 0 when it holds none (`get_tick`). */
    private fun netAt(a: TickArray, tick: Int, spacing: Int): BigInteger {
        val span = TICKS * spacing
        if (tick < a.start || tick >= a.start + span) return ZERO
        if (tick < MIN_TICK || tick > MAX_TICK || tick % spacing != 0) return ZERO
        val offset = (tick - a.start) / spacing
        return a.ticks.firstOrNull { it.offset == offset }?.net ?: ZERO
    }

    class Walk(val out: BigInteger, val crossed: Int, val steps: Int)

    /**
     * An exact-input swap of `amount`, walking the ticks as the program does: what it pays, and the
     * ticks it stopped at and the steps it took (its compute grows with both).
     */
    fun walk(v: OrcaVenue, amount: BigInteger, aToB: Boolean): Walk {
        val limit = if (aToB) MIN_SQRT_PRICE else MAX_SQRT_PRICE
        if (if (aToB) limit >= v.sqrtPrice else limit <= v.sqrtPrice) refuse()
        val spacing = v.tickSpacing
        val seq = sequence(v, aToB)
        val fees = FeeRates(aToB, v.tick, v.clock.unixTime, v.feeRate, v.adaptive)
        var remaining = amount
        var out = ZERO
        var price = v.sqrtPrice
        var tick = v.tick
        var liquidity = v.liquidity
        var arrayIndex = 0
        var crossed = 0
        var steps = 0
        while (remaining.signum() > 0 && price != limit) {
            val (nextArray, nextTick) = nextTickOf(seq, tick, spacing, aToB, arrayIndex)
            val nextTickPrice = sqrtPriceFromTick(nextTick)
            val target = if (aToB) {
                if (nextTickPrice > limit) nextTickPrice else limit
            } else {
                if (nextTickPrice < limit) nextTickPrice else limit
            }
            while (true) {
                fees.updateVolatility()
                val rate = fees.total()
                val (bounded, skipped) = fees.bounded(target, liquidity)
                val step = computeSwapIn(remaining, rate, liquidity, price, bounded, aToB)
                remaining -= step.amountIn + step.fee
                if (remaining.signum() < 0) refuse()
                out += step.amountOut
                if (out > U64_MAX) refuse()
                steps++
                if (step.nextPrice == nextTickPrice) {
                    val a = seq.getOrNull(nextArray) ?: refuse()
                    val net = netAt(a, nextTick, spacing)
                    crossed++
                    liquidity += if (aToB) net.negate() else net
                    if (liquidity.signum() < 0 || liquidity.shiftRight(128).signum() != 0) refuse()
                    val offset = Math.floorDiv(nextTick - a.start, spacing)
                    arrayIndex = if ((aToB && offset == 0) || (!aToB && offset == TICKS - 1)) nextArray + 1 else nextArray
                    tick = if (aToB) nextTick - 1 else nextTick
                } else if (step.nextPrice != price) {
                    tick = tickFromSqrtPrice(step.nextPrice)
                }
                price = step.nextPrice
                fees.advance(skipped, price, nextTickPrice, nextTick)
                if (remaining.signum() == 0 || price == target) break
            }
        }
        // A partial fill (the pool emptied to its price limit) is not quoted.
        return if (remaining.signum() == 0) Walk(out, crossed, steps) else refuse()
    }

    /** Each pool's tick spacing and tick read last time, to read its arrays along with it. */
    private val lastRead = ConcurrentHashMap<String, Pair<Int, Int>>()

    override fun unitsFor(v: OrcaVenue, side: Side, amountIn: BigInteger): Int = try {
        val w = walk(v, amountIn, aIn(v, side))
        UNITS_FIRST_STEP + UNITS_PER_STEP * max(0, w.steps - 1) + UNITS_PER_CROSS * w.crossed
    } catch (_: Exception) {
        // A swap the pool cannot fill fails on chain whatever it is given.
        UNITS
    }

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): OrcaVenue? {
        val before = lastRead[pool]
        val guess = if (before != null) aroundIndexes(before.second, before.first) else emptyList()
        val oracle = oracleAddress(pool)
        val accs = ctx.read(listOf(pool, SolAddr.SYSVAR_CLOCK, oracle) + guess.map { tickArrayAddress(pool, it) })
        val poolAcc = accs.getOrNull(0)
        val clockAcc = accs.getOrNull(1)
        val oracleAcc = accs.getOrNull(2)
        if (poolAcc?.owner != program) return null
        val s = decodeWhirlpool(poolAcc.data)
        val clock = clockAcc?.let { Layouts.decodeClock(it.data) }
        if (s == null || clock == null || s.tickSpacing == 0) return null
        val coinIsA = s.mintA == mint
        if (!coinIsA && s.mintB != mint) return null
        val quote = ctx.quoteAsset(if (coinIsA) s.mintB else s.mintA) ?: return null

        var oracleState: OracleState? = null
        if (oracleAcc != null && oracleAcc.data.isNotEmpty()) {
            if (oracleAcc.owner != program) return null
            oracleState = decodeOracle(oracleAcc.data) ?: return null
        }

        val needed = aroundIndexes(s.tick, s.tickSpacing)
        val got = HashMap<Int, SolAccount?>()
        guess.forEachIndexed { i, start -> got[start] = accs.getOrNull(3 + i) }
        val missing = needed.filter { it !in got }
        if (missing.isNotEmpty()) {
            val more = ctx.read(missing.map { tickArrayAddress(pool, it) })
            missing.forEachIndexed { i, start -> got[start] = more.getOrNull(i) }
        }
        val arrays = ArrayList<TickArray>()
        for (start in needed) {
            val acc = got[start]
            // An array never created holds no ticks: the program reads it as zeroed.
            if (acc == null || acc.data.isEmpty()) {
                arrays += TickArray(start, emptyList())
                continue
            }
            val a = if (acc.owner == program) decodeTickArray(acc.data, pool) else null
            if (a == null || a.start != start) return null
            arrays += a
        }
        if (lastRead.size > 200) lastRead.clear()
        lastRead[pool] = s.tickSpacing to s.tick
        return OrcaVenue(
            pool = pool,
            mint = mint,
            tokenProgram = ctx.coinProgram,
            quote = quote,
            coinIsA = coinIsA,
            vaultA = s.vaultA,
            vaultB = s.vaultB,
            tickSpacing = s.tickSpacing,
            feeRate = s.feeRate,
            liquidity = s.liquidity,
            sqrtPrice = s.sqrtPrice,
            tick = s.tick,
            clock = clock,
            adaptive = oracleState?.fee,
            tradeFrom = oracleState?.tradeFrom ?: ZERO,
            arrays = arrays,
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeWhirlpool(data)?.let { SolAddr.coinOfPair(it.mintA, it.mintB) }

    override fun label(v: OrcaVenue) = "Orca Whirlpool"

    override fun quote(v: OrcaVenue, side: Side, amountIn: BigInteger): BigInteger = quoteOrZero {
        if (amountIn.signum() > 0 && amountIn <= U64_MAX) walk(v, amountIn, aIn(v, side)).out else ZERO
    }

    override fun spot(v: OrcaVenue): Double {
        val r = v.sqrtPrice.toDouble() / Math.pow(2.0, 64.0)
        val price = r * r
        return if (v.coinIsA) price else if (price > 0) 1 / price else 0.0
    }

    override fun idle(v: OrcaVenue, nowSec: Double): String? =
        if (v.clock.unixTime < v.tradeFrom) tr("This Orca pool has not opened to trading yet.") else null

    override fun swap(v: OrcaVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val aToB = aIn(v, side)
        val coin = QuoteAsset(v.mint, v.tokenProgram)
        val quote = QuoteAsset(v.quote.mint, v.quote.program)
        val (a, b) = if (v.coinIsA) coin to quote else quote to coin
        val starts = startIndexes(v.tick, v.tickSpacing, aToB)
        val arrays = (0..2).map { i -> tickArrayAddress(v.pool, starts.getOrNull(min(i, starts.size - 1)) ?: 0) }
        val keys = mutableListOf(
            meta(a.program),
            meta(b.program),
            meta(MeteoraDlmm.MEMO_PROGRAM),
            meta(user, false, true),
            meta(v.pool, true),
            meta(a.mint),
            meta(b.mint),
            meta(SolAddr.associatedTokenAddress(user, a.mint, a.program), true),
            meta(v.vaultA, true),
            meta(SolAddr.associatedTokenAddress(user, b.mint, b.program), true),
            meta(v.vaultB, true),
        )
        keys += arrays.map { meta(it, true) }
        keys += meta(oracleAddress(v.pool), true)
        // Exact input, no price limit, no remaining accounts.
        return Instruction(program, keys, concat(SWAP_V2, u64(amountIn), u64(minOut), ByteArray(16), bytesOf(1, if (aToB) 1 else 0, 0)))
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 43 || !hasDiscriminator(data, SWAP_V2)) return null
        val r = Reader(data, 8)
        return SwapAmounts(r.u64(), r.u64())
    }
}
