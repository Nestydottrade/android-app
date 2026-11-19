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
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

data class RaydiumCpmmVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    /** Whether the coin is the pool's token 0 (else token 1). */
    val coinIs0: Boolean,
    val config: String,
    val observation: String,
    val vault0: String,
    val vault1: String,
    val status: Int,
    val openTime: BigInteger,
    /** The vaults less the fees owed out of them: what swaps price on. */
    val reserve0: BigInteger,
    val reserve1: BigInteger,
    val tradeFeeRate: BigInteger,
    /** 0 unless the pool takes a creator fee. */
    val creatorFeeRate: BigInteger,
    /** 0: on the input, 1: only in token 0, 2: only in token 1. */
    val creatorFeeOn: Int,
) : DexVenue {
    override val dex get() = DexKind.RAYDIUM_CPMM
}

/**
 * Raydium's constant-product pools (CPMM), where Raydium LaunchLab coins graduate. Facts from its
 * source (raydium-io/raydium-cp-swap): `swap_base_input` prices on the vaults less the fees owed
 * out of them, takes the trade fee from the input rounded up, and a creator fee (when the pool
 * enables it) from the input or the output as the pool says. Ported from dex/raydium-cpmm.ts.
 */
object RaydiumCpmm : DexModule<RaydiumCpmmVenue>(DexKind.RAYDIUM_CPMM, "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C", 150_000) {
    val AUTHORITY: String by lazy { SolAddr.pda(program, SolAddr.seed("vault_and_lp_mint_auth_seed")) }

    val POOL_DISC = bytesOf(247, 237, 227, 245, 215, 195, 222, 70)
    private val CONFIG_DISC = bytesOf(218, 244, 33, 104, 203, 203, 43, 111)
    private val SWAP_BASE_INPUT = bytesOf(143, 190, 90, 218, 196, 30, 51, 222)
    private const val POOL_SIZE = 637

    /** Fee rates are in millionths. */
    private val RATE = BigInteger.valueOf(1_000_000)

    /** Status bit 2 set: swaps disabled. */
    private const val SWAP_DISABLED = 4

    class PoolState(
        val config: String,
        val vault0: String,
        val vault1: String,
        val mint0: String,
        val mint1: String,
        val program0: String,
        val program1: String,
        val observation: String,
        val status: Int,
        /** Protocol, fund and creator fees owed out of each vault. */
        val owed0: BigInteger,
        val owed1: BigInteger,
        val openTime: BigInteger,
        val creatorFeeOn: Int,
        val creatorFeeEnabled: Boolean,
    )

    /** PoolState: zero-copy and packed, at fixed offsets after the discriminator. */
    fun decodeCpmmPool(data: ByteArray): PoolState? {
        if (data.size != POOL_SIZE || !hasDiscriminator(data, POOL_DISC)) return null
        fun at(o: Int) = Reader(data, o)
        // Protocol fees of tokens 0 and 1, then fund fees, then (later) creator fees.
        fun owed(protocol: Int, fund: Int, creator: Int) = at(protocol).u64() + at(fund).u64() + at(creator).u64()
        return PoolState(
            config = at(8).pubkey(),
            vault0 = at(72).pubkey(),
            vault1 = at(104).pubkey(),
            mint0 = at(168).pubkey(),
            mint1 = at(200).pubkey(),
            program0 = at(232).pubkey(),
            program1 = at(264).pubkey(),
            observation = at(296).pubkey(),
            status = at(329).u8(),
            owed0 = owed(341, 357, 397),
            owed1 = owed(349, 365, 405),
            openTime = at(373).u64(),
            creatorFeeOn = at(389).u8(),
            creatorFeeEnabled = at(390).bool(),
        )
    }

    /** AmmConfig: the trade fee and the creator fee rates. */
    private fun decodeConfig(data: ByteArray): Pair<BigInteger, BigInteger>? {
        if (!hasDiscriminator(data, CONFIG_DISC)) return null
        return Reader(data, 12).u64() to Reader(data, 108).u64()
    }

    /** Pools read before: their keys never change, so later reads take the rest along. */
    private val known = ConcurrentHashMap<String, PoolState>()

    private fun ceilRate(amount: BigInteger, rate: BigInteger) = (amount * rate + RATE - BigInteger.ONE) / RATE

