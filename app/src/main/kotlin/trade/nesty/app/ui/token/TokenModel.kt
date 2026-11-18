package trade.nesty.app.ui.token

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import trade.nesty.app.data.ApiException
import trade.nesty.app.data.Candle
import trade.nesty.app.data.ChainRecord
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.HoldersAnswer
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.Share
import trade.nesty.app.data.SignalRow
import trade.nesty.app.data.StreamEvent
import trade.nesty.app.data.Trade
import trade.nesty.app.data.TradersAnswer
import trade.nesty.app.data.TradesAnswer
import trade.nesty.app.ui.signal.SignalHead
import trade.nesty.app.ui.signal.SignalWallets
import kotlin.math.min

enum class Phase { LOADING, READY, EMPTY, FAILED }

/** How far back the Trades list reaches: the page before its oldest trade is read as its end comes into view. */
enum class Older {
    IDLE,
    LOADING,
    /** Pages of a quiet stretch came back empty: the next is read on the person's move. */
    STALLED,
    FAILED,
    /** Its first trade is in the list, or the list is full. */
    DONE,
}

/** One wallet's every trade of the coin, as the server read them for the filtered list. */
data class WalletRead(
    val wallet: String,
    val trades: List<Trade> = emptyList(),
    /** Every trade the wallet made of the coin is among them. */
    val all: Boolean = false,
    val phase: Phase = Phase.LOADING,
)

/** Trades the newest page asks for, and each page before it. */
private const val NEWEST_PAGE = 100
private const val OLDER_PAGE = 100
/** Empty pages in a row before the list waits for the person. */
private const val EMPTY_PAGES = 3
/** Trades the list holds at most: past them the oldest go. */
const val MAX_TRADES = 5_000
/** Each page of the coin's past read without being asked: the most the server sends. */
private const val BOOKED_PAGE = 200
/** Between those pages, as the site's token page waits between its own. */
private const val BOOKED_PAUSE_MS = 300L
/** A page that did not come is asked for once more, this long after. */
private const val PAGE_RETRY_MS = 2_000L
/** While the server's first read of the coin runs, its newest page is asked again this often, so many times. */
private const val READING_WAIT_MS = 3_000L
private const val READING_TRIES = 10

/** Everything the token page and its Trades and holders page read of one coin. */
class TokenModel(private val api: NestyApi, val chain: Chain, val address: String) {
    var coin by mutableStateOf<Coin?>(null)
        private set
    var coinPhase by mutableStateOf(Phase.LOADING)
        private set

    var frame by mutableStateOf(Frame.S15)
    private var raw by mutableStateOf<List<Candle>>(emptyList())
    private var rawFrame by mutableStateOf<Frame?>(null)
    var supply by mutableStateOf<Double?>(null)
        private set
    var chartPhase by mutableStateOf(Phase.LOADING)
        private set
    /**
     * The most it traded at, in dollars per token, once a chart answer said its whole life is
     * known; null until then. Kept across lengths while the page is open, raised by new candles.
     */
    var ath by mutableStateOf<Double?>(null)
        private set
    /** The oldest candle read on the page (Unix seconds): the coin is at least this old. */
    var firstCandleAt by mutableStateOf<Long?>(null)
        private set

    /** Newest first. */
    val trades = mutableStateListOf<Trade>()
    private val tradeIds = HashSet<String>()
    var tradesPhase by mutableStateOf(Phase.LOADING)
        private set
    /** The server's first read of the coin still runs: no trades yet means none read yet. */
    var tradesReading by mutableStateOf(false)
        private set
    var older by mutableStateOf(Older.IDLE)
        private set
    /** Counts the older pages that added trades, so a list whose end stays in view asks again. */
    var olderPages by mutableIntStateOf(0)
        private set
    /** The coin's past is being read on its own, between its pages too. */
    var readingPast by mutableStateOf(false)
        private set
    /** Where the page before the list starts, as the server told it. */
    private var olderCursor: String? = null
    /** An older page was added: a newest page read again then leaves the cursor as it is. */
    private var paged = false
    /** Counts the times the list started again, so an older page asked for before is dropped. */
    private var listEpoch = 0
    /**
     * Whether the last page before the newest came from Nesty's book: then the coin's past is read
     * on its own from the moment it opens. Null until a page said.
     */
    private var olderBooked: Boolean? = null

    /** The wallet the list is filtered to, with its trades read whole; null while it is not. */
    var walletRead by mutableStateOf<WalletRead?>(null)
        private set

