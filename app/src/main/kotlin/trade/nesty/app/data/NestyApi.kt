package trade.nesty.app.data

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The server said no, or answered off its shape. */
class ApiException(
    val status: Int,
    /** The server's `error`: `bad_request`, `not_found`, `busy`, `unreadable`, `slow`, … */
    val code: String?,
    val retryAfterSeconds: Int? = null,
    message: String = "HTTP $status${code?.let { " ($it)" } ?: ""}",
) : IOException(message) {
    val notFound: Boolean get() = status == 404
    val busy: Boolean get() = status == 429 || status == 503
}

val NestyJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * The read-only server every read goes through (website/api/README.md). It holds no key and
 * signs nothing; the app sends it no key either. Nothing derived from a wallet is logged here.
 */
class NestyApi(
    private val http: OkHttpClient,
    private val streamHttp: OkHttpClient,
    val base: HttpUrl = DEFAULT_BASE.toHttpUrl(),
) {
    suspend fun chains(): JsonObject = get("chains")

    suspend fun feed(chain: Chain, limit: Int = 50): Feed = get("feed/${chain.feed}", "limit" to limit)

    suspend fun trending(chain: Chain, span: String = "1h"): Tokens = get("trending/${chain.feed}", "span" to span)

    suspend fun search(q: String): Tokens = get("search", "q" to q)

    suspend fun token(chain: Chain, address: String): Coin = get("token/${chain.feed}/$address")

    suspend fun candles(chain: Chain, address: String, interval: String, limit: Int = 500): CandlesAnswer =
        get("candles/${chain.feed}/$address", "interval" to interval, "limit" to limit)

    suspend fun trades(chain: Chain, address: String, limit: Int = 100): TradesAnswer =
        get("trades/${chain.feed}/$address", "limit" to limit)

    suspend fun holders(chain: Chain, address: String, limit: Int = 100): HoldersAnswer =
        get("holders/${chain.feed}/$address", "limit" to limit)

    /** At most 100 rows in each list. Only the coin goes to the server: it knows a signal's wallets itself. */
    suspend fun traders(chain: Chain, address: String, limit: Int = 50): TradersAnswer =
        get("traders/${chain.feed}/$address", "limit" to limit)

    /** Up to 40 coins at a time. */
    suspend fun shares(coins: List<Pair<Chain, String>>): SharesAnswer =
        get("shares", "tokens" to coins.joinToString(",") { "${it.first.feed}:${it.second}" })

    /** Up to 30 coins at a time. */
    suspend fun prices(coins: List<Pair<Chain, String>>): PricesAnswer =
        get("prices", "tokens" to coins.joinToString(",") { "${it.first.feed}:${it.second}" })

    suspend fun signals(chain: Chain): SignalsAnswer = get("signals/${chain.feed}")

    suspend fun signalRecord(): RecordAnswer = get("signals/record")

    suspend fun leaderboard(chain: Chain, window: String): LeaderboardAnswer =
        get("leaderboard/${chain.feed}", "window" to window)

    suspend fun stats(window: String): StatsAnswer = get("stats", "window" to window)

    suspend fun updates(limit: Int = 100, tag: String? = null): UpdatesAnswer =
        get("updates", "limit" to limit, "tag" to tag)

    suspend fun wallet(chain: Chain, address: String, window: String): WalletAnswer =
        get("wallet/${chain.feed}/$address", "window" to window)

    /** The newest APK, or null while none is published (a 404). */
    suspend fun app(): AppRelease? = try {
        get<AppRelease>("app")
    } catch (e: ApiException) {
        if (e.notFound) null else throw e
    }

    /** A coin logo on a plain-http host, passed on over https. */
    fun logoUrl(httpUrl: String): String =
        base.newBuilder().addPathSegment("logo").addQueryParameter("url", httpUrl).build().toString()

    fun url(path: String, vararg query: Pair<String, Any?>): HttpUrl {
        val b = base.newBuilder().addPathSegments(path.trimStart('/'))
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v.toString())
        return b.build()
    }

    suspend inline fun <reified T> get(path: String, vararg query: Pair<String, Any?>): T {
        val text = getText(url(path, *query))
        return try {
            // Off the main thread: a feed of three lanes is a few hundred kilobytes.
            withContext(Dispatchers.Default) { NestyJson.decodeFromString<T>(text) }
        } catch (e: SerializationException) {
            throw ApiException(200, "shape", message = "Answer off its shape: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ApiException(200, "shape", message = "Answer off its shape: ${e.message}")
        }
    }

    suspend fun getText(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val response = http.newCall(request).await()
        return response.use { r ->
            val body = withContext(Dispatchers.IO) { r.body.string() }
            if (!r.isSuccessful) throw errorOf(r.code, body, r.header("retry-after"))
            body
        }
    }

    /**
     * A coin's live events. The first item is [StreamEvent.Open] once the server answered, so a
     * caller can read /trades after it and lose no trade between the two. The flow ends when
     * the server lets the stream go; the caller reconnects.
     */
    fun stream(chain: Chain, address: String, interval: String): Flow<StreamEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url("stream/${chain.feed}/$address", "interval" to interval))
            .header("Accept", "text/event-stream")
            .build()
        val call = streamHttp.newCall(request)
        launch(Dispatchers.IO) {
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw errorOf(r.code, r.body.string(), r.header("retry-after"))
                    send(StreamEvent.Open)
                    val parser = SseParser()
                    val source = r.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val event = parser.line(line) ?: continue
                        decodeEvent(event)?.let { send(it) }
                    }
                }
                close()
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    companion object {
        const val DEFAULT_BASE = "https://nesty.trade/api/v1/"

        fun errorOf(status: Int, body: String, retryAfter: String?): ApiException {
            val code = runCatching {
                NestyJson.parseToJsonElement(body).let { it as? JsonObject }?.get("error")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return ApiException(status, code, retryAfter?.trim()?.toIntOrNull())
        }

        fun decodeEvent(e: SseEvent): StreamEvent? = runCatching {
            when (e.event) {
                "coin" -> StreamEvent.CoinUpdate(NestyJson.decodeFromString<Coin>(e.data))
                "trade" -> StreamEvent.NewTrade(NestyJson.decodeFromString<Trade>(e.data))
                "candle" -> StreamEvent.NewCandle(NestyJson.decodeFromString<StreamCandle>(e.data))
                "market" -> StreamEvent.MarketUpdate(NestyJson.decodeFromString<Market>(e.data))
                else -> null
            }
        }.getOrNull()
    }
}

sealed interface StreamEvent {
    data object Open : StreamEvent
    data class CoinUpdate(val coin: Coin) : StreamEvent
    data class NewTrade(val trade: Trade) : StreamEvent
    data class NewCandle(val candle: StreamCandle) : StreamEvent
    data class MarketUpdate(val market: Market) : StreamEvent
}

data class SseEvent(val event: String, val data: String)

/** Server-Sent Events, line by line: `event:` and `data:` fields, a blank line ends an event. */
class SseParser {
    private var event = "message"
    private val data = StringBuilder()
    private var hasData = false

    fun line(line: String): SseEvent? {
        if (line.isEmpty()) {
            val out = if (hasData) SseEvent(event, data.toString()) else null
            event = "message"
            data.setLength(0)
            hasData = false
            return out
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
