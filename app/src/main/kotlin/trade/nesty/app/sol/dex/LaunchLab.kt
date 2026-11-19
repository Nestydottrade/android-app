package trade.nesty.app.sol.dex

import trade.nesty.app.evm.Side
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.QuoteAsset
import trade.nesty.app.sol.Reader
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolAddr.SYSTEM_PROGRAM
import trade.nesty.app.sol.bytesOf
import trade.nesty.app.sol.concat
import trade.nesty.app.sol.hasDiscriminator
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

data class LaunchLabVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    val config: String,
    val platform: String,
    val creator: String,
    val vaultA: String,
    val vaultB: String,
    val status: Int,
    /** The curve's shape, from its config: 0 constant product, 1 fixed price, 2 linear. */
    val curve: Int,
    val virtualA: BigInteger,
    val virtualB: BigInteger,
    /** Coin sold so far, and quote paid in (fees excluded). */
    val realA: BigInteger,
    val realB: BigInteger,
    /** What the curve sells before it completes. */
    val totalSellA: BigInteger,
    /** Protocol, platform and creator fee rates, together. */
    val feeRate: BigInteger,
) : DexVenue {
    override val dex get() = DexKind.LAUNCHLAB
}

/**
 * Raydium LaunchLab, the bonding curves behind Bonk.fun and other launch sites. Facts from
 * Raydium's SDK (raydium-sdk-V2, launchpad): a curve sells its coin for one quote token (wrapped
 * SOL or a stablecoin) on a constant-product, fixed-price or linear curve; the protocol, platform
 * and creator fees are taken from the quote side, rounded up; a buy past what is left for sale
 * fills only that, and charges only for it. Ported from dex/launchlab.ts.
 */
object LaunchLab : DexModule<LaunchLabVenue>(DexKind.LAUNCHLAB, "LanMV9sAd7wArD4vJFi2qDdfnVhFxYSUg6eADduJ3uj", 200_000) {
    private val AUTHORITY by lazy { SolAddr.pda(program, SolAddr.seed("vault_auth_seed")) }
    private val EVENT_AUTHORITY by lazy { SolAddr.pda(program, SolAddr.seed("__event_authority")) }

    private val POOL_DISC = bytesOf(247, 237, 227, 245, 215, 195, 222, 70)
    private val CONFIG_DISC = bytesOf(149, 8, 156, 202, 160, 252, 176, 217)
    private val PLATFORM_DISC = bytesOf(160, 78, 128, 0, 248, 83, 230, 160)
    private val BUY_EXACT_IN = bytesOf(250, 234, 13, 123, 213, 156, 19, 236)
    private val SELL_EXACT_IN = bytesOf(149, 39, 222, 155, 211, 124, 152, 26)

    /** Fee rates are in millionths. */
    private val RATE = BigInteger.valueOf(1_000_000)
    private val Q64 = BigInteger.ONE.shiftLeft(64)
    private val TWO = BigInteger.valueOf(2)

    /** Pool status while its curve sells; later ones are migrating, then migrated. */
    private const val FUNDING = 0

    class PoolState(
        val status: Int,
        val virtualA: BigInteger,
        val virtualB: BigInteger,
        val realA: BigInteger,
        val realB: BigInteger,
        val totalSellA: BigInteger,
        val config: String,
        val platform: String,
        val mintA: String,
        val mintB: String,
        val vaultA: String,
        val vaultB: String,
        val creator: String,
    )

