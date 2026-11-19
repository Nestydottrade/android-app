package trade.nesty.app.sol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import trade.nesty.app.data.await
import java.io.IOException
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import trade.nesty.app.i18n.tr

class SolAccount(val owner: String, val lamports: BigInteger, val data: ByteArray, val executable: Boolean = false)

/** A JSON-RPC error from the node itself (not the transport). */
class SolRpcError(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

class SignatureStatus(val confirmationStatus: String?, val err: JsonElement?)

class Simulation(val err: JsonElement?, val logs: List<String>, val unitsConsumed: Long?)

class TokenBalance(val accountIndex: Int, val mint: String, val owner: String?, val amount: BigInteger)

/** Balances around a confirmed transaction, for what a trade really paid and received. */
class TxMeta(
    val err: JsonElement?,
    val fee: BigInteger,
    val accountKeys: List<String>,
    val preBalances: List<BigInteger>,
    val postBalances: List<BigInteger>,
    val preTokenBalances: List<TokenBalance>,
    val postTokenBalances: List<TokenBalance>,
)

/** One JSON-RPC read on Solana. Sends never go through here: see SolSender. */
fun interface SolCall {
    suspend fun call(method: String, params: JsonArray): JsonElement
}

/** Sends a signed transaction; resolves with its signature once an RPC accepts it. */
fun interface SolSender {
    suspend fun send(raw: ByteArray): String
}

object SolRpcs {
    /** The public RPCs built into the app, as the extension's SOLANA.rpcUrls. */
    val PUBLIC = listOf("https://solana-rpc.publicnode.com")

    /** Node errors another endpoint may not have: unhealthy node, rate limit. */
    val RETRY_CODES = setOf(-32005, -32007, -32016, 429)

    /** How many keys a node says one getMultipleAccounts may name, from its refusal; else null. */
    fun keysLimit(message: String): Int? =
        Regex("maximum of (\\d+) accounts", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }

    private val ids = AtomicLong(1)

    fun body(method: String, params: JsonArray): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", ids.getAndIncrement())
        put("method", method)
        put("params", params)
    }.toString()

    /** The answer's result, or the node's error as SolRpcError. */
    fun answer(e: JsonElement): JsonElement {
        val o = e.jsonObject
        val err = o["error"]
        if (err != null && err !is JsonNull) {
            val eo = err.jsonObject
            throw SolRpcError(
                eo["code"]?.jsonPrimitive?.intOrNull ?: -1,
                eo["message"]?.jsonPrimitive?.contentOrNull ?: tr("RPC error"),
                eo["data"],
            )
        }
        return o["result"] ?: JsonNull
    }

    /**
     * The node's own refusal behind an HTTP error, when it is one about request size: public
     * endpoints answer "A maximum of 10 accounts is allowed" with a 403.
     */
    fun sizeRefusal(text: String): SolRpcError? = try {
        val err = Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
        val code = err?.get("code")?.jsonPrimitive?.intOrNull
        val message = err?.get("message")?.jsonPrimitive?.contentOrNull
        if (code != null && message != null && keysLimit(message) != null) SolRpcError(code, message) else null
    } catch (_: Exception) {
        null
    }
}

/**
 * Reads go to Nesty's /rpc/solana (the server's own RPCs) and, when it is busy or down, to the
 * public RPCs, in order, as the extension's SolRpc tries its endpoints. An answer from the node
 * (a failed simulation, say) is final.
 */