    var live by mutableStateOf(false)
        private set

    var share by mutableStateOf<Share?>(null)
        private set
    var holders by mutableStateOf<HoldersAnswer?>(null)
        private set
    /** False where the holder book does not follow the coin. */
    var holdersServed by mutableStateOf<Boolean?>(null)
        private set
    var traders by mutableStateOf<TradersAnswer?>(null)
        private set
    var tradersPhase by mutableStateOf(Phase.LOADING)
        private set

    /** The coin's largest signal in the chain's list. */
    var signal by mutableStateOf<SignalRow?>(null)
        private set

    /** The coin's signal as the page shows it: the trader answer's own where the server sends one. */
    val signalHead: SignalHead? get() = SignalWallets.coinHead(traders, signal)

    var record by mutableStateOf<ChainRecord?>(null)
        private set
    var recordDays by mutableStateOf(0)
        private set
    private var recordAsked = false

    /** The candles of the picked length, summed where the server has none of it. */
    fun candles(): List<Candle> {
        val f = frame
        if (rawFrame?.interval != f.interval) return emptyList()
        return if (f.seconds > 1 && f.interval == "1s") CandleMath.aggregate(raw, f.seconds) else raw
    }

    val holderCount: Int? get() = share?.holders ?: holders?.holders

    /** Reads the coin, its chart and its trades live, its past, and the rest every 30 seconds, while shown. */
    suspend fun live(withChart: Boolean) = coroutineScope {
        launch { firstCoin() }
        launch { sides() }
        launch { readPast() }
        if (withChart) {
            launch {
                snapshotFlow { frame }.collectLatest { f ->
                    coroutineScope {
                        launch { loadCandles(f) }
                        stream(f.interval, f)
                    }
                }
            }
        } else {
            launch { stream("1m", null) }
        }
    }

