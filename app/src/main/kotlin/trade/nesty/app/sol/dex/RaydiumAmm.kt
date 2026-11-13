package trade.nesty.app.sol.dex

import trade.nesty.app.evm.Side
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.Layouts
import trade.nesty.app.sol.QuoteAsset
import trade.nesty.app.sol.Reader
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolAddr.TOKEN_PROGRAM
import trade.nesty.app.sol.bytesOf
import trade.nesty.app.sol.concat
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

data class RaydiumAmmVenue(
    override val pool: String,
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    /** Whether the coin is the pool's "coin" side (else its "pc" side). */
    val coinIsMint: Boolean,
    val status: BigInteger,
    val openTime: BigInteger,
    val feeNumerator: BigInteger,
    val feeDenominator: BigInteger,
    val coinVault: String,
    val pcVault: String,
    /** The vaults less the profit the pool has yet to take: what swaps price on. */
    val coinReserve: BigInteger,
    val pcReserve: BigInteger,
) : DexVenue {
    override val dex get() = DexKind.RAYDIUM_AMM
}

/**
 * Raydium's constant-product AMM (v4), where pump.fun coins went before PumpSwap existed. Facts
 * from its source (raydium-io/raydium-amm): `SwapBaseInV2` (tag 16) swaps without the order book,
 * prices on the vaults less the profit the pool has yet to take, takes its fee from the input
 * rounded up, and trades SPL Token mints only. Ported from dex/raydium-amm.ts.
 */
object RaydiumAmm : DexModule<RaydiumAmmVenue>(DexKind.RAYDIUM_AMM, "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8", 90_000) {
    private val authorityAndNonce by lazy { SolAddr.pdaWithBump(program, SolAddr.seed("amm authority")) }
    val AUTHORITY: String get() = authorityAndNonce.first

    private const val POOL_SIZE = 752
    private const val SWAP_BASE_IN_V2 = 16

    /** Statuses that allow swaps: Initialized, SwapOnly, and WaitingTrade once it opens. */
    private val SWAP_STATUSES = setOf(1L, 6L, 7L).map(BigInteger::valueOf).toSet()
    private val WAITING_TRADE = BigInteger.valueOf(7)

    class PoolState(
        val status: BigInteger,
        val nonce: BigInteger,
        val feeNumerator: BigInteger,
        val feeDenominator: BigInteger,
        val pnlCoin: BigInteger,
        val pnlPc: BigInteger,
        val openTime: BigInteger,
        val coinVault: String,
        val pcVault: String,
        val coinMint: String,
        val pcMint: String,
    )

    /** AmmInfo: no discriminator, fixed offsets (u64s, then the fees, state data and keys). */
    fun decodeAmmInfo(data: ByteArray): PoolState? {
        if (data.size != POOL_SIZE) return null
        fun at(o: Int) = Reader(data, o)
        return PoolState(
            status = at(0).u64(),
            nonce = at(8).u64(),
            feeNumerator = at(176).u64(),
            feeDenominator = at(184).u64(),
            pnlCoin = at(192).u64(),
            pnlPc = at(200).u64(),
            openTime = at(224).u64(),
            coinVault = at(336).pubkey(),
            pcVault = at(368).pubkey(),
            coinMint = at(400).pubkey(),
            pcMint = at(432).pubkey(),
        )
    }

    /** Pools read before: their keys never change, so later reads take the vaults along. */
    private val known = ConcurrentHashMap<String, PoolState>()

    private fun reserves(v: RaydiumAmmVenue, side: Side): Pair<BigInteger, BigInteger> {
        // Buying spends the quote side; the coin is "coin" or "pc" depending on the pool.
        val coinIn = if (side == Side.BUY) !v.coinIsMint else v.coinIsMint
        return if (coinIn) v.coinReserve to v.pcReserve else v.pcReserve to v.coinReserve
    }