class HttpSolCall(
    private val http: OkHttpClient,
    base: String = "https://nesty.trade/api/v1/",
    private val urls: List<String> = listOf("${base}rpc/solana") + SolRpcs.PUBLIC,
    private val timeoutMs: Long = RPC_TIMEOUT_MS,
) : SolCall {
    override suspend fun call(method: String, params: JsonArray): JsonElement {
        require(method != "sendTransaction") { "sends go through SolBroadcast" }
        val body = SolRpcs.body(method, params)
        var last: Exception = IOException("no RPC answered")
        for (url in urls) {
            try {
                return postJson(http, url, body, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SolRpcError) {
                if (e.code !in SolRpcs.RETRY_CODES) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }
}

private val JSON_TYPE = "application/json".toMediaType()

/** The extension's limit on one RPC answer. */
internal const val RPC_TIMEOUT_MS = 8_000L

/**
 * One POST, with the extension's 8 s limit; HTTP errors become retryable node errors. A slow
 * endpoint fails like a down one (an IOException, so the next is tried): never as a cancel, which
 * would end the caller's whole coroutine (a sheet's quote loop) without a word.
 */
internal suspend fun postJson(http: OkHttpClient, url: String, body: String, timeoutMs: Long = RPC_TIMEOUT_MS): JsonElement {
    val text = withTimeoutOrNull(timeoutMs) {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON_TYPE)).build()
        val response = http.newCall(request).await()
        response.use { r ->
            val t = withContext(Dispatchers.IO) { r.body.string() }
            if (!r.isSuccessful) throw SolRpcs.sizeRefusal(t) ?: SolRpcError(if (r.code == 429) 429 else -32005, "HTTP ${r.code}")
            t
        }
    } ?: throw IOException(tr("No answer in {1} s", timeoutMs / 1000.0))
    return SolRpcs.answer(Json.parseToJsonElement(text))
}

/**
 * Where a signed transaction goes: every public RPC at once, the first to accept wins, as the
 * extension's broadcast. Never through Nesty's server: it only ever reads (and refuses sends).
 */
class SolBroadcast(
    private val http: OkHttpClient,
    private val urls: List<String> = SolRpcs.PUBLIC,
    /** To block engines (Jito.ENGINES), which take the encoding alone and skip the preflight themselves. */
    private val engines: Boolean = false,
) : SolSender {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun send(raw: ByteArray): String {
        val params = buildJsonArray {
            add(Base64.getEncoder().encodeToString(raw))
            add(buildJsonObject {
                put("encoding", "base64")
                if (!engines) {
                    put("skipPreflight", true)
                    put("maxRetries", 0)
                }
            })
        }
        val body = SolRpcs.body("sendTransaction", params)
        val winner = CompletableDeferred<String>()
        val errors = arrayOfNulls<Exception>(urls.size)
        val jobs = urls.mapIndexed { i, url ->
            background.launch {
                try {
                    val sig = postJson(http, url, body).jsonPrimitive.contentOrNull ?: throw IOException("no signature")
                    winner.complete(sig)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[i] = e
                }
            }
        }
        background.launch {
            jobs.joinAll()
            winner.completeExceptionally(errors.firstOrNull { it != null } ?: IOException(tr("No RPC accepted it")))
        }
        return winner.await()
    }
}

/**
 * The typed reads a trade makes, over any SolCall (Nesty's /rpc, or a test's record). `sender`
 * sends to the public RPCs, `privateSender` to the block engines, for a trade with a tip.
 */
class SolRpc(private val reads: SolCall, private val sender: SolSender? = null, private val privateSender: SolSender? = null) {
    /**
     * Keys per getMultipleAccounts, fewer once an endpoint refuses more. The spec allows 100, but
     * the public node behind Nesty's /rpc takes 10, and the proxy answers a bigger read with a
     * plain 502 that names no limit, so reads start at 10.
     *
     * Private RPC: 100 per call (docs/private-rpc.md, "getMultipleAccounts").
     */
    @Volatile
    private var keysPerCall = 10

    suspend fun call(method: String, params: JsonArray): JsonElement = reads.call(method, params)

    suspend fun getMultipleAccounts(keys: List<String>): List<SolAccount?> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<SolAccount?>(keys.size)
        var i = 0
        while (i < keys.size) {
            val size = keysPerCall
            try {
                val part = keys.subList(i, minOf(keys.size, i + size))
                val r = call("getMultipleAccounts", buildJsonArray {
                    add(buildJsonArray { part.forEach { add(it) } })
                    add(buildJsonObject {
                        put("encoding", "base64")
                        put("commitment", "confirmed")
                    })
                })
                val value = r.jsonObject["value"]?.jsonArray ?: throw IOException("bad answer")
                if (value.size != part.size) throw IOException("bad answer")
                value.forEach { out.add(parseAccount(it)) }
                i += size
            } catch (e: SolRpcError) {
                // An endpoint that takes fewer keys says how many: kept for later reads, and this
                // part is read again in smaller pieces.
                val limit = SolRpcs.keysLimit(e.message ?: "")
                if (limit == null || limit >= size) throw e
                keysPerCall = limit
            }
        }
        return out
    }

    suspend fun getAccount(key: String): SolAccount? = getMultipleAccounts(listOf(key)).firstOrNull()

    /**
     * The least an account of `size` bytes may hold other than nothing: its rent exemption. Solana
     * lowers it now and then (650,240 lamports for a wallet in October 2026, down from 890,880).
     */
    suspend fun minimumBalance(size: Int): BigInteger {
        val r = call("getMinimumBalanceForRentExemption", buildJsonArray {
            add(size)
            add(buildJsonObject { put("commitment", "confirmed") })
        })
        return r.jsonPrimitive.contentOrNull?.toBigIntegerOrNull()?.takeIf { it.signum() > 0 } ?: throw IOException("bad answer")
    }

    suspend fun getBalance(key: String): BigInteger {
        val r = call("getBalance", buildJsonArray {
            add(key)
            add(buildJsonObject { put("commitment", "confirmed") })
        })
        return r.jsonObject["value"]?.jsonPrimitive?.contentOrNull?.toBigIntegerOrNull() ?: throw IOException("bad answer")
    }

    class Blockhash(val blockhash: String, val lastValidBlockHeight: Long)

    suspend fun getLatestBlockhash(): Blockhash {
        val r = call("getLatestBlockhash", buildJsonArray { add(buildJsonObject { put("commitment", "confirmed") }) })
        val v = r.jsonObject["value"]?.jsonObject ?: throw IOException("bad answer")
        return Blockhash(
            v["blockhash"]?.jsonPrimitive?.contentOrNull ?: throw IOException("bad answer"),
            v["lastValidBlockHeight"]?.jsonPrimitive?.longOrNull ?: throw IOException("bad answer"),
        )
    }

    suspend fun getBlockHeight(): Long =
        call("getBlockHeight", buildJsonArray { add(buildJsonObject { put("commitment", "confirmed") }) }).jsonPrimitive.longOrNull
            ?: throw IOException("bad answer")

    /** Runs an unsigned transaction against the current state (signatures not checked). */
    suspend fun simulate(raw: ByteArray): Simulation {
        val r = call("simulateTransaction", buildJsonArray {
            add(Base64.getEncoder().encodeToString(raw))
            add(buildJsonObject {
                put("encoding", "base64")
                put("sigVerify", false)
                put("replaceRecentBlockhash", true)
                put("commitment", "confirmed")
            })
        })
        val v = r.jsonObject["value"]?.jsonObject ?: throw IOException("bad answer")
        val err = v["err"]?.takeIf { it !is JsonNull }
        val logs = (v["logs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return Simulation(err, logs, (v["unitsConsumed"] as? JsonPrimitive)?.longOrNull)
    }

    suspend fun getSignatureStatuses(sigs: List<String>): List<SignatureStatus?> {
        val r = call("getSignatureStatuses", buildJsonArray {
            add(buildJsonArray { sigs.forEach { add(it) } })
            add(buildJsonObject { put("searchTransactionHistory", false) })
        })
        val value = r.jsonObject["value"]?.jsonArray ?: throw IOException("bad answer")
        return value.map { s ->
            val o = s as? JsonObject ?: return@map null
            SignatureStatus(
                (o["confirmationStatus"] as? JsonPrimitive)?.contentOrNull,
                o["err"]?.takeIf { it !is JsonNull },
            )
        }
    }

    /** A landed transaction's balances. Ours are legacy; others may be v0 or v1. */
    suspend fun getTransactionMeta(sig: String, maxVersion: Int = 0): TxMeta? {
        val r = call("getTransaction", buildJsonArray {
            add(sig)
            add(buildJsonObject {
                put("encoding", "json")
                put("maxSupportedTransactionVersion", maxVersion)
                put("commitment", "confirmed")
            })
        })
        return parseTxMeta(r)
    }

    /** Sends to the public RPCs (never through Nesty's server). */
    /** A private send never falls back to the public RPCs: without a block engine it fails. */
    suspend fun broadcast(raw: ByteArray, privately: Boolean = false): String =
        if (privately) (privateSender ?: throw IOException(tr("No private route"))).send(raw) else (sender ?: throw IOException(tr("No sender"))).send(raw)

    companion object {
        fun parseAccount(e: JsonElement): SolAccount? {
            val o = e as? JsonObject ?: return null
            val data = (o["data"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: return null
            return SolAccount(
                owner = o["owner"]?.jsonPrimitive?.contentOrNull ?: return null,
                lamports = o["lamports"]?.jsonPrimitive?.contentOrNull?.toBigIntegerOrNull() ?: BigInteger.ZERO,
                data = Base64.getDecoder().decode(data),
                executable = o["executable"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }

        fun parseTxMeta(r: JsonElement): TxMeta? {
            val o = r as? JsonObject ?: return null
            val m = o["meta"] as? JsonObject ?: return null
            fun big(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toBigIntegerOrNull() ?: BigInteger.ZERO
            fun tokens(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { t ->
                val b = t as? JsonObject ?: return@mapNotNull null
                TokenBalance(
                    accountIndex = b["accountIndex"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    mint = b["mint"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    owner = (b["owner"] as? JsonPrimitive)?.contentOrNull,
                    amount = big((b["uiTokenAmount"] as? JsonObject)?.get("amount")),
                )
            } ?: emptyList()
            val keys = ((o["transaction"] as? JsonObject)?.get("message") as? JsonObject)?.get("accountKeys") as? JsonArray
            val loaded = m["loadedAddresses"] as? JsonObject
            fun strings(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            return TxMeta(
                err = m["err"]?.takeIf { it !is JsonNull },
                fee = big(m["fee"]),
                accountKeys = strings(keys) + strings(loaded?.get("writable")) + strings(loaded?.get("readonly")),
                preBalances = (m["preBalances"] as? JsonArray)?.map { big(it) } ?: emptyList(),
                postBalances = (m["postBalances"] as? JsonArray)?.map { big(it) } ?: emptyList(),
                preTokenBalances = tokens(m["preTokenBalances"]),
                postTokenBalances = tokens(m["postTokenBalances"]),
            )
        }
    }
}
