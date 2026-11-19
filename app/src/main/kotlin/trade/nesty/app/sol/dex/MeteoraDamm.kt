package trade.nesty.app.sol.dex

import trade.nesty.app.evm.Side
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.Layouts
import trade.nesty.app.sol.QuoteAsset
import trade.nesty.app.sol.Reader
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.bytesOf
import trade.nesty.app.sol.concat
import trade.nesty.app.sol.hasDiscriminator
import trade.nesty.app.sol.dex.MeteoraMath.ONE_Q64
import trade.nesty.app.sol.dex.MeteoraMath.U64_MAX
import trade.nesty.app.sol.dex.MeteoraMath.deltaBase
import trade.nesty.app.sol.dex.MeteoraMath.deltaQuote
import trade.nesty.app.sol.dex.MeteoraMath.feeOn
import trade.nesty.app.sol.dex.MeteoraMath.fitU64
import trade.nesty.app.sol.dex.MeteoraMath.nextSqrtFromInput
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.math.BigInteger

/** The base fee, by the schedule it follows. */
sealed class DammBaseFee {
    abstract val cliff: BigInteger

    data class Time(
        val exponential: Boolean,
        override val cliff: BigInteger,
        val periods: BigInteger,
        /** Slots or seconds per period, as the pool's activation counts. */
        val frequency: BigInteger,
        val reduction: BigInteger,
    ) : DammBaseFee()

    data class Limiter(
        override val cliff: BigInteger,
        val incrementBps: BigInteger,
        val duration: BigInteger,
        val maxFeeBps: BigInteger,
        val reference: BigInteger,
    ) : DammBaseFee()

    data class Mcap(
        val exponential: Boolean,
        override val cliff: BigInteger,
        val periods: BigInteger,
        val stepBps: BigInteger,
        val expiration: BigInteger,
        val reduction: BigInteger,
    ) : DammBaseFee()
}

data class DammVolatility(val accumulator: BigInteger, val binStep: BigInteger, val control: BigInteger)

data class MeteoraDammVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    /** Whether the coin is the pool's token A (else token B). */
    val coinIsA: Boolean,
    val vaultA: String,
    val vaultB: String,
    /** 0 while swaps are enabled. */
    val status: Int,
    /** Opening, in slots (activation type 0) or seconds (1). */
    val activationType: Int,
    val activationPoint: BigInteger,
    /** The chain's clock when the pool was read: the base fee may move with it. */
    val clock: Layouts.ChainClock,
    val collectFeeMode: Int,
    val maxFee: BigInteger,
    val baseFee: DammBaseFee,
    /** The volatility fee's inputs; it holds still between swaps. */
    val volatility: DammVolatility?,
    val initSqrtPrice: BigInteger,
    val sqrtPrice: BigInteger,
    val sqrtMinPrice: BigInteger,
    val sqrtMaxPrice: BigInteger,
    val liquidity: BigInteger,
    val reserveA: BigInteger,
    val reserveB: BigInteger,
) : DexVenue {
    override val dex get() = DexKind.METEORA_DAMM
}

/**
 * Meteora DAMM v2 (cp-amm), where Meteora's bonding curves graduate. Facts from its source
 * (MeteoraAg/damm-v2): a pool prices on concentrated liquidity between two square-root prices, or
 * (compounding pools) on its reserves as a constant product; the fee is a base fee (fixed,
 * decaying with time or market cap, or rate-limited by size) plus a volatility fee, rounded up, and
 * comes off the input or the output as the pool's fee mode says. Ported from dex/meteora-damm.ts.
 */
object MeteoraDamm : DexModule<MeteoraDammVenue>(DexKind.METEORA_DAMM, "cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG", 150_000) {
    private val AUTHORITY by lazy { SolAddr.pda(program, SolAddr.seed("pool_authority")) }
    private val EVENT_AUTHORITY by lazy { SolAddr.pda(program, SolAddr.seed("__event_authority")) }