    private suspend fun firstCoin() {
        var wait = 3_000L
        while (coin == null) {
            try {
                coin = api.token(chain, address)
                coinPhase = Phase.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.notFound) {
                    coinPhase = Phase.EMPTY
                    return
                }
                coinPhase = if (coin == null) Phase.FAILED else Phase.READY
            } catch (e: Exception) {
                coinPhase = if (coin == null) Phase.FAILED else Phase.READY
            }
            if (coin == null) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(30_000)
            }
        }
    }

    private suspend fun loadCandles(f: Frame) {
        if (rawFrame?.interval != f.interval) chartPhase = Phase.LOADING
        var wait = 5_000L
        while (true) {
            try {
                val a = api.candles(chain, address, f.interval, f.limit)
                raw = a.parsed()
                rawFrame = f
                a.supply?.let { supply = it }
                ath = Ath.raise(ath, a.ath, raw)
                raw.firstOrNull()?.time?.let { t -> firstCandleAt = firstCandleAt?.let { min(it, t) } ?: t }
                chartPhase = if (raw.isEmpty()) Phase.EMPTY else Phase.READY
                // A partial chart fills in a little later; an empty one can be a coin the
                // server only starts to follow on this first ask, so it is asked again too.
                if (!a.partial && raw.isNotEmpty()) return
                delay(if (raw.isEmpty()) wait else 20_000)
                if (raw.isEmpty()) wait = (wait * 2).coerceAtMost(60_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (raw.isEmpty() || rawFrame?.interval != f.interval) chartPhase = Phase.FAILED
                delay(wait)
                wait = (wait * 2).coerceAtMost(60_000)
            }
        }
    }

    /** The live stream; while it is down the coin and its trades are read every few seconds instead. */
    private suspend fun stream(interval: String, f: Frame?) = coroutineScope {
        var wait = 3_000L
        while (true) {
            try {
                api.stream(chain, address, interval).collect { e ->
                    when (e) {
                        StreamEvent.Open -> {
                            live = true
                            wait = 3_000L
                            launch { loadTrades() }
                        }
                        is StreamEvent.CoinUpdate -> {
                            coin = e.coin
                            coinPhase = Phase.READY
                        }
                        is StreamEvent.NewTrade -> addTrade(e.trade)
                        is StreamEvent.NewCandle -> if (f != null && e.candle.interval == interval && rawFrame?.interval == interval) {
                            val c = e.candle.toCandle()
                            raw = CandleMath.upsert(raw, c, f.limit)
                            ath = Ath.raise(ath, null, listOf(c))
                            if (chartPhase != Phase.READY) chartPhase = Phase.READY
                        }
                        is StreamEvent.MarketUpdate -> coin = coin?.let { c ->
                            c.copy(priceUsd = e.market.priceUsd ?: c.priceUsd, mcapUsd = e.market.mcapUsd ?: c.mcapUsd)
                        }
                    }
                }
            } catch (e: CancellationException) {
                live = false
                throw e
            } catch (_: Exception) {
            }
            live = false
            runCatching { api.token(chain, address) }.getOrNull()?.let {
                coin = it
                coinPhase = Phase.READY
            }
            loadTrades()
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000)
        }
    }

    /** The newest page, at each (re)opened stream and while it is down: internal for its tests. */
    internal suspend fun loadTrades() {
        try {
            takeNewest(api.trades(chain, address, NEWEST_PAGE))
            tradesPhase = if (trades.isEmpty()) Phase.EMPTY else Phase.READY
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (trades.isEmpty()) tradesPhase = if (e.notFound) Phase.EMPTY else Phase.FAILED
        } catch (e: Exception) {
            if (trades.isEmpty()) tradesPhase = Phase.FAILED
        }
    }

    /** The newest page: its trades, and where the page before it starts while no older page is in the list. */
    private fun takeNewest(a: TradesAnswer) {
        // More than a page traded while the stream was down: what lies between was not read, so the
        // list starts again from this page.
        if (trades.isNotEmpty() && a.trades.size >= NEWEST_PAGE && a.trades.none { it.id in tradeIds }) {
            trades.clear()
            tradeIds.clear()
            paged = false
            listEpoch++
        }
        a.trades.forEach { addTrade(it) }
        tradesReading = a.reading
        if (!paged && !a.reading) {
            olderCursor = a.older
            if (older != Older.LOADING) older = if (a.older == null) Older.DONE else Older.IDLE
        }
    }

    /**
     * Reads the coin's past without being asked, from the moment it opens, while its pages come
     * from Nesty's book: a page every BOOKED_PAUSE_MS, back to its first trade or MAX_TRADES, so
     * the followed wallets' trades and the Trades list hold all of it. A page read from the chain
     * stops it: the rest come as the list's end comes into view, as the server's RPCs are shared
     * by every phone. Not on a chain without the book. Internal for its tests.
     */
    internal suspend fun readPast() {
        if (!chain.book) return
        // The newest page first: it tells where the one before it starts.
        snapshotFlow { tradesPhase }.first { it != Phase.LOADING }
        var reading = 0
        try {
            while (older != Older.DONE && older != Older.STALLED && olderBooked != false) {
                if (olderCursor == null && reading++ >= READING_TRIES) return
                // No cursor yet: the server's first read of the coin still runs.
                delay(if (olderCursor == null) READING_WAIT_MS else BOOKED_PAUSE_MS)
                // Its pages come from the book: the list's end says so between them too.
                readingPast = olderBooked == true
                loadOlder(BOOKED_PAGE)
                if (older == Older.FAILED) {
                    // Asked once more; past that, the list's end asks again as it comes into view.
                    delay(PAGE_RETRY_MS)
                    loadOlder(BOOKED_PAGE)
                    if (older == Older.FAILED) return
                }
            }
        } finally {
            readingPast = false
        }
    }

    /**
     * Adds the page before the list's oldest trade, [size] trades at most. A quiet stretch of the
     * chain can answer empty pages: past EMPTY_PAGES of them in a row the list waits (STALLED) for
     * the person to scroll back to its end or ask.
     */
    suspend fun loadOlder(size: Int = OLDER_PAGE) {
        if (older == Older.LOADING || older == Older.DONE) return
        older = Older.LOADING
        try {
            if (olderCursor == null) {
                // The newest page tells where the one before it starts, once the coin's first read is done.
                val a = api.trades(chain, address, NEWEST_PAGE)
                takeNewest(a)
                if (olderCursor == null) {
                    older = if (a.reading) Older.IDLE else Older.DONE
                    return
                }
            }
            val epoch = listEpoch
            repeat(EMPTY_PAGES) {
                val cursor = olderCursor ?: return
                val a = api.trades(chain, address, size, before = cursor)
                // The list started again meanwhile, or filled up.
                if (epoch != listEpoch || older != Older.LOADING) return
                olderBooked = a.booked
                val added = addOlder(a.trades)
                paged = true
                val full = trades.size >= MAX_TRADES
                olderCursor = if (full) null else a.older
                if (added > 0) olderPages++
                if (olderCursor == null) {
                    older = Older.DONE
                    return
                }
                if (added > 0) {
                    older = Older.IDLE
                    return
                }
            }
            older = Older.STALLED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (older == Older.LOADING) older = Older.FAILED
        } finally {
            if (older == Older.LOADING) older = Older.IDLE
        }
    }

    private fun addTrade(t: Trade) {
        if (t.id.isEmpty() || !tradeIds.add(t.id)) return
        val i = trades.indexOfFirst { it.at < t.at }
        if (i < 0) trades.add(t) else trades.add(i, t)
        if (trades.size > MAX_TRADES) {
            // The oldest go, and with them the way back past them.
            while (trades.size > MAX_TRADES) tradeIds.remove(trades.removeAt(trades.lastIndex).id)
            olderCursor = null
            paged = true
            listEpoch++
            older = Older.DONE
        }
        if (tradesPhase != Phase.READY) tradesPhase = Phase.READY
    }

    /** An older page's trades below the list's, each once, up to MAX_TRADES; how many were new. */
    private fun addOlder(page: List<Trade>): Int {
        var added = 0
        for (t in page) {
            if (trades.size >= MAX_TRADES) break
            if (t.id.isEmpty() || !tradeIds.add(t.id)) continue
            val last = trades.lastOrNull()
            if (last == null || last.at >= t.at) {
                trades.add(t)
            } else {
                val i = trades.indexOfFirst { it.at < t.at }
                if (i < 0) trades.add(t) else trades.add(i, t)
            }
            added++
        }
        if (added > 0 && tradesPhase != Phase.READY) tradesPhase = Phase.READY
        return added
    }

    /** Reads every trade [wallet] made of the coin for the filtered list, asking again until it answers. */
    suspend fun readWallet(wallet: String) {
        walletRead = WalletRead(wallet)
        var wait = 5_000L
        while (true) {
            try {
                val a = api.walletTrades(chain, address, wallet)
                walletRead = WalletRead(wallet, a.trades, a.all, Phase.READY)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                walletRead = WalletRead(wallet, phase = Phase.FAILED)
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000)
        }
    }

    fun clearWallet() {
        walletRead = null
    }

    /** The list filtered to [wallet]: its trades read whole and those heard since, each once, newest first. */
    fun walletTrades(wallet: String): List<Trade> {
        val mine = { t: Trade -> t.wallet.equals(wallet, ignoreCase = true) }
        // Its own only: a server from before the wallet read answers the coin's newest trades instead.
        val read = walletRead?.takeIf { it.wallet == wallet }?.trades.orEmpty().filter(mine)
        return (trades.filter(mine) + read).distinctBy { it.id }.sortedByDescending { it.at }
    }

    /** Holder shares, the holder list, the trader tables and the chain's signals: every 30 seconds. */
    private suspend fun sides() = coroutineScope {
        while (true) {
            if (chain.book) {
                launch { runCatching { api.shares(listOf(chain to address)).shares.firstOrNull() }.getOrNull()?.let { share = it } }
                launch {
                    try {
                        holders = api.holders(chain, address, 100)
                        holdersServed = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.notFound) holdersServed = false
                    } catch (_: Exception) {
                    }
                }
                launch {
                    runCatching { api.signals(chain).signals.list }.getOrNull()?.let { list ->
                        signal = list.filter { it.token.equals(address, ignoreCase = true) }.maxByOrNull { it.size }
                    }
                    askRecord()
                }
            } else {
                holdersServed = false
            }
            launch {
                try {
                    // 100 rows a list: the signal wallets sheet looks its wallets up in them
                    // where the answer has no `signal` of its own.
                    traders = api.traders(chain, address, 100)
                    tradersPhase = Phase.READY
                    askRecord()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (traders == null) tradersPhase = if (e.notFound) Phase.EMPTY else Phase.FAILED
                } catch (_: Exception) {
                    if (traders == null) tradersPhase = Phase.FAILED
                }
            }
            delay(30_000)
        }
    }

    /** How signals like this one went, asked once, and only for a coin that has a signal. */
    private suspend fun askRecord() {
        if (signalHead == null || recordAsked) return
        recordAsked = true
        runCatching { api.signalRecord().record }.getOrNull()?.let { r ->
            recordDays = r.days
            record = r.chains.firstOrNull { it.chain.toString() == chain.feed }
        }
    }
}
