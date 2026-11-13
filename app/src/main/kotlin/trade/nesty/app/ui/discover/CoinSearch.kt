package trade.nesty.app.ui.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import trade.nesty.app.data.ApiException
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.ListToken
import trade.nesty.app.data.NestyApi

/**
 * Search, with a way in for a coin the search does not list. The server's search answers from
 * listed pools, so a launchpad coin pasted by its address comes back empty. Such an address is
 * then asked for as a coin on each chain it can be on, the way its token page asks. Only the
 * pasted address goes to the server, as in the search itself.
 */
object CoinSearch {
    private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val SOLANA_ADDRESS = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    /** How long one chain may take before it counts as not answered. */
    private const val CHAIN_WAIT_MS = 8_000L

    /** The chains a pasted address can be a coin on: every EVM chain, or Solana. None for a name. */
    fun chainsOf(term: String): List<Chain> = when {
        EVM_ADDRESS.matches(term) -> Chain.entries.filter { it.isEvm }
        SOLANA_ADDRESS.matches(term) -> listOf(Chain.SOLANA)
        else -> emptyList()
    }

    /** A coin's own answer as a search row. */
    fun row(c: Coin, chain: Chain, asked: String): ListToken = ListToken(
        feed = c.feed.ifBlank { chain.feed },
        address = c.address.ifBlank { asked },
        name = c.name,
        symbol = c.symbol,
        image = c.image,
        priceUsd = c.priceUsd,
        mcapUsd = c.mcapUsd,
        liquidityUsd = c.liquidityUsd,
        change = c.change,
        pair = c.pool?.address,
        dex = c.pool?.dex,
        createdAt = c.createdAt,
    )

    /** What one chain said of the address: the coin, no such coin, or nothing at all. */
    private sealed interface Said {
        data class Found(val row: ListToken) : Said
        data object NoCoin : Said
        data object Silent : Said
    }

    private suspend fun ask(api: NestyApi, chain: Chain, address: String): Said = try {
        val coin = withTimeoutOrNull(CHAIN_WAIT_MS) { api.token(chain, address) }
        when {
            coin == null -> Said.Silent
            // An answer without a name, a symbol or a price is not a coin to list.
            coin.symbol.isNullOrBlank() && coin.name.isNullOrBlank() && coin.priceUsd == null && coin.mcapUsd == null -> Said.NoCoin
            else -> Said.Found(row(coin, chain, address))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        // 404: no such coin on this chain. 400: not an address of this chain.
        if (e.notFound || e.status == 400) Said.NoCoin else Said.Silent
    } catch (_: Exception) {
        Said.Silent
    }

    /**
     * The coins found; no rows when there is none; null when it cannot be said, because the
     * search or a chain did not answer: no answer is not "no coin".
     *
     * The search itself lists launchpad coins since 2026-11-13, by name, symbol and address, so
     * the asks on each chain are a backup for an address it still does not list.
     */
    suspend fun find(api: NestyApi, term: String): SearchRows? = coroutineScope {
        val answer = try {
            api.search(term)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // One row a coin: the list keys its rows by them.
        val listed = answer?.tokens?.distinctBy { it.feed + ":" + it.address.lowercase() }
        val partial = answer?.partial == true
        if (!listed.isNullOrEmpty()) return@coroutineScope SearchRows(listed, partial)
        val said = chainsOf(term).map { c -> async { ask(api, c, term) } }.awaitAll()
        val coins = said.filterIsInstance<Said.Found>().map { it.row }
        when {
            coins.isNotEmpty() -> SearchRows(coins, partial)
            // Not answered, or answered without the listings: nothing found is not "no coin".
            listed == null || partial || said.any { it is Said.Silent } -> null
            else -> SearchRows(emptyList())
        }
    }
}

/** What a search found. `partial`: the listings could not be asked, so some coins may be missing. */
data class SearchRows(val rows: List<ListToken>, val partial: Boolean = false)
