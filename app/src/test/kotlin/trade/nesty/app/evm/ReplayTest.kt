package trade.nesty.app.evm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.evm.EvmTestKit.bigs
import trade.nesty.app.evm.EvmTestKit.pool
import trade.nesty.app.evm.EvmTestKit.venue
import java.math.BigInteger
import java.util.Collections

/**
 * Answers reads from replay.json: every read the TypeScript engine made on live chains, each
 * Multicall3 sub-call on its own, held still. A read it did not make is answered as a failed
 * call and noted, so a test can name it.
 */
class ReplayRpc(replay: JsonObject) : EvmRpc {
    private val calls: Map<Long, JsonObject> = replay.obj("chains").mapKeys { it.key.toLong() }.mapValues { it.value.jsonObject.obj("calls") }
    val missing: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Reads answered here before the record, by "to:data" (lowercase): the wallet's own. */
    val overrides = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, String>>()

    /** Any other method, for a test that trades. */
    var other: (suspend (EvmChain, String, JsonArray) -> JsonElement)? = null

    fun lookup(chainId: Long, to: String, data: String): Pair<Boolean, String> {
        val key = "${to.lowercase()}:${data.lowercase()}"
        overrides[key]?.let { return it }
        val hit = calls[chainId]?.get(key)?.jsonObject
        if (hit == null) {
            missing += "$chainId $key".take(140)
            return false to "0x"
        }
        return hit.getValue("ok").bool() to hit.s("ret")
    }

    override suspend fun request(chain: EvmChain, method: String, params: JsonArray): JsonElement {
        if (method != "eth_call") return other?.invoke(chain, method, params) ?: throw AssertionError("unrecorded $method")
        val call = params[0].jsonObject
        val to = call.getValue("to").jsonPrimitive.content.lowercase()
        val data = (call["data"] ?: call["input"])?.jsonPrimitive?.content?.lowercase() ?: "0x"
        val sub = AGGREGATE3.decodeCall(data.hexBytes())
        if (to == EvmChains.MULTICALL3.lowercase() && sub != null) {
            val list = sub[0] as List<*>
            val results = list.map { c ->
                val l = c as List<*>
                val (ok, ret) = lookup(chain.id, l[0] as String, (l[2] as ByteArray).toHex())
                listOf(ok, ret.hexBytes())
            }
            return JsonPrimitive(Abi.encode(AGGREGATE3.outputs, listOf(results)).toHex())
        }
        val (ok, ret) = lookup(chain.id, to, data)
        if (!ok) throw RpcError(3, "execution reverted", ret)
        return JsonPrimitive(ret)
    }

    companion object {
        val AGGREGATE3 = AbiFunction(
            "function aggregate3((address target, bool allowFailure, bytes callData)[] calls) payable returns ((bool success, bytes returnData)[] returnData)",
        )
    }
}

/**
 * Discovery and quotes on real coins of every launchpad and DEX, replayed: the Kotlin engine
 * must find the same pools, quote the same amounts, pick the same pool for each amount, and
 * read the same venue fees and funding legs as the TypeScript engine did on the live chains.
 * Read-only: nothing is signed or sent.
 */
class ReplayTest {
    private val v = EvmVectors.load("replay")

    /** What was compared, so an empty loop cannot pass. */
    private var pools = 0
    private var quotes = 0
    private var picks = 0

