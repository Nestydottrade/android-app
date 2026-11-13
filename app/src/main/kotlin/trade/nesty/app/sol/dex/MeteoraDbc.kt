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
import java.util.concurrent.ConcurrentHashMap

/** The base fee: decaying with time from the pool's activation, or rate-limited by size. */
sealed class DbcBaseFee {
    abstract val cliff: BigInteger

    data class Scheduler(
        val exponential: Boolean,
        override val cliff: BigInteger,
        val periods: BigInteger,
        /** Slots or seconds per period, as the config's activation counts. */
        val frequency: BigInteger,
        val reduction: BigInteger,
    ) : DbcBaseFee()

    data class Limiter(override val cliff: BigInteger, val incrementBps: BigInteger, val duration: BigInteger, val reference: BigInteger) : DbcBaseFee()
}

/** A curve point: liquidity up to `sqrtPrice` from the point before it. */
data class CurvePoint(val sqrtPrice: BigInteger, val liquidity: BigInteger)

data class DbcVolatility(val accumulator: BigInteger, val binStep: BigInteger, val control: BigInteger)

data class MeteoraDbcVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    val config: String,
    val baseVault: String,
    val quoteVault: String,
    /** Slots (activation type 0) or seconds (1). */
    val activationType: Int,
    val activationPoint: BigInteger,
    /** The chain's clock when the pool was read: the base fee may move with it. */
    val clock: Layouts.ChainClock,
    /** 0: in the quote token (off a buy's input), 1: off the output. */
    val collectFeeMode: Int,
    val baseFee: DbcBaseFee,
    /** The volatility fee's inputs; it holds still between swaps. */
    val volatility: DbcVolatility?,
    val sqrtPrice: BigInteger,
    val quoteReserve: BigInteger,
    /** Where the curve completes: the quote it raises, and the price it stops at. */
    val migrationThreshold: BigInteger,
    val migrationSqrtPrice: BigInteger,
    val sqrtStartPrice: BigInteger,
    val curve: List<CurvePoint>,
) : DexVenue {
    override val dex get() = DexKind.METEORA_DBC
}

/**
 * Meteora's Dynamic Bonding Curve, the launch curve behind Jupiter Studio, Bags, Believe and other
 * launchpads. Facts from its source (MeteoraAg/dynamic-bonding-curve): a pool prices on up to 20
 * segments of concentrated liquidity its config lays out, from a start price up to the migration
 * price; a trade must fill whole within them, and none trades once the quote raised reaches the
 * migration threshold. The fee (a decaying or rate-limited base fee plus a volatility fee, at most
 * 99%) comes off a buy's input when the config collects it in the quote token, else off the
 * output. Ported from dex/meteora-dbc.ts.
 */
object MeteoraDbc : DexModule<MeteoraDbcVenue>(DexKind.METEORA_DBC, "dbcij3LWUppWqq96dh6gJWwBifmcGfLSB5D4DuSMaqN", 150_000) {
    /** Owns every pool's vaults. */
    val POOL_AUTHORITY: String by lazy { SolAddr.pda(program, SolAddr.seed("pool_authority")) }
    private val EVENT_AUTHORITY by lazy { SolAddr.pda(program, SolAddr.seed("__event_authority")) }

    private val POOL_DISC = bytesOf(213, 224, 5, 209, 98, 69, 119, 92)
    private val CONFIG_DISC = bytesOf(26, 108, 14, 123, 116, 230, 129, 43)
    val SWAP = bytesOf(248, 198, 158, 145, 225, 117, 135, 200)
    private const val POOL_SIZE = 424
    private const val CONFIG_SIZE = 1048
    private const val CURVE_AT = 408
    private const val CURVE_POINTS = 20
    private val MAX_FEE = BigInteger.valueOf(990_000_000)
    private val ZERO = BigInteger.ZERO

    class PoolState(
        val accumulator: BigInteger,
        val config: String,
        val creator: String,
        val baseMint: String,
        val baseVault: String,
        val quoteVault: String,
        val baseReserve: BigInteger,
        val quoteReserve: BigInteger,
        val sqrtPrice: BigInteger,
        val activationPoint: BigInteger,
        /** The coin's token program: 0 SPL Token, 1 Token-2022. */
        val poolType: Int,
        val isMigrated: Boolean,
        val migrationProgress: Int,
        val finishCurveTime: BigInteger,
    )

