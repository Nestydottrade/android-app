package trade.nesty.app.ui.smart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import trade.nesty.app.data.Board
import trade.nesty.app.data.BoardRow
import trade.nesty.app.data.Chain
import trade.nesty.app.data.ChainSignals
import trade.nesty.app.data.CoinImages
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.Share
import trade.nesty.app.data.SignalFigures
import trade.nesty.app.data.SignalRecord
import trade.nesty.app.data.SignalRow
import trade.nesty.app.ui.discover.shareKey

/** The chains the book reads, so the ones with signals and a Leaderboard. Solana has neither yet. */
val SMART_CHAINS: List<Chain> = Chain.booked

data class ChainSignal(val chain: Chain, val row: SignalRow)

/** The record of one rule for one scope: the signals and the yardstick beside them. */
data class RecordView(val signals: SignalFigures, val any: SignalFigures, val days: Int)

class SignalsModel(private val api: NestyApi) {
    /** Proven wallets needed: 2, 3 or 5. */
    var need by mutableIntStateOf(3)
    var chain by mutableStateOf<Chain?>(null)

    private val lists = mutableStateMapOf<Chain, ChainSignals>()
    var record by mutableStateOf<SignalRecord?>(null)
        private set
    private val shares = mutableStateMapOf<String, Share>()
    /** The signals come without pictures: each coin's is read as its row is drawn. */
    val images = CoinImages(api)
    var failed by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun live() = coroutineScope {
        launch {
            while (true) {
                val ok = SMART_CHAINS.map { c ->
                    async { runCatching { lists[c] = api.signals(c).signals }.isSuccess }
                }.awaitAll()
                failed = ok.none { it }
                if (ok.any { it }) loaded = true
                refreshShares()
                delay(15_000)
            }
        }
        launch {
            while (true) {
                runCatching { record = api.signalRecord().record }
                delay(if (record == null) 20_000 else 300_000)
            }
        }
    }

    private suspend fun refreshShares() {
        val want = signals().take(40).map { it.chain to it.row.token }
        if (want.isEmpty()) return
        runCatching { api.shares(want).shares }.getOrNull()?.forEach { s ->
            Chain.of(s.feed)?.let { shares[shareKey(it, s.address)] = s }
        }
    }

    fun shareOf(chain: Chain, token: String): Share? = shares[shareKey(chain, token)]

    /** How many proven wallets a chain has; null until read. */
    fun proven(c: Chain): Int? = lists[c]?.proven

    /**
     * The last day's signals of the picked size and chain, newest first. A coin is listed once for
     * each size it reached (2, 3 and 5 wallets), as it was when it got there, so a coin that reached
     * 5 has rows at 2 and 3 too. Only the picked size's row is shown, as on the site.
     */
    fun signals(): List<ChainSignal> {
        val chains = chain?.let { listOf(it) } ?: SMART_CHAINS
        return chains.flatMap { c -> lists[c]?.list.orEmpty().filter { it.size == need }.map { ChainSignal(c, it) } }
            .sortedByDescending { it.row.at }
    }

    fun recordView(): RecordView? {
        val r = record ?: return null
        val c = chain
        return if (c == null) {
            r.all.sizes[need.toString()]?.let { RecordView(it, r.all.any, r.days) }
        } else {
            val cr = r.chains.firstOrNull { it.chain.toString() == c.feed } ?: return null
            cr.sizes[need.toString()]?.let { RecordView(it, cr.any, r.days) }
        }
    }
}

enum class Sort(val key: String, val label: String) {
    PROFIT("profit", "Profit"),
    ROI("roi", "ROI"),
    WIN("winRate", "Win rate"),
    VOLUME("volume", "Volume"),
}

enum class Period(val window: String, val label: String, val words: String) {
    D1("1d", "1D", "last day"),
    D7("7d", "7D", "last 7 days"),
    D30("30d", "30D", "last 30 days"),
}

class LeadersModel(private val api: NestyApi) {
    var chain by mutableStateOf(Chain.BSC)
    var period by mutableStateOf(Period.D7)
    var sort by mutableStateOf(Sort.PROFIT)
    private val boards = mutableStateMapOf<String, Board>()
    var failed by mutableStateOf(false)
        private set

    private fun key(c: Chain, p: Period) = "${c.feed}:${p.window}"

    suspend fun live() {
        snapshotFlow { chain to period }.collectLatest { (c, p) ->
            while (true) {
                failed = false
                runCatching { boards[key(c, p)] = api.leaderboard(c, p.window).board }.onFailure { failed = boards[key(c, p)] == null }
                delay(60_000)
            }
        }
    }

    fun board(): Board? = boards[key(chain, period)]

    /** The picked ranking, best first, at most 100. */
    fun rows(): List<BoardRow> {
        val b = board() ?: return emptyList()
        val order = b.order[sort.key]
        val list = if (order != null) order.mapNotNull { b.rows.getOrNull(it) } else b.rows
        return list.take(100)
    }
}

fun BoardRow.roi(): Double? = if (basis > 0) profit / basis else null
fun BoardRow.winRate(): Double? = if (sold > 0) wins.toDouble() / sold else null
