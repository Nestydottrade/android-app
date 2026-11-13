package trade.nesty.app.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import trade.nesty.app.vault.WalletInfo
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A wallet's One Balance: its own coin on each chain, in dollars. Tokens it holds are positions,
 * not balance. A chain that could not be read is in `unread`, and is not counted as a zero.
 */
data class WalletBalance(
    val chains: Map<Chain, Double>,
    val unread: Set<Chain>,
    /** The chain's own coin in its smallest unit (wei, lamports). */
    val raw: Map<Chain, BigInteger> = emptyMap(),
    /** Dollars for a whole coin of each chain. */
    val prices: Map<Chain, Double> = emptyMap(),
    /** The named tokens held, by `feed:token` (lowercase), in their smallest unit. */
    val tokens: Map<String, BigInteger> = emptyMap(),
) {
    val total: Double get() = chains.values.sum()

    fun token(chain: Chain, address: String): BigInteger? = tokens[Balances.tokenKey(chain, address)]
}

object Balances {
    /** Dollars of an amount in the chain's smallest unit; null when it cannot be said. */
    fun usd(native: String, decimals: Int, price: Double?): Double? {
        if (price == null || price.isNaN() || price < 0) return null
        val raw = native.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: return null
        return raw.movePointLeft(decimals).multiply(BigDecimal.valueOf(price)).toDouble()
    }

    fun tokenKey(chain: Chain, address: String) = "${chain.feed}:${address.lowercase()}"

    /** The chains a wallet can hold coins on: the EVM chains for an EVM address, Solana for a Solana one. */
    fun chainsOf(wallet: WalletInfo): List<Chain> = Chain.entries.filter { if (it.isEvm) wallet.evm != null else wallet.sol != null }

    /**
     * One wallet's balance from the two answers (null: not answered). A chain that was not read
     * this time keeps what was read before, and stays marked as unread.
     */
    fun of(wallet: WalletInfo, evm: BalancesAnswer?, sol: BalancesAnswer?, before: WalletBalance? = null): WalletBalance {
        val read = HashMap<Chain, Double>()
        val raw = HashMap<Chain, BigInteger>()
        val prices = HashMap<Chain, Double>()
        val tokens = HashMap<String, BigInteger>()
        for (answer in listOfNotNull(evm, sol)) {
            for (c in answer.chains) {
                val chain = Chain.of(c.feed) ?: continue
                if (c.failed) continue
                usd(c.native, chain.decimals, c.usd)?.let { read[chain] = it }
                c.native.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }?.let { raw[chain] = it }
                c.usd?.takeIf { it > 0 && !it.isNaN() }?.let { prices[chain] = it }
                for (t in c.tokens) t.raw?.toBigIntegerOrNull()?.let { tokens[tokenKey(chain, t.address)] = it }
            }
        }
        val mine = chainsOf(wallet)
        val unread = mine.filter { it !in read }.toSet()
        val chains = mine.mapNotNull { c -> (read[c] ?: before?.chains?.get(c))?.let { c to it } }.toMap()
        // What was not read this time stays as it was.
        val keptRaw = before?.raw.orEmpty().filterKeys { it in unread } + raw
        val keptPrices = before?.prices.orEmpty() + prices
        val keptTokens = before?.tokens.orEmpty().filterKeys { key -> unread.any { key.startsWith("${it.feed}:") } } + tokens
        return WalletBalance(chains, unread, keptRaw, keptPrices, keptTokens)
    }
}

/**
 * The balances the screens show. The wallet that trades is read every 30 seconds while the app
 * is on screen, and right after each of its trades; the others only when the Wallets screen asks.
 *
 * The server sees each address it is asked about and the coins named with it (the wallet's
 * positions), as the app's privacy note has to say. Nothing else of a wallet goes to it.
 */
class BalanceModel(
    private val api: NestyApi,
    private val session: WalletSession,
    /** The coins a wallet holds positions in, to name with its address. */
    private val coinsOf: (WalletInfo) -> List<Pair<Chain, String>> = { emptyList() },
) {
    private val byWallet = mutableStateMapOf<String, WalletBalance>()

    fun of(wallet: WalletInfo?): WalletBalance? = wallet?.let { byWallet[it.id] }

    /** One Balance of the wallet that trades; null until it is read. */
    val active: WalletBalance? get() = of(session.active)

    suspend fun live() {
        snapshotFlow { session.active }.collectLatest { wallet ->
            if (wallet == null) {
                byWallet.clear()
                return@collectLatest
            }
            while (true) {
                read(wallet)
                delay(30_000)
            }
        }
    }

    /** Every wallet once, for the Wallets screen. */
    suspend fun readAll() {
        for (wallet in session.wallets) read(wallet)
    }

    /** The wallet that trades, now: after a trade lands. */
    suspend fun refresh() {
        session.active?.let { read(it) }
    }

    suspend fun read(wallet: WalletInfo) = coroutineScope {
        val named = coinsOf(wallet)
        val evm = async { wallet.evm?.let { a -> runCatching { api.balances(a, named.filter { it.first.isEvm }) }.getOrNull() } }
        val sol = async { wallet.sol?.let { a -> runCatching { api.balances(a, named.filter { !it.first.isEvm }) }.getOrNull() } }
        byWallet[wallet.id] = Balances.of(wallet, evm.await(), sol.await(), byWallet[wallet.id])
    }
}
