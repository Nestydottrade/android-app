package trade.nesty.app.evm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import trade.nesty.app.data.await
import java.io.IOException
import java.math.BigInteger

/** The chain answered with a JSON-RPC error (a revert carries its data). */
class RpcError(val code: Int, message: String, val data: String? = null) : Exception(message)

/** One JSON-RPC read or send, on one chain. */
fun interface EvmRpc {
    suspend fun request(chain: EvmChain, method: String, params: JsonArray): JsonElement
}

/**
 * Reads go to Nesty's /rpc (the server's own RPCs, so keys stay off the phone) and, when it
 * is busy or down, to the chain's public RPCs. Sends never go through here: see Broadcast.
 */
class HttpRpc(
    private val http: OkHttpClient,
    private val base: String = "https://nesty.trade/api/v1/",
    /**
     * Only the chain's own public RPCs, never Nesty's /rpc: for reads that name an address the user
     * typed (a withdrawal's receiver), which Nesty's server must not see.
     */
    private val direct: Boolean = false,
) : EvmRpc {
    private val json = "application/json".toMediaType()

    override suspend fun request(chain: EvmChain, method: String, params: JsonArray): JsonElement {
        require(method != "eth_sendRawTransaction") { "sends go through Broadcast" }
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        var last: Exception? = null
        val urls = if (direct) chain.rpcUrls else listOf("${base}rpc/${chain.id}") + chain.rpcUrls
        for (url in urls) {
            try {
                return post(url, body)
            } catch (e: RpcError) {
                // The chain itself said no (a revert, a bad nonce): another RPC says the same.
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no RPC answered")
    }

    private suspend fun post(url: String, body: String): JsonElement {
        val request = Request.Builder().url(url).post(body.toRequestBody(json)).build()
        val response = http.newCall(request).await()
        val text = response.use { r ->
            val t = withContext(Dispatchers.IO) { r.body.string() }
            // The server's own refusals (busy, method) and HTTP errors: try the next RPC.
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            t
        }
        return answer(Json.parseToJsonElement(text))
    }

    companion object {
        fun answer(e: JsonElement): JsonElement {
            val o = e.jsonObject
            val err = o["error"]
            if (err != null && err !is JsonNull) {
                val eo = err.jsonObject
                throw RpcError(
                    eo["code"]?.jsonPrimitive?.intOrNull ?: -1,
                    eo["message"]?.jsonPrimitive?.contentOrNull ?: "RPC error",
                    eo["data"]?.let { d -> if (d is JsonPrimitive) d.contentOrNull else null },
                )
            }
            return o["result"] ?: JsonNull
        }
    }
}

/** One Multicall3 call and its answer. */
data class Call(val to: String, val data: ByteArray) {
    override fun equals(other: Any?) = other is Call && to.equals(other.to, true) && data.contentEquals(other.data)
    override fun hashCode() = to.lowercase().hashCode() * 31 + data.contentHashCode()
}

class CallResult(val success: Boolean, val data: ByteArray)

private val aggregate3 = AbiFunction(
    "function aggregate3((address target, bool allowFailure, bytes callData)[] calls) payable returns ((bool success, bytes returnData)[] returnData)",
)

/** Calldata per Multicall3 request: about what viem's batch of 8 KB held, as the extension sends. */
private const val MULTICALL_BYTES = 8_192

/**
 * The engine's reads on one chain: eth_call, Multicall3 (failures allowed), and the reads a
 * trade needs (balance, nonce, base fee, gas estimate, receipt). Reads made together, by
 * coroutines that run at the same moment, go out in one Multicall3 request (as viem batches).
 */
class ChainReader(val chain: EvmChain, private val rpc: EvmRpc, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val mutex = Mutex()
    private var pending: MutableList<Pair<Call, CompletableDeferred<CallResult>>>? = null

    private fun hex(n: BigInteger) = "0x" + n.toString(16)

    suspend fun request(method: String, params: JsonArray): JsonElement = rpc.request(chain, method, params)

    /** A plain eth_call; a revert throws RpcError. */
    suspend fun ethCall(to: String, data: ByteArray, from: String? = null, value: BigInteger? = null): ByteArray {
        val call = buildJsonObject {
            if (from != null) put("from", from)
            put("to", to)
            put("data", data.toHex())
            if (value != null && value.signum() > 0) put("value", hex(value))
        }
        val r = request("eth_call", buildJsonArray { add(call); add(JsonPrimitive("latest")) })
        return r.jsonPrimitive.content.hexBytes()
    }

    /** Each call's answer, failures allowed, in as few Multicall3 requests as the size allows. */
    suspend fun multicall(calls: List<Call>): List<CallResult> {
        if (calls.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<Call>>()
        var cur = mutableListOf<Call>()
        var size = 0
        for (c in calls) {
            if (cur.isNotEmpty() && size + c.data.size > MULTICALL_BYTES) {
                chunks += cur
                cur = mutableListOf()
                size = 0
            }
            cur += c
            size += c.data.size
        }
        chunks += cur
        return coroutineScope {
            chunks.map { chunk -> async { aggregate(chunk) } }.awaitAll().flatten()
        }
    }

    private suspend fun aggregate(calls: List<Call>): List<CallResult> {
        val data = aggregate3.encode(calls.map { listOf(it.to, true, it.data) })
        val ret = ethCall(EvmChains.MULTICALL3, data)
        val results = aggregate3.decodeResult(ret)[0] as List<*>
        if (results.size != calls.size) throw IOException("multicall answered ${results.size} of ${calls.size}")
        return results.map { r ->
            val l = r as List<*>
            CallResult(l[0] as Boolean, l[1] as ByteArray)
        }
    }

    /**
     * One call that goes out with the other reads made at the same moment (viem's batched
     * readContract). Null when it failed or answered nothing decodable.
     */
    suspend fun read(to: String, fn: AbiFunction, vararg args: Any?): List<Any?>? {
        val r = readRaw(Call(to, fn.encodeList(args.toList())))
        if (!r.success) return null
        return try {
            fn.decodeResult(r.data)
        } catch (e: AbiException) {
            null
        }
    }

    /** read() of a function that returns one value. */
    suspend fun read1(to: String, fn: AbiFunction, vararg args: Any?): Any? = read(to, fn, *args)?.firstOrNull()

    /** One raw call batched with the other reads of this moment. */
    suspend fun readRaw(call: Call): CallResult {
        val d = CompletableDeferred<CallResult>()
        var start = false
        mutex.withLock {
            val list = pending ?: mutableListOf<Pair<Call, CompletableDeferred<CallResult>>>().also {
                pending = it
                start = true
            }
            list += call to d
        }
        if (start) {
            scope.launch {
                // Let the other reads of this moment join.
                delay(1)
                val batch = mutex.withLock { pending.also { pending = null } } ?: return@launch
                try {
                    val res = multicall(batch.map { it.first })
                    batch.forEachIndexed { i, (_, def) -> def.complete(res[i]) }
                } catch (e: Exception) {
                    batch.forEach { (_, def) -> def.completeExceptionally(e) }
                }
            }
        }
        return d.await()
    }

    /** Several reads in one Multicall3 request, each decoded, null where it failed. */
    suspend fun reads(calls: List<Triple<String, AbiFunction, List<Any?>>>): List<List<Any?>?> {
        val res = multicall(calls.map { (to, fn, args) -> Call(to, fn.encodeList(args)) })
        return res.mapIndexed { i, r ->
            if (!r.success) null else try {
                calls[i].second.decodeResult(r.data)
            } catch (e: AbiException) {
                null
            }
        }
    }

    private fun quantity(e: JsonElement): BigInteger = BigInteger(e.jsonPrimitive.content.removePrefix("0x").ifEmpty { "0" }, 16)

    /** Native balance, now or at the end of `block`. */
    suspend fun balance(address: String, block: BigInteger? = null): BigInteger =
        quantity(request("eth_getBalance", buildJsonArray { add(JsonPrimitive(address)); add(JsonPrimitive(block?.let { hex(it) } ?: "latest")) }))

    suspend fun pendingNonce(address: String): Long = nonce(address, "pending")

    /** Transactions sent from `address`: mined ones at "latest", known ones at "pending". */
    suspend fun nonce(address: String, tag: String): Long =
        quantity(request("eth_getTransactionCount", buildJsonArray { add(JsonPrimitive(address)); add(JsonPrimitive(tag)) })).toLong()

    suspend fun baseFee(): BigInteger {
        val block = request("eth_getBlockByNumber", buildJsonArray { add(JsonPrimitive("latest")); add(JsonPrimitive(false)) })
        val fee = (block as? JsonObject)?.get("baseFeePerGas")?.takeIf { it !is JsonNull } ?: return BigInteger.ZERO
        return quantity(fee)
    }

    /** eth_estimateGas: the simulation every trade passes before it is signed. */
    suspend fun estimateGas(from: String, to: String, data: ByteArray, value: BigInteger): BigInteger {
        val call = buildJsonObject {
            put("from", from)
            put("to", to)
            put("data", data.toHex())
            put("value", hex(value))
        }
        return quantity(request("eth_estimateGas", buildJsonArray { add(call) }))
    }

    /** A landed transaction's receipt, or null while it is pending. */
    suspend fun receipt(hash: String): Receipt? {
        val r = request("eth_getTransactionReceipt", buildJsonArray { add(JsonPrimitive(hash)) })
        if (r is JsonNull) return null
        val o = r.jsonObject
        val logs = (o["logs"] as? JsonArray).orEmpty().map { l ->
            val lo = l.jsonObject
            Log(
                address = lo.getValue("address").jsonPrimitive.content.lowercase(),
                topics = (lo["topics"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.lowercase() },
                data = lo["data"]?.jsonPrimitive?.content ?: "0x",
            )
        }
        val l1 = (o["l1Fee"] as? JsonPrimitive)?.content
        return Receipt(
            success = o["status"]?.jsonPrimitive?.content == "0x1",
            gasUsed = o["gasUsed"]?.let { quantity(it) } ?: BigInteger.ZERO,
            effectiveGasPrice = o["effectiveGasPrice"]?.let { quantity(it) } ?: BigInteger.ZERO,
            logs = logs,
            blockNumber = o["blockNumber"]?.takeIf { it !is JsonNull }?.let { quantity(it) },
            l1Fee = if (l1 != null && Regex("^0x[0-9a-fA-F]+$").matches(l1)) BigInteger(l1.substring(2), 16) else BigInteger.ZERO,
        )
    }
}

data class Log(val address: String, val topics: List<String>, val data: String)

/** A landed transaction, as receipts.ts reads it. */
data class Receipt(
    val success: Boolean,
    val gasUsed: BigInteger,
    val effectiveGasPrice: BigInteger,
    val logs: List<Log>,
    val blockNumber: BigInteger? = null,
    /** The L1 data fee OP-stack chains (Base) add to the receipt. */
    val l1Fee: BigInteger = BigInteger.ZERO,
) {
    /** Network fee actually paid: gas x price, plus the L1 data fee. */
    val feeWei: BigInteger get() = gasUsed * effectiveGasPrice + l1Fee

    /** What `wallet` received of `token` in this transaction (ERC-20 Transfer logs to it). */
    fun received(token: String, wallet: String): BigInteger {
        val to = "0x" + "0".repeat(24) + wallet.removePrefix("0x").lowercase()
        return logs.filter { it.address.equals(token, true) && it.topics.size == 3 && it.topics[0] == TRANSFER && it.topics[2] == to }
            .fold(BigInteger.ZERO) { s, l -> s + word(l.data) }
    }

    /**
     * Wrapped native unwrapped in the transaction (a sell's native out: the router unwraps and
     * forwards it). Zero when the pool trades native directly (Uniswap v4).
     */
    fun unwrapped(wrapped: String): BigInteger = logs.filter { it.topics.firstOrNull() == WITHDRAWAL && it.address.equals(wrapped, true) }
        .fold(BigInteger.ZERO) { s, l -> s + word(l.data) }

    private fun word(data: String): BigInteger = if (data.length >= 66) BigInteger(data.substring(2, 66), 16) else BigInteger.ZERO

    companion object {
        const val TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
        const val WITHDRAWAL = "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65"
    }
}
