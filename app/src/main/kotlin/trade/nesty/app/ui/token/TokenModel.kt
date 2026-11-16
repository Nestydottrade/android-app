package trade.nesty.app.ui.token

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import trade.nesty.app.ui.signal.SignalHead
import trade.nesty.app.ui.signal.SignalWallets
import kotlin.math.min

enum class Phase { LOADING, READY, EMPTY, FAILED }

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

    /** Reads the coin, its chart and its trades live, and the rest every 30 seconds, while shown. */
    suspend fun live(withChart: Boolean) = coroutineScope {
        launch { firstCoin() }
        launch { sides() }
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

    private suspend fun loadTrades() {
        try {
            val a = api.trades(chain, address, 100)
            a.trades.forEach { addTrade(it) }
            tradesReading = a.reading
            tradesPhase = if (trades.isEmpty()) Phase.EMPTY else Phase.READY
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (trades.isEmpty()) tradesPhase = if (e.notFound) Phase.EMPTY else Phase.FAILED
        } catch (e: Exception) {
            if (trades.isEmpty()) tradesPhase = Phase.FAILED
        }
    }

    private fun addTrade(t: Trade) {
        if (t.id.isEmpty() || !tradeIds.add(t.id)) return
        val i = trades.indexOfFirst { it.at < t.at }
        if (i < 0) trades.add(t) else trades.add(i, t)
        while (trades.size > 300) tradeIds.remove(trades.removeAt(trades.lastIndex).id)
        if (tradesPhase != Phase.READY) tradesPhase = Phase.READY
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