    override suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): RaydiumAmmVenue? {
        // The coin must be an SPL Token mint: the program refuses Token-2022.
        if (ctx.coinProgram != TOKEN_PROGRAM) return null
        val before = known[pool]
        val accs = ctx.read(if (before != null) listOf(pool, before.coinVault, before.pcVault) else listOf(pool))
        val poolAcc = accs.getOrNull(0)
        var coinAcc = accs.getOrNull(1)
        var pcAcc = accs.getOrNull(2)
        if (poolAcc == null || poolAcc.owner != program) return null
        val state = decodeAmmInfo(poolAcc.data) ?: return null
        if (state.nonce != BigInteger.valueOf(authorityAndNonce.second.toLong())) return null
        if (before?.coinVault != state.coinVault || before?.pcVault != state.pcVault) {
            val vaults = ctx.read(listOf(state.coinVault, state.pcVault))
            coinAcc = vaults.getOrNull(0)
            pcAcc = vaults.getOrNull(1)
        }
        val coinIsMint = state.coinMint == mint
        if (!coinIsMint && state.pcMint != mint) return null
        val quote = ctx.quoteAsset(if (coinIsMint) state.pcMint else state.coinMint) ?: return null
        if (quote.program != TOKEN_PROGRAM) return null
        val coinVault = coinAcc?.let { Layouts.decodeTokenAccount(it.data) } ?: return null
        val pcVault = pcAcc?.let { Layouts.decodeTokenAccount(it.data) } ?: return null
        if (coinVault.amount < state.pnlCoin || pcVault.amount < state.pnlPc) return null
        if (known.size > 200) known.clear()
        known[pool] = state
        return RaydiumAmmVenue(
            pool = pool,
            mint = mint,
            tokenProgram = TOKEN_PROGRAM,
            quote = quote,
            coinIsMint = coinIsMint,
            status = state.status,
            openTime = state.openTime,
            feeNumerator = state.feeNumerator,
            feeDenominator = state.feeDenominator,
            coinVault = state.coinVault,
            pcVault = state.pcVault,
            coinReserve = coinVault.amount - state.pnlCoin,
            pcReserve = pcVault.amount - state.pnlPc,
        )
    }

    override fun coinOf(data: ByteArray): String? = decodeAmmInfo(data)?.let { SolAddr.coinOfPair(it.coinMint, it.pcMint) }

    override fun label(v: RaydiumAmmVenue) = "Raydium AMM"

    override fun quote(v: RaydiumAmmVenue, side: Side, amountIn: BigInteger): BigInteger {
        if (amountIn.signum() <= 0 || v.feeDenominator.signum() == 0) return BigInteger.ZERO
        val (inR, outR) = reserves(v, side)
        // The fee is rounded up, then the rest swaps at constant product, rounded down.
        val fee = (amountIn * v.feeNumerator + v.feeDenominator - BigInteger.ONE) / v.feeDenominator
        val net = amountIn - fee
        if (net.signum() <= 0) return BigInteger.ZERO
        val out = outR * net / (inR + net)
        return if (out.signum() > 0 && out < outR) out else BigInteger.ZERO
    }

    override fun spot(v: RaydiumAmmVenue): Double {
        val (coin, quote) = if (v.coinIsMint) v.coinReserve to v.pcReserve else v.pcReserve to v.coinReserve
        return if (coin.signum() > 0) quote.toDouble() / coin.toDouble() else 0.0
    }

    override fun idle(v: RaydiumAmmVenue, nowSec: Double): String? {
        if (v.status !in SWAP_STATUSES) return "Raydium has paused this pool."
        if (v.status == WAITING_TRADE && BigInteger.valueOf(floor(nowSec).toLong()) < v.openTime) return "This Raydium pool has not opened yet."
        return null
    }

    override fun swap(v: RaydiumAmmVenue, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction {
        val (source, destination) = userAccounts(v, side, user)
        return Instruction(
            program,
            listOf(
                meta(TOKEN_PROGRAM),
                meta(v.pool, true),
                meta(AUTHORITY),
                meta(v.coinVault, true),
                meta(v.pcVault, true),
                meta(source, true),
                meta(destination, true),
                meta(user, false, true),
            ),
            concat(bytesOf(SWAP_BASE_IN_V2), u64(amountIn), u64(minOut)),
        )
    }

    override fun amounts(data: ByteArray): SwapAmounts? {
        if (data.size != 17 || data[0].toInt() != SWAP_BASE_IN_V2) return null
        val r = Reader(data, 1)
        return SwapAmounts(r.u64(), r.u64())
    }
}