    /** Whether `side` puts token 0 into the pool. */
    private fun zeroIn(v: RaydiumCpmmVenue, side: Side) = (side == Side.SELL) == v.coinIs0

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): RaydiumCpmmVenue? {
        val before = known[pool]
        val accs = ctx.read(if (before != null) listOf(pool, before.config, before.vault0, before.vault1) else listOf(pool))
        val poolAcc = accs.getOrNull(0)
        var cfgAcc = accs.getOrNull(1)
        var acc0 = accs.getOrNull(2)
        var acc1 = accs.getOrNull(3)
        if (poolAcc?.owner != program) return null
        val s = decodeCpmmPool(poolAcc.data) ?: return null
        if (before?.config != s.config || before?.vault0 != s.vault0 || before?.vault1 != s.vault1) {
            val more = ctx.read(listOf(s.config, s.vault0, s.vault1))
            cfgAcc = more.getOrNull(0)
            acc0 = more.getOrNull(1)
            acc1 = more.getOrNull(2)
        }
        val coinIs0 = s.mint0 == mint
        if (!coinIs0 && s.mint1 != mint) return null
        if ((if (coinIs0) s.program0 else s.program1) != ctx.coinProgram) return null
        val quote = ctx.quoteAsset(if (coinIs0) s.mint1 else s.mint0) ?: return null
        if (quote.program != (if (coinIs0) s.program1 else s.program0)) return null
        val cfg = if (cfgAcc?.owner == program) decodeConfig(cfgAcc.data) else null
        val v0 = acc0?.let { Layouts.decodeTokenAccount(it.data) }
        val v1 = acc1?.let { Layouts.decodeTokenAccount(it.data) }
        if (cfg == null || v0 == null || v1 == null || v0.amount < s.owed0 || v1.amount < s.owed1) return null
        if (known.size > 200) known.clear()
        known[pool] = s
        return RaydiumCpmmVenue(
            pool = pool,
            mint = mint,
            tokenProgram = ctx.coinProgram,
            quote = quote,
            coinIs0 = coinIs0,
            config = s.config,
            observation = s.observation,
            vault0 = s.vault0,
            vault1 = s.vault1,
            status = s.status,
            openTime = s.openTime,
            reserve0 = v0.amount - s.owed0,
            reserve1 = v1.amount - s.owed1,
            tradeFeeRate = cfg.first,
            creatorFeeRate = if (s.creatorFeeEnabled) cfg.second else BigInteger.ZERO,
            creatorFeeOn = s.creatorFeeOn,
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeCpmmPool(data)?.let { SolAddr.coinOfPair(it.mint0, it.mint1) }

    override fun label(v: RaydiumCpmmVenue) = "Raydium CPMM"

    override fun quote(v: RaydiumCpmmVenue, side: Side, amountIn: BigInteger): BigInteger {
        val in0 = zeroIn(v, side)
        val (inR, outR) = if (in0) v.reserve0 to v.reserve1 else v.reserve1 to v.reserve0
        if (amountIn.signum() <= 0 || inR.signum() == 0 || outR.signum() == 0) return BigInteger.ZERO
        val onInput = v.creatorFeeOn == 0 || v.creatorFeeOn == (if (in0) 1 else 2)
        // The trade fee (with the creator's, when it is taken from the input) is rounded up.
        val fee = ceilRate(amountIn, v.tradeFeeRate + (if (onInput) v.creatorFeeRate else BigInteger.ZERO))
        if (fee >= amountIn) return BigInteger.ZERO
        val net = amountIn - fee
        val swapped = net * outR / (inR + net)
        val out = if (onInput) swapped else swapped - ceilRate(swapped, v.creatorFeeRate)
        return if (out.signum() > 0) out else BigInteger.ZERO
    }

    override fun spot(v: RaydiumCpmmVenue): Double {
        val (coin, quote) = if (v.coinIs0) v.reserve0 to v.reserve1 else v.reserve1 to v.reserve0
        return if (coin.signum() > 0) quote.toDouble() / coin.toDouble() else 0.0
    }

    override fun idle(v: RaydiumCpmmVenue, nowSec: Double): String? {
        if (v.status and SWAP_DISABLED != 0) return tr("Raydium has paused swaps on this pool.")
        if (BigInteger.valueOf(floor(nowSec).toLong()) < v.openTime) return tr("This Raydium pool has not opened yet.")
        return null
    }

    override fun swap(v: RaydiumCpmmVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val (source, destination) = userAccounts(v, side, user)
        val in0 = zeroIn(v, side)
        val coin = QuoteAsset(v.mint, v.tokenProgram)
        val quote = QuoteAsset(v.quote.mint, v.quote.program)
        val (t0, t1) = if (v.coinIs0) coin to quote else quote to coin
        val (tin, tout) = if (in0) t0 to t1 else t1 to t0
        val (vin, vout) = if (in0) v.vault0 to v.vault1 else v.vault1 to v.vault0
        return Instruction(
            program,
            listOf(
                meta(user, false, true),
                meta(AUTHORITY),
                meta(v.config),
                meta(v.pool, true),
                meta(source, true),
                meta(destination, true),
                meta(vin, true),
                meta(vout, true),
                meta(tin.program),
                meta(tout.program),
                meta(tin.mint),
                meta(tout.mint),
                meta(v.observation, true),
            ),
            concat(SWAP_BASE_INPUT, u64(amountIn), u64(minOut)),
        )
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 24 || !hasDiscriminator(data, SWAP_BASE_INPUT)) return null
        val r = Reader(data, 8)
        return SwapAmounts(r.u64(), r.u64())
    }
}
