package trade.nesty.app.evm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import trade.nesty.app.data.await
import java.io.IOException

/** Sends a signed transaction. Separate from reads so tests can stand in for it. */
fun interface TxSender {
    suspend fun send(chain: EvmChain, raw: ByteArray): String
}

/**
 * Where a signed transaction goes, as the extension sends: Ethereum and BNB Chain to their
 * MEV-protected RPC alone (a public mempool would show the trade to sandwich bots); the
 * sequencer chains (Base, Robinhood, Elysium) to every public RPC at once, the first to accept
 * wins. Never through Nesty's server: it only ever reads.
 */
class Broadcast(private val http: OkHttpClient) : TxSender {
    private val json = "application/json".toMediaType()

    /** Late sends keep going after the first accepts: the same bytes, the same hash. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun send(chain: EvmChain, raw: ByteArray): String {
        val urls = chain.mevRpc?.let { listOf(it) } ?: chain.rpcUrls.distinct()
        if (urls.isEmpty()) throw IOException("No RPC to send to")
        if (urls.size == 1) return post(urls[0], raw)
        val winner = CompletableDeferred<String>()
        val errors = arrayOfNulls<Exception>(urls.size)
        val jobs = urls.mapIndexed { i, url ->
            background.launch {
                try {
                    winner.complete(post(url, raw))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[i] = e
                }
            }
        }
        background.launch {
            jobs.joinAll()
            // Every endpoint refused: report the first one's reason.
            if (!winner.isCompleted) winner.completeExceptionally(errors.firstOrNull { it != null } ?: IOException("No RPC accepted it"))
        }
        return winner.await()
    }

    private suspend fun post(url: String, raw: ByteArray): String {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_sendRawTransaction")
            put("params", buildJsonArray { add(JsonPrimitive(raw.toHex())) })
        }.toString()
        val response = http.newCall(Request.Builder().url(url).post(body.toRequestBody(json)).build()).await()
        val text = response.use { r ->
            val t = withContext(Dispatchers.IO) { r.body.string() }
            if (!r.isSuccessful && t.isBlank()) throw IOException("HTTP ${r.code}")
            t
        }
        return HttpRpc.answer(Json.parseToJsonElement(text)).jsonPrimitive.content
    }
}

/**
 * Local nonce tracking per (chain, wallet), ported from nonce.ts, so taps in a row get
 * consecutive nonces instead of all reading the same "pending" count.
 */
class NonceManager(
    private val fetchPending: suspend (EvmChain, String) -> Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class State(val next: Long, val syncedAt: Long)

    private val guard = Mutex()
    private val state = HashMap<String, State>()
    private val locks = HashMap<String, Mutex>()

    private fun key(chain: EvmChain, address: String) = "${chain.id}:${address.lowercase()}"

    private suspend fun lockOf(key: String) = guard.withLock { locks.getOrPut(key) { Mutex() } }

    private suspend fun reserve(chain: EvmChain, address: String): Long {
        val key = key(chain, address)
        val s = guard.withLock { state[key] }
        val stale = s == null || now() - s.syncedAt > RESYNC_MS
        var next = s?.next ?: 0
        if (stale) next = maxOf(next, fetchPending(chain, address))
        guard.withLock { state[key] = State(next + 1, if (stale) now() else s.syncedAt) }
        return next
    }

    private suspend fun forget(key: String) {
        guard.withLock { state.remove(key) }
    }

    /**
     * The lock covers reserving, signing and *starting* the send, not the RPC's reply: each
     * transaction leaves in nonce order without waiting for the one before it to be accepted.
     * A failed send forgets the local count, so the next one reads it again.
     */
    suspend fun <S, R> signAndSend(chain: EvmChain, address: String, sign: suspend (Long) -> S, send: suspend (S) -> R): Pair<Long, R> {
        val key = key(chain, address)
        val (nonce, pending) = lockOf(key).withLock {
            val nonce = reserve(chain, address)
            try {
                val signed = sign(nonce)
                nonce to CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.UNDISPATCHED) { send(signed) }
            } catch (e: Exception) {
                forget(key)
                throw e
            }
        }
        return try {
            nonce to pending.await()
        } catch (e: Exception) {
            forget(key)
            throw e
        }
    }

    suspend fun reset(chain: EvmChain, address: String) = forget(key(chain, address))

    companion object {
        const val RESYNC_MS = 30_000L
    }
}