    class ConfigState(
        val quoteMint: String,
        val baseFee: DbcBaseFee?,
        val volatility: Pair<BigInteger, BigInteger>?,
        val collectFeeMode: Int,
        val migrationOption: Int,
        val activationType: Int,
        val tokenDecimal: Int,
        val quoteTokenFlag: Int,
        val migrationThreshold: BigInteger,
        val migrationBaseThreshold: BigInteger,
        val migrationSqrtPrice: BigInteger,
        val sqrtStartPrice: BigInteger,
        val curve: List<CurvePoint>,
    )

    /** VirtualPool: zero-copy, at fixed offsets after the discriminator. */
    fun decodeDbcPool(data: ByteArray): PoolState? {
        if (data.size != POOL_SIZE || !hasDiscriminator(data, POOL_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        return PoolState(
            accumulator = at(40).u128(),
            config = at(72).pubkey(),
            creator = at(104).pubkey(),
            baseMint = at(136).pubkey(),
            baseVault = at(168).pubkey(),
            quoteVault = at(200).pubkey(),
            baseReserve = at(232).u64(),
            quoteReserve = at(240).u64(),
            sqrtPrice = at(280).u128(),
            activationPoint = at(296).u64(),
            poolType = at(304).u8(),
            isMigrated = at(305).u8() != 0,
            migrationProgress = at(308).u8(),
            finishCurveTime = at(344).u64(),
        )
    }

    private fun decodeBaseFee(data: ByteArray): DbcBaseFee? {
        fun at(o: Int) = Reader(data, o)
        val cliff = at(104).u64()
        // Stored in reverse so older configs read alike: the first factor last.
        val second = at(112).u64()
        val third = at(120).u64()
        val first = BigInteger.valueOf(at(128).u16().toLong())
        return when (at(130).u8()) {
            0, 1 -> DbcBaseFee.Scheduler(exponential = at(130).u8() == 1, cliff = cliff, periods = first, frequency = second, reduction = third)
            2 -> DbcBaseFee.Limiter(cliff = cliff, incrementBps = first, duration = second, reference = third)
            else -> null
        }
    }

    /** PoolConfig: the fees, the curve and where it completes. */
    fun decodeDbcConfig(data: ByteArray): ConfigState? {
        if (data.size != CONFIG_SIZE || !hasDiscriminator(data, CONFIG_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        val curve = (0 until CURVE_POINTS).map { i ->
            val r = at(CURVE_AT + i * 32)
            CurvePoint(r.u128(), r.u128())
        }
        return ConfigState(
            quoteMint = at(8).pubkey(),
            baseFee = decodeBaseFee(data),
            volatility = if (at(136).u8() != 0) BigInteger.valueOf(at(152).u16().toLong()) to BigInteger.valueOf(at(148).u32()) else null,
            collectFeeMode = at(232).u8(),
            migrationOption = at(233).u8(),
            activationType = at(234).u8(),
            tokenDecimal = at(235).u8(),
            quoteTokenFlag = at(238).u8(),
            migrationThreshold = at(264).u64(),
            migrationBaseThreshold = at(272).u64(),
            migrationSqrtPrice = at(280).u128(),
            sqrtStartPrice = at(392).u128(),
            curve = curve,
        )
    }

    private fun flagProgram(flag: Int) = when (flag) {
        0 -> SolAddr.TOKEN_PROGRAM
        1 -> SolAddr.TOKEN_2022_PROGRAM
        else -> null
    }

    /** Slot or Unix time, as the config's activation counts. */
    private fun pointOf(v: MeteoraDbcVenue) = if (v.activationType == 0) v.clock.slot else v.clock.unixTime

    private fun complete(v: MeteoraDbcVenue) = v.quoteReserve >= v.migrationThreshold

    private fun baseFeeOf(v: MeteoraDbcVenue, input: BigInteger, buy: Boolean): BigInteger {
        val point = pointOf(v)
        return when (val f = v.baseFee) {
            is DbcBaseFee.Scheduler -> {
                if (f.frequency.signum() == 0) return f.cliff
                if (point < v.activationPoint) refuse()
                val passed = (point - v.activationPoint) / f.frequency
                val period = if (passed < f.periods) passed else f.periods
                MeteoraMath.scheduledFee(f.exponential, f.cliff, f.reduction, period, false)
            }
            is DbcBaseFee.Limiter -> {
                val zero = f.reference.signum() == 0 && f.duration.signum() == 0 && f.incrementBps.signum() == 0
                if (zero || !buy || point > v.activationPoint + f.duration) f.cliff
                else MeteoraMath.rateLimitedFee(input, f.cliff, f.reference, f.incrementBps, MAX_FEE)
            }
        }
    }

    /** The coin a buy of `amountIn` gets, up the curve's segments to the migration price. */
    private fun quoteToBase(v: MeteoraDbcVenue, amountIn: BigInteger): BigInteger {
        var price = v.sqrtPrice
        var left = amountIn
        var out = ZERO
        for ((upper, liquidity) in v.curve) {
            if (upper.signum() == 0 || liquidity.signum() == 0) break
            val stop = if (v.migrationSqrtPrice < upper) v.migrationSqrtPrice else upper
            if (stop <= price) continue
            val room = deltaQuote(price, stop, liquidity, true)
            if (left < room) {
                val next = nextSqrtFromInput(price, liquidity, left, false)
                out = fitU64(out + fitU64(deltaBase(price, next, liquidity, false)))
                left = ZERO
                break
            }
            out = fitU64(out + fitU64(deltaBase(price, stop, liquidity, false)))
            left -= room
            price = stop
            if (stop == v.migrationSqrtPrice) break
        }
        // A buy that would run past the migration price does not fill.
        return if (left.signum() == 0) out else refuse()
    }

    /** The quote a sale of `amountIn` gets, down the curve's segments to its start price. */
    private fun baseToQuote(v: MeteoraDbcVenue, amountIn: BigInteger): BigInteger {
        val c = v.curve
        var price = v.sqrtPrice
        var left = amountIn
        var out = ZERO
        for (i in c.size - 2 downTo 0) {
            val lower = c.getOrNull(i)?.sqrtPrice ?: ZERO
            val liquidity = c.getOrNull(i + 1)?.liquidity ?: ZERO
            if (lower.signum() == 0 || (c.getOrNull(i)?.liquidity ?: ZERO).signum() == 0 || lower >= price) continue
            val room = deltaBase(lower, price, liquidity, true)
            if (left < room) {
                val next = nextSqrtFromInput(price, liquidity, left, true)
                out = fitU64(out + fitU64(deltaQuote(next, price, liquidity, false)))
                left = ZERO
                break
            }
            out = fitU64(out + fitU64(deltaQuote(lower, price, liquidity, false)))
            left -= room
            price = lower
        }
        if (left.signum() != 0) {
            val liquidity = c.getOrNull(0)?.liquidity ?: ZERO
            var next = nextSqrtFromInput(price, liquidity, left, true)
            if (next < v.sqrtStartPrice) {
                next = v.sqrtStartPrice
                val used = fitU64(deltaBase(next, price, liquidity, true))
                if (used > left) refuse()
                left -= used
            } else {
                left = ZERO
            }
            out = fitU64(out + fitU64(deltaQuote(next, price, liquidity, false)))
        }
        return if (left.signum() == 0) out else refuse()
    }

    /** Each pool's config: it never changes, so later reads take it along with the pool. */
    private val configOf = ConcurrentHashMap<String, String>()

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): MeteoraDbcVenue? {
        val before = configOf[pool]
        val accs = ctx.read(if (before != null) listOf(pool, SolAddr.SYSVAR_CLOCK, before) else listOf(pool, SolAddr.SYSVAR_CLOCK))
        val poolAcc = accs.getOrNull(0)
        val clockAcc = accs.getOrNull(1)
        var cfgAcc = accs.getOrNull(2)
        if (poolAcc?.owner != program) return null
        val s = decodeDbcPool(poolAcc.data)
        if (s?.baseMint != mint) return null
        if (before != s.config) cfgAcc = ctx.read(listOf(s.config)).getOrNull(0)
        val c = if (cfgAcc?.owner == program) decodeDbcConfig(cfgAcc.data) else null
        val clock = clockAcc?.let { Layouts.decodeClock(it.data) }
        val baseFee = c?.baseFee
        if (c == null || baseFee == null || clock == null || c.collectFeeMode > 1) return null
        if (flagProgram(s.poolType) != ctx.coinProgram) return null
        val quote = ctx.quoteAsset(c.quoteMint) ?: return null
        if (quote.program != flagProgram(c.quoteTokenFlag)) return null
        if (configOf.size > 500) configOf.clear()
        configOf[pool] = s.config
        return MeteoraDbcVenue(
            pool = pool,
            mint = mint,
            tokenProgram = ctx.coinProgram,
            quote = quote,
            config = s.config,
            baseVault = s.baseVault,
            quoteVault = s.quoteVault,
            activationType = c.activationType,
            activationPoint = s.activationPoint,
            clock = clock,
            collectFeeMode = c.collectFeeMode,
            baseFee = baseFee,
            volatility = c.volatility?.let { (binStep, control) -> DbcVolatility(s.accumulator, binStep, control) },
            sqrtPrice = s.sqrtPrice,
            quoteReserve = s.quoteReserve,
            migrationThreshold = c.migrationThreshold,
            migrationSqrtPrice = c.migrationSqrtPrice,
            sqrtStartPrice = c.sqrtStartPrice,
            curve = c.curve,
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeDbcPool(data)?.baseMint

    override fun label(v: MeteoraDbcVenue) = "Meteora DBC"

    override fun quote(v: MeteoraDbcVenue, side: Side, amountIn: BigInteger): BigInteger = quoteOrZero {
        if (amountIn.signum() <= 0 || amountIn > U64_MAX || complete(v)) return@quoteOrZero ZERO
        val buy = side == Side.BUY
        val vol = v.volatility
        val total = baseFeeOf(v, amountIn, buy) + (vol?.let { MeteoraMath.volatilityFee(it.accumulator, it.binStep, it.control) } ?: ZERO)
        val fee = if (total > MAX_FEE) MAX_FEE else total
        if (buy && v.collectFeeMode == 0) return@quoteOrZero quoteToBase(v, amountIn - feeOn(amountIn, fee))
        val out = if (buy) quoteToBase(v, amountIn) else baseToQuote(v, amountIn)
        out - feeOn(out, fee)
    }

    override fun spot(v: MeteoraDbcVenue): Double {
        val r = v.sqrtPrice.toDouble() / ONE_Q64.toDouble()
        return r * r
    }

    override fun idle(v: MeteoraDbcVenue, nowSec: Double): String? =
        if (complete(v)) "This Meteora bonding curve is complete. Trading moves to its pool once it migrates." else null

    override fun swap(v: MeteoraDbcVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val (source, destination) = userAccounts(v, side, user)
        val keys = mutableListOf(
            meta(POOL_AUTHORITY),
            meta(v.config),
            meta(v.pool, true),
            meta(source, true),
            meta(destination, true),
            meta(v.baseVault, true),
            meta(v.quoteVault, true),
            meta(v.mint),
            meta(v.quote.mint),
            meta(user, false, true),
            meta(v.tokenProgram),
            meta(v.quote.program),
            // No referral account: the program's own address stands in for none.
            meta(program),
            meta(EVENT_AUTHORITY),
            meta(program),
        )
        // A rate-limited curve checks the transaction holds no other swap on it.
        if (v.baseFee is DbcBaseFee.Limiter) keys += meta(SolAddr.SYSVAR_INSTRUCTIONS)
        return Instruction(program, keys, concat(SWAP, u64(amountIn), u64(minOut)))
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 24 || !hasDiscriminator(data, SWAP)) return null
        val r = Reader(data, 8)
        return SwapAmounts(r.u64(), r.u64())
    }
}
