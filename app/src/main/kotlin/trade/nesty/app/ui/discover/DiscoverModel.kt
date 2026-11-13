package trade.nesty.app.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Feed
import trade.nesty.app.data.FeedRow
import trade.nesty.app.data.ListToken
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.Settings
import trade.nesty.app.data.Share
import trade.nesty.app.ui.Checks
import trade.nesty.app.ui.Flag
import trade.nesty.app.ui.venueName

enum class Lane(val label: String) {
    NEW("New"),
    NEAR("Final stretch"),
    GRAD("Graduated"),
    TREND("Trending"),
}

/** A coin as a Discover list shows it. */
data class DiscoverRow(
    val chain: Chain,
    val address: String,
    val symbol: String,
    val image: String?,
    /** When its age counts from. */
    val since: Long?,
    val mcap: Double?,
    val volume: Double?,
    /** Percent over the last hour, where the list has it. */
    val change: Double?,
    /** 0 to 1 while it is on its curve. */
    val curve: Double?,
    val holders: Int?,
    val flag: Flag?,
)

fun shareKey(chain: Chain, address: String) = "${chain.feed}:${address.lowercase()}"

class DiscoverModel(private val api: NestyApi, @Suppress("unused") private val settings: Settings) {
    var lane by mutableStateOf(Lane.NEW)
    var chain by mutableStateOf<Chain?>(null)

    private val feeds = mutableStateMapOf<Chain, Feed>()
    private val trending = mutableStateMapOf<Chain, List<ListToken>>()
    private val shares = mutableStateMapOf<String, Share>()
    private val sharesAsked = HashMap<String, Long>()

    /** Chains whose last read failed. */
    var failed by mutableStateOf<Set<Chain>>(emptySet())
        private set
    var loaded by mutableStateOf<Set<Pair<Lane, Chain>>>(emptySet())
        private set

    private fun chainsNow(c: Chain?) = c?.let { listOf(it) } ?: Chain.entries

    /** Reads the picked list every few seconds, and its coins' holder shares, while shown. */
    suspend fun live() {
        snapshotFlow { lane to chain }.collectLatest { (l, c) ->
            while (true) {
                refresh(l, chainsNow(c))
                refreshShares()
                delay(if (l == Lane.TREND) 30_000 else 5_000)
            }
        }
    }

    private suspend fun refresh(l: Lane, chains: List<Chain>) = coroutineScope {
        val results = chains.map { c ->
            async {
                c to runCatching {
                    if (l == Lane.TREND) trending[c] = api.trending(c).tokens
                    else feeds[c] = api.feed(c)
                }.isSuccess
            }
        }.awaitAll()
        failed = results.filter { !it.second }.map { it.first }.toSet()
        loaded = loaded + results.filter { it.second }.map { l to it.first }
    }

    /** Shares of the shown coins: those never asked, and all of them every 30 seconds. Never on Solana. */
    private suspend fun refreshShares() {
        val now = System.currentTimeMillis()
        val want = rows().filter { it.chain.book }
            .map { it.chain to it.address }
            .filter { (c, a) -> (sharesAsked[shareKey(c, a)] ?: 0L) < now - 30_000 }
            .take(120)
        if (want.isEmpty()) return
        for ((c, a) in want) sharesAsked[shareKey(c, a)] = now
        coroutineScope {
            want.chunked(40).map { part ->
                async {
                    runCatching { api.shares(part).shares }.getOrNull()?.forEach { s ->
                        Chain.of(s.feed)?.let { shares[shareKey(it, s.address)] = s }
                    }
                }
            }.awaitAll()
        }
    }

    fun isLoaded(): Boolean = chainsNow(chain).any { (lane to it) in loaded }

    /** The picked list, merged across chains when All is picked. */
    fun rows(): List<DiscoverRow> {
        val l = lane
        val chains = chainsNow(chain)
        val out = when (l) {
            Lane.TREND -> chains.flatMap { c -> trending[c].orEmpty().map { fromList(c, it) } }
                .sortedByDescending { it.volume ?: 0.0 }
            else -> chains.flatMap { c ->
                val f = feeds[c] ?: return@flatMap emptyList()
                when (l) {
                    Lane.NEW -> f.fresh
                    Lane.NEAR -> f.near
                    else -> f.graduated
                }.map { fromFeed(c, it, l) }
            }.let { list ->
                when (l) {
                    Lane.NEW -> list.sortedByDescending { it.since ?: 0L }
                    Lane.NEAR -> list.sortedByDescending { it.curve ?: 0.0 }
                    else -> list.sortedByDescending { it.since ?: 0L }
                }
            }
        }
        return out.distinctBy { shareKey(it.chain, it.address) }.take(100)
    }

    private fun fromFeed(c: Chain, r: FeedRow, l: Lane): DiscoverRow {
        val share = shares[shareKey(c, r.address)]
        return DiscoverRow(
            chain = c,
            address = r.address,
            symbol = r.symbol?.takeIf { it.isNotBlank() } ?: r.name ?: "?",
            image = r.image,
            since = if (l == Lane.GRAD) r.graduatedAt ?: r.createdAt else r.createdAt,
            mcap = r.mcapUsd,
            volume = r.volume24,
            change = null,
            curve = if (!r.complete && l != Lane.GRAD) r.progress else null,
            holders = share?.holders,
            flag = Checks.flag(r.signal?.size, share, r.tax, r.venue),
        )
    }

    private fun fromList(c: Chain, t: ListToken): DiscoverRow {
        val share = shares[shareKey(c, t.address)]
        return DiscoverRow(
            chain = c,
            address = t.address,
            symbol = t.symbol?.takeIf { it.isNotBlank() } ?: t.name ?: "?",
            image = t.image,
            since = t.createdAt,
            mcap = t.mcapUsd,
            volume = t.volume24,
            change = t.change?.h1,
            curve = null,
            holders = share?.holders,
            flag = Checks.flag(null, share, null, venueName(t.dex)),
        )
    }
}