    private val POOL_DISC = bytesOf(241, 154, 109, 4, 17, 177, 109, 188)
    private val SWAP = bytesOf(248, 198, 158, 145, 225, 117, 135, 200)
    private const val POOL_SIZE = 1112

    /** Fee modes: 0 in both tokens (off the output), 1 only in token B, 2 compounding. */
    private const val COMPOUNDING = 2
    private val MAX_FEE = listOf(BigInteger.valueOf(500_000_000), BigInteger.valueOf(990_000_000))
    private val ZERO = BigInteger.ZERO
    private val BPS = BigInteger.valueOf(10_000)

    class PoolState(
        val baseFee: DammBaseFee?,
        val volatility: DammVolatility?,
        val initSqrtPrice: BigInteger,
        val mintA: String,
        val mintB: String,
        val vaultA: String,
        val vaultB: String,
        val liquidity: BigInteger,
        val sqrtMinPrice: BigInteger,
        val sqrtMaxPrice: BigInteger,
        val sqrtPrice: BigInteger,
        val activationPoint: BigInteger,
        val activationType: Int,
        val status: Int,
        val flagA: Int,
        val flagB: Int,
        val collectFeeMode: Int,
        val feeVersion: Int,
        val reserveA: BigInteger,
        val reserveB: BigInteger,
    )

    private fun decodeBaseFee(data: ByteArray): DammBaseFee? {
        fun at(o: Int) = Reader(data, o)
        val cliff = at(8).u64()
        return when (val mode = at(16).u8()) {
            0, 1 -> DammBaseFee.Time(
                exponential = mode == 1,
                cliff = cliff,
                periods = BigInteger.valueOf(at(22).u16().toLong()),
                frequency = at(24).u64(),
                reduction = at(32).u64(),
            )
            2 -> DammBaseFee.Limiter(
                cliff = cliff,
                incrementBps = BigInteger.valueOf(at(22).u16().toLong()),
                duration = BigInteger.valueOf(at(24).u32()),
                maxFeeBps = BigInteger.valueOf(at(28).u32()),
                reference = at(32).u64(),
            )
            3, 4 -> DammBaseFee.Mcap(
                exponential = mode == 4,
                cliff = cliff,
                periods = BigInteger.valueOf(at(22).u16().toLong()),
                stepBps = BigInteger.valueOf(at(24).u32()),
                expiration = BigInteger.valueOf(at(28).u32()),
                reduction = at(32).u64(),
            )
            else -> null
        }
    }