    @Test
    fun replaysEveryCase() = runBlocking {
        val cases = v.arr("cases").map { it.jsonObject }
        assertTrue(cases.size >= 45)
        val problems = mutableListOf<String>()
        for (c in cases) {
            val label = c.s("label")
            val rpc = ReplayRpc(v)
            try {
                replay(c, rpc)
            } catch (e: AssertionError) {
                problems += "$label: ${e.message}"
            }
            if (rpc.missing.isNotEmpty()) problems += "$label: reads not in the record: ${rpc.missing.distinct().take(4)}"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        // Every pool, quote and pick of the record was compared.
        var recordPools = 0
        var recordQuotes = 0
        var recordPicks = 0
        for (c in cases) {
            recordPools += c.arr("discovered").sumOf { it.jsonObject.arr("pools").size }
            for (k in listOf("buys", "sells")) {
                recordQuotes += c.arr(k).sumOf { q -> bigs(q.jsonObject["outs"])?.count { it != null } ?: 0 }
            }
            recordPicks += c.arr("bestBuy").count { it !is JsonNull } + c.arr("bestSell").count { it !is JsonNull }
        }
        assertEquals("pools compared", recordPools, pools)
        assertEquals("quotes compared", recordQuotes, quotes)
        assertEquals("picks compared", recordPicks, picks)
        assertTrue(pools >= 150 && quotes >= 1_000 && picks >= 250)
    }

    private suspend fun replay(c: JsonObject, rpc: ReplayRpc) {
        val label = c.s("label")
        val chainId = c.getValue("chainId").long()
        val chain = EvmTestKit.chain(chainId)
        val ctx = VenueCtx(chain, ChainReader(chain, rpc))
        val token = c.s("token").lowercase()

        // 1) Discovery, venue by venue, in the extension's order.
        val discovered = c.arr("discovered").map { it.jsonObject }
        val venues = EvmVenues.all.filter { it.supports(chainId) }
        assertEquals("$label: venues", discovered.map { it.s("venueId") }, venues.map { it.id })
        val pools = mutableListOf<PoolRef>()
        for ((i, d) in discovered.withIndex()) {
            val expected = d.arr("pools").map { pool(it.jsonObject) }
            val got = venues[i].discover(ctx, token)
            assertEquals("$label: ${d.s("venueId")} pools", expected.map { it.copy(label = it.label.lowercase()) }, got.map { it.copy(label = it.label.lowercase()) })
            pools += got
            this.pools += got.size
        }

        // 2) Quotes on every pool, and the pick per amount.
        val buyAmounts = c.arr("buyAmounts").map { it.big() }
        quoteAll("$label: buy", ctx, pools, Side.BUY, buyAmounts, c.arr("buys"))
        val routing = Routing(EvmVenues.all)
        if (buyAmounts.isNotEmpty()) {
            val best = routing.bestWithRef(ctx, token, Side.BUY, buyAmounts[0], buyAmounts.drop(1))
            assertPick("$label: best buy", c.arr("bestBuy"), best)
        }
        val sellAmounts = c.arr("sellAmounts").map { it.big() }
        if (sellAmounts.isNotEmpty()) {
            quoteAll("$label: sell", ctx, pools, Side.SELL, sellAmounts, c.arr("sells"))
            val best = routing.bestWithRef(ctx, token, Side.SELL, sellAmounts[0], sellAmounts.drop(1))
            assertPick("$label: best sell", c.arr("bestSell"), best)
        }

        // 3) What a trade reads around these pools: the venue's own fee, the funding leg.
        val extras = c.arr("extras").map { it.jsonObject }
        assertEquals("$label: extras", pools.size, extras.size)
        val buyIn = buyAmounts.drop(1)
        val sellIn = sellAmounts.drop(1)
        for ((i, e) in extras.withIndex()) {
            val p = pools[i]
            val venue = venue(p.venueId)
            val what = "$label: ${p.venueId} ${p.label}"
            val tax = orNull { venue.venueTax(ctx, p) }
            val expectedTax = e["tax"]
            when {
                expectedTax == null || expectedTax is JsonNull -> assertEquals("$what: no venue fee", null, tax)
                else -> {
                    val t = expectedTax.jsonObject
                    assertEquals("$what: venue fee", VenueTax(t.getValue("buyBps").int(), t.getValue("sellBps").int()), tax)
                }
            }
            val f = venue.funding(ctx, p)
            val ef = e["funding"]?.takeIf { it !is JsonNull }?.jsonObject
            if (ef == null) {
                assertEquals("$what: not funded", null, f)
                continue
            }
            checkNotNull(f) { "$what: funding missing" }
            assertEquals("$what: asset", ef.s("asset").lowercase(), f.asset.lowercase())
            assertEquals("$what: symbol", ef.s("symbol"), f.symbol)
            assertEquals("$what: leg", pool(ef.obj("leg")).let { it.copy(label = it.label.lowercase()) }, f.leg.copy(label = f.leg.label.lowercase()))
            val legVenue = venue(f.leg.venueId)
            assertEquals("$what: leg supported", ef.getValue("legSupported").bool(), NestyRouter.legSupported(ctx, legVenue, f.leg))
            assertEquals("$what: leg quotes", bigs(ef["legBuy"]), orNull { legVenue.quote(ctx, f.leg, Side.BUY, buyIn) })
            val assetIn = ef.arr("assetIn").map { it.big() }
            if (assetIn.isNotEmpty()) assertEquals("$what: asset buys", bigs(ef["assetBuy"]), orNull { f.quoteAsset(ctx, Side.BUY, assetIn) })
            if (sellIn.isNotEmpty()) assertEquals("$what: asset sells", bigs(ef["assetSell"]), orNull { f.quoteAsset(ctx, Side.SELL, sellIn) })
        }

        // 4) Why nothing trades, when nothing does.
        val idle = c["idle"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        if (idle != null) assertEquals("$label: idle", idle, routing.whyNone(ctx, token))
    }

    private suspend fun quoteAll(what: String, ctx: VenueCtx, pools: List<PoolRef>, side: Side, amounts: List<BigInteger>, expected: JsonArray) {
        assertEquals("$what: pools quoted", expected.size, pools.size)
        for ((i, p) in pools.withIndex()) {
            val e = expected[i].jsonObject
            assertEquals("$what: pool", e.s("id").lowercase(), p.id)
            val got = orNull { venue(p.venueId).quote(ctx, p, side, amounts) }
            assertEquals("$what: ${p.label}", bigs(e["outs"]), got)
            quotes += got?.count { it != null } ?: 0
        }
    }

    private fun assertPick(what: String, expected: JsonArray, got: List<RouteChoice>) {
        assertEquals("$what: amounts", expected.size, got.size)
        for ((i, e) in expected.withIndex()) {
            val r = got[i]
            if (e is JsonNull) {
                assertEquals("$what #$i: no pool", null, r.route)
                continue
            }
            val o = e.jsonObject
            val route = checkNotNull(r.route) { "$what #$i: no route, expected ${o.s("id")}" }
            assertEquals("$what #$i pool", o.s("id").lowercase(), route.pool.id)
            assertEquals("$what #$i venue", o.s("venueId"), route.venue.id)
            assertEquals("$what #$i out", o.getValue("out").big(), route.out)
            assertEquals("$what #$i ref", o["refOut"]?.takeIf { it !is JsonNull }?.big(), r.refOut)
            picks++
        }
    }
}