    /** PoolState: borsh, fixed offsets after the discriminator. */
    fun decodeLaunchPool(data: ByteArray): PoolState? {
        if (data.size < 367 || !hasDiscriminator(data, POOL_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        return PoolState(
            status = at(17).u8(),
            totalSellA = at(29).u64(),
            virtualA = at(37).u64(),
            virtualB = at(45).u64(),
            realA = at(53).u64(),
            realB = at(61).u64(),
            config = at(141).pubkey(),
            platform = at(173).pubkey(),
            mintA = at(205).pubkey(),
            mintB = at(237).pubkey(),
            vaultA = at(269).pubkey(),
            vaultB = at(301).pubkey(),
            creator = at(333).pubkey(),
        )
    }

    private fun ceilRate(amount: BigInteger, rate: BigInteger) = (amount * rate + RATE - BigInteger.ONE) / RATE

    /** Largest integer whose square is at most `n`. */
    fun isqrt(n: BigInteger): BigInteger {
        if (n < TWO) return n
        // Newton's method from above: exact for any size.
        var x = BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2)
        while (true) {
            val y = (x + n / x).shiftRight(1)
            if (y >= x) return x
            x = y
        }
    }

    /** The coin a net quote amount buys on the curve, before the cap on what is left. */
    private fun curveBuy(v: LaunchLabVenue, net: BigInteger): BigInteger = when (v.curve) {
        0 -> net * (v.virtualA - v.realA) / (v.virtualB + v.realB + net)
        1 -> if (v.virtualB.signum() > 0) v.virtualA * net / v.virtualB else BigInteger.ZERO
        else -> if (v.virtualA.signum() > 0) isqrt(TWO * (v.realB + net) * Q64 / v.virtualA) - v.realA else BigInteger.ZERO
    }

    /** The quote selling `a` of the coin takes out of the curve, before fees. */
    private fun curveSell(v: LaunchLabVenue, a: BigInteger): BigInteger = when (v.curve) {
        0 -> a * (v.virtualB + v.realB) / (v.virtualA - v.realA + a)
        1 -> if (v.virtualA.signum() > 0) v.virtualB * a / v.virtualA else BigInteger.ZERO
        else -> {
            if (a > v.realA) {
                BigInteger.ZERO
            } else {
                val left = v.realA - a
                val quote = (v.virtualA * left * left + TWO * Q64 - BigInteger.ONE) / (TWO * Q64)
                v.realB - quote
            }
        }
    }

    private fun poolAddress(mintA: String, mintB: String) = SolAddr.pda(program, SolAddr.seed("pool"), mintA, mintB)

    /** Pools read before: their keys never change, so later reads take the rest along. */
    private val known = ConcurrentHashMap<String, PoolState>()

    // A coin's curve is found from its mint: one per quote token it may sell for.
    override fun candidates(mint: String): List<String> = listOf(poolAddress(mint, SolAddr.WSOL_MINT), poolAddress(mint, SolAddr.USD1_MINT))

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): LaunchLabVenue? {
        val before = known[pool]
        val accs = ctx.read(if (before != null) listOf(pool, before.config, before.platform, before.vaultA, before.vaultB) else listOf(pool))
        val poolAcc = accs.getOrNull(0)
        var rest = accs.drop(1)
        if (poolAcc?.owner != program) return null
        val s = decodeLaunchPool(poolAcc.data) ?: return null
        if (s.mintA != mint) return null
        if (before?.config != s.config || before?.platform != s.platform || before?.vaultA != s.vaultA || before?.vaultB != s.vaultB) {
            rest = ctx.read(listOf(s.config, s.platform, s.vaultA, s.vaultB))
        }
        val cfgAcc = rest.getOrNull(0)
        val platAcc = rest.getOrNull(1)
        val quote = ctx.quoteAsset(s.mintB) ?: return null
        if (cfgAcc == null || cfgAcc.owner != program || !hasDiscriminator(cfgAcc.data, CONFIG_DISC)) return null
        if (platAcc == null || platAcc.owner != program || !hasDiscriminator(platAcc.data, PLATFORM_DISC)) return null
        val curve = Reader(cfgAcc.data, 16).u8()
        if (curve != 0 && curve != 1 && curve != 2) return null
        val tradeFeeRate = Reader(cfgAcc.data, 27).u64()
        val platformFeeRate = Reader(platAcc.data, 104).u64()
        val creatorFeeRate = Reader(platAcc.data, 720).u64()
        val feeRate = tradeFeeRate + platformFeeRate + creatorFeeRate
        if (feeRate >= RATE) return null
        if (known.size > 200) known.clear()
        known[pool] = s
        return LaunchLabVenue(
            pool = pool,
            mint = mint,
            tokenProgram = ctx.coinProgram,
            quote = quote,
            config = s.config,
            platform = s.platform,
            creator = s.creator,
            vaultA = s.vaultA,
            vaultB = s.vaultB,
            status = s.status,
            curve = curve,
            virtualA = s.virtualA,
            virtualB = s.virtualB,
            realA = s.realA,
            realB = s.realB,
            totalSellA = s.totalSellA,
            feeRate = feeRate,
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeLaunchPool(data)?.let { SolAddr.coinOfPair(it.mintA, it.mintB) }

    override fun label(v: LaunchLabVenue) = "LaunchLab"

    override fun quote(v: LaunchLabVenue, side: Side, amountIn: BigInteger): BigInteger {
        if (amountIn.signum() <= 0 || v.status != FUNDING) return BigInteger.ZERO
        if (side == Side.BUY) {
            val net = amountIn - ceilRate(amountIn, v.feeRate)
            if (net.signum() <= 0) return BigInteger.ZERO
            val out = curveBuy(v, net)
            val left = v.totalSellA - v.realA
            return if (out.signum() <= 0) BigInteger.ZERO else if (out > left) left else out
        }
        val gross = curveSell(v, amountIn)
        if (gross.signum() <= 0 || gross > v.realB) return BigInteger.ZERO
        val out = gross - ceilRate(gross, v.feeRate)
        return if (out.signum() > 0) out else BigInteger.ZERO
    }

    override fun spot(v: LaunchLabVenue): Double = when (v.curve) {
        0 -> {
            val coin = v.virtualA - v.realA
            if (coin.signum() > 0) (v.virtualB + v.realB).toDouble() / coin.toDouble() else 0.0
        }
        1 -> if (v.virtualA.signum() > 0) v.virtualB.toDouble() / v.virtualA.toDouble() else 0.0
        else -> (v.virtualA * v.realA).toDouble() / Q64.toDouble()
    }

    override fun idle(v: LaunchLabVenue, nowSec: Double): String? =
        if (v.status == FUNDING) null else tr("This LaunchLab curve is complete. Trading moves to Raydium once it migrates.")

    override fun swap(v: LaunchLabVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val coinAta = SolAddr.associatedTokenAddress(user, v.mint, v.tokenProgram)
        val quoteAta = SolAddr.associatedTokenAddress(user, v.quote.mint, v.quote.program)
        return Instruction(
            program,
            listOf(
                meta(user, true, true),
                meta(AUTHORITY),
                meta(v.config),
                meta(v.platform),
                meta(v.pool, true),
                meta(coinAta, true),
                meta(quoteAta, true),
                meta(v.vaultA, true),
                meta(v.vaultB, true),
                meta(v.mint),
                meta(v.quote.mint),
                meta(v.tokenProgram),
                meta(v.quote.program),
                meta(EVENT_AUTHORITY),
                meta(program),
                meta(SYSTEM_PROGRAM),
                // Where the platform's and the creator's fees accrue.
                meta(SolAddr.pda(program, v.platform, v.quote.mint), true),
                meta(SolAddr.pda(program, v.creator, v.quote.mint), true),
            ),
            // The amount, the minimum back, and no share fee.
            concat(if (side == Side.BUY) BUY_EXACT_IN else SELL_EXACT_IN, u64(amountIn), u64(minOut), u64(BigInteger.ZERO)),
        )
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 32) return null
        if (!hasDiscriminator(data, BUY_EXACT_IN) && !hasDiscriminator(data, SELL_EXACT_IN)) return null
        val r = Reader(data, 8)
        return SwapAmounts(r.u64(), r.u64())
    }
}