    /** Pool: zero-copy, at fixed offsets after the discriminator. */
    fun decodeDammPool(data: ByteArray): PoolState? {
        if (data.size != POOL_SIZE || !hasDiscriminator(data, POOL_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        return PoolState(
            baseFee = decodeBaseFee(data),
            volatility = if (at(56).u8() != 0) {
                DammVolatility(
                    accumulator = at(120).u128(),
                    binStep = BigInteger.valueOf(at(72).u16().toLong()),
                    control = BigInteger.valueOf(at(68).u32()),
                )
            } else {
                null
            },
            initSqrtPrice = at(152).u128(),
            mintA = at(168).pubkey(),
            mintB = at(200).pubkey(),
            vaultA = at(232).pubkey(),
            vaultB = at(264).pubkey(),
            liquidity = at(360).u128(),
            sqrtMinPrice = at(424).u128(),
            sqrtMaxPrice = at(440).u128(),
            sqrtPrice = at(456).u128(),
            activationPoint = at(472).u64(),
            activationType = at(480).u8(),
            status = at(481).u8(),
            flagA = at(482).u8(),
            flagB = at(483).u8(),
            collectFeeMode = at(484).u8(),
            feeVersion = at(486).u8(),
            reserveA = at(680).u64(),
            reserveB = at(688).u64(),
        )
    }

    private fun flagProgram(flag: Int) = when (flag) {
        0 -> SolAddr.TOKEN_PROGRAM
        1 -> SolAddr.TOKEN_2022_PROGRAM
        else -> null
    }

    /** Slot or Unix time, as the pool's activation counts. */
    private fun pointOf(v: MeteoraDammVenue) = if (v.activationType == 0) v.clock.slot else v.clock.unixTime

    /** Whether `side` puts token A into the pool. */
    private fun aIn(v: MeteoraDammVenue, side: Side) = (side == Side.SELL) == v.coinIsA

    private fun baseFeeOf(v: MeteoraDammVenue, input: BigInteger, aToB: Boolean): BigInteger {
        val point = pointOf(v)
        val early = point < v.activationPoint
        return when (val f = v.baseFee) {
            is DammBaseFee.Time -> {
                if (f.frequency.signum() == 0) return f.cliff
                val passed = if (early) f.periods else (point - v.activationPoint) / f.frequency
                val period = if (passed < f.periods) passed else f.periods
                MeteoraMath.scheduledFee(f.exponential, f.cliff, f.reduction, period, true)
            }
            is DammBaseFee.Limiter -> {
                val zero = f.reference.signum() == 0 && f.duration.signum() == 0 && f.maxFeeBps.signum() == 0 && f.incrementBps.signum() == 0
                val applied = !zero && !aToB && !early && point <= v.activationPoint + f.duration
                if (!applied) f.cliff
                else MeteoraMath.rateLimitedFee(input, f.cliff, f.reference, f.incrementBps, MeteoraMath.bpsNumerator(f.maxFeeBps))
            }
            is DammBaseFee.Mcap -> {
                var period = f.periods
                val live = !early && point <= v.activationPoint + f.expiration
                if (live && v.sqrtPrice > v.initSqrtPrice) {
                    if (v.initSqrtPrice.signum() == 0 || f.stepBps.signum() == 0) refuse()
                    val passed = (v.sqrtPrice - v.initSqrtPrice) * BPS / v.initSqrtPrice / f.stepBps
                    if (passed < period) period = passed
                } else if (live) {
                    period = ZERO
                }
                MeteoraMath.scheduledFee(f.exponential, f.cliff, f.reduction, period, true)
            }
        }
    }

    /** What the pool's curve pays out for `amountIn` going in, before any output fee. */
    private fun curveOut(v: MeteoraDammVenue, amountIn: BigInteger, aToB: Boolean): BigInteger {
        if (v.collectFeeMode == COMPOUNDING) {
            val (inR, outR) = if (aToB) v.reserveA to v.reserveB else v.reserveB to v.reserveA
            return fitU64(outR * amountIn / fitU64(inR + amountIn))
        }
        val next = nextSqrtFromInput(v.sqrtPrice, v.liquidity, amountIn, aToB)
        if (if (aToB) next < v.sqrtMinPrice else next > v.sqrtMaxPrice) refuse()
        return fitU64(
            if (aToB) deltaQuote(next, v.sqrtPrice, v.liquidity, false) else deltaBase(v.sqrtPrice, next, v.liquidity, false),
        )
    }

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): MeteoraDammVenue? {
        val accs = ctx.read(listOf(pool, SolAddr.SYSVAR_CLOCK))
        val poolAcc = accs.getOrNull(0)
        val clockAcc = accs.getOrNull(1)
        if (poolAcc?.owner != program) return null
        val s = decodeDammPool(poolAcc.data)
        val clock = clockAcc?.let { Layouts.decodeClock(it.data) }
        val baseFee = s?.baseFee
        if (s == null || baseFee == null || clock == null) return null
        val coinIsA = s.mintA == mint
        if (!coinIsA && s.mintB != mint) return null
        if (flagProgram(if (coinIsA) s.flagA else s.flagB) != ctx.coinProgram) return null
        val quote = ctx.quoteAsset(if (coinIsA) s.mintB else s.mintA) ?: return null
        if (quote.program != flagProgram(if (coinIsA) s.flagB else s.flagA)) return null
        val maxFee = MAX_FEE.getOrNull(s.feeVersion) ?: return null
        if (s.collectFeeMode > COMPOUNDING) return null
        return MeteoraDammVenue(
            pool = pool,
            mint = mint,
            tokenProgram = ctx.coinProgram,
            quote = quote,
            coinIsA = coinIsA,
            vaultA = s.vaultA,
            vaultB = s.vaultB,
            status = s.status,
            activationType = s.activationType,
            activationPoint = s.activationPoint,
            clock = clock,
            collectFeeMode = s.collectFeeMode,
            maxFee = maxFee,
            baseFee = baseFee,
            volatility = s.volatility,
            initSqrtPrice = s.initSqrtPrice,
            sqrtPrice = s.sqrtPrice,
            sqrtMinPrice = s.sqrtMinPrice,
            sqrtMaxPrice = s.sqrtMaxPrice,
            liquidity = s.liquidity,
            reserveA = s.reserveA,
            reserveB = s.reserveB,
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeDammPool(data)?.let { SolAddr.coinOfPair(it.mintA, it.mintB) }

    override fun label(v: MeteoraDammVenue) = "Meteora DAMM v2"

    override fun quote(v: MeteoraDammVenue, side: Side, amountIn: BigInteger): BigInteger = quoteOrZero {
        if (amountIn.signum() <= 0 || amountIn > U64_MAX) return@quoteOrZero ZERO
        val aToB = aIn(v, side)
        val vol = v.volatility
        val total = baseFeeOf(v, amountIn, aToB) + (vol?.let { MeteoraMath.volatilityFee(it.accumulator, it.binStep, it.control) } ?: ZERO)
        val fee = if (fitU64(total) < v.maxFee) total else v.maxFee
        // Fees come off the input only when token B goes in on a pool that collects in B.
        if (v.collectFeeMode != 0 && !aToB) return@quoteOrZero curveOut(v, amountIn - feeOn(amountIn, fee), aToB)
        val out = curveOut(v, amountIn, aToB)
        out - feeOn(out, fee)
    }

    override fun spot(v: MeteoraDammVenue): Double {
        val price = if (v.collectFeeMode == COMPOUNDING) {
            if (v.reserveA.signum() > 0) v.reserveB.toDouble() / v.reserveA.toDouble() else 0.0
        } else {
            val r = v.sqrtPrice.toDouble() / ONE_Q64.toDouble()
            r * r
        }
        return if (v.coinIsA) price else if (price > 0) 1 / price else 0.0
    }

    override fun idle(v: MeteoraDammVenue, nowSec: Double): String? {
        if (v.status != 0) return tr("Meteora has disabled swaps on this pool.")
        if (pointOf(v) < v.activationPoint) return tr("This Meteora pool has not opened yet.")
        return null
    }

    override fun swap(v: MeteoraDammVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val (source, destination) = userAccounts(v, side, user)
        val coin = QuoteAsset(v.mint, v.tokenProgram)
        val quote = QuoteAsset(v.quote.mint, v.quote.program)
        val (a, b) = if (v.coinIsA) coin to quote else quote to coin
        val keys = mutableListOf(
            meta(AUTHORITY),
            meta(v.pool, true),
            meta(source, true),
            meta(destination, true),
            meta(v.vaultA, true),
            meta(v.vaultB, true),
            meta(a.mint),
            meta(b.mint),
            meta(user, false, true),
            meta(a.program),
            meta(b.program),
            // No referral account: the program's own address stands in for none.
            meta(program),
            meta(EVENT_AUTHORITY),
            meta(program),
        )
        // A rate-limited pool checks the transaction holds no other swap on it.
        if (v.baseFee is DammBaseFee.Limiter) keys += meta(SolAddr.SYSVAR_INSTRUCTIONS)
        return Instruction(program, keys, concat(SWAP, u64(amountIn), u64(minOut)))
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 24 || !hasDiscriminator(data, SWAP)) return null
        val r = Reader(data, 8)
        return SwapAmounts(r.u64(), r.u64())
    }
}
