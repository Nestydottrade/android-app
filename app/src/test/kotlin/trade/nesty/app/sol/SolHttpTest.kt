package trade.nesty.app.sol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * The reads' transport, with no network: each "endpoint" is answered inside the client. A slow
 * endpoint must fail over to the next as a down one does, as the extension's reads do; and a read
 * one caller gives up on must still serve the others.
 */
class SolHttpTest {
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** slow.test answers after a second; any other host at once, with a balance of 7. */
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val host = chain.request().url.host
        asked += host
        if (host == "slow.test") Thread.sleep(1_000)
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},"value":7}}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val params = buildJsonArray { add("11111111111111111111111111111111") }

    @Test
    fun aSlowEndpointGivesWayToTheNext() = runBlocking {
        val call = HttpSolCall(http, urls = listOf("https://slow.test/rpc", "https://fast.test/rpc"), timeoutMs = 200)
        val started = System.currentTimeMillis()
        val r = call.call("getBalance", params)
        assertEquals(7, r.jsonObject.getValue("value").jsonPrimitive.int)
        assertEquals(listOf("slow.test", "fast.test"), asked.toList())
        assertTrue(System.currentTimeMillis() - started < 1_000)
    }

    @Test
    fun whenEveryEndpointIsSlowTheReadFailsWithoutCancelling() = runBlocking {
        val call = HttpSolCall(http, urls = listOf("https://slow.test/a", "https://slow.test/b"), timeoutMs = 150)
        try {
            call.call("getBalance", params)
            fail("expected a failure")
        } catch (e: CancellationException) {
            fail("a slow read cancelled its caller: $e")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.startsWith("No answer in"))
        }
        assertEquals(2, asked.size)
    }

    @Test
    fun aPriceReadOneCallerLeavesStillServesTheNext() = runBlocking {
        val replay = SolVectors.load("replay")
        val record = ReplaySolCall(replay)
        val gate = CompletableDeferred<Unit>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val call = SolCall { method, p ->
            if (reads.incrementAndGet() == 1) gate.await()
            record.call(method, p)
        }
        val price = SolPrice(SolRpc(call))
        // The first caller (a sheet that closes) leaves while the pool is being read.
        val first = async { price.usd() }
        while (reads.get() == 0) kotlinx.coroutines.yield()
        first.cancel()
        gate.complete(Unit)
        val usd = withTimeout(5_000) { price.usd() }
        val want = replay.getValue("solUsd").jsonPrimitive.content.toDouble()
        assertEquals(want, usd!!, want * 1e-6)
    }
}
