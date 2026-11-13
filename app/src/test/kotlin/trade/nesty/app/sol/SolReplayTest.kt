package trade.nesty.app.sol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.TradeException
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.evm.big
import trade.nesty.app.evm.bool
import trade.nesty.app.evm.int
import trade.nesty.app.evm.str
import trade.nesty.app.sol.dex.Dex
import java.math.BigInteger

/**
 * The extension's Solana engine on real coins of every venue, replayed (fixtures/sol/replay.json):
 * the Kotlin engine must find the same routes and funding pools, quote the same amounts, build
 * the same transactions byte for byte (compute budget included), and judge changed ones the same.
 * Read-only: the signer keeps the message and refuses, so nothing is signed or sent.
 */
class SolReplayTest {
    private val replay = SolVectors.load("replay")
    private val nowMs = replay.getValue("nowMs").jsonPrimitive.content.toLong()
    private val wallet = replay.getValue("wallet").str()
    private val p = replay.getValue("prefs").jsonObject
    private val prefs = SolPrefs(p.getValue("slippageBps").int(), p.getValue("priorityLamports").big(), p.getValue("maxGasUsd").jsonPrimitive.content.toDouble())
    private val cases = replay.getValue("cases").jsonArray.map { it.jsonObject }.filter { it["error"] == null }

    private class Run(val call: ReplaySolCall, val trader: SolTrader, val scope: CoroutineScope)

    private fun run(): Run {
        val call = ReplaySolCall(replay)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val trader = SolTrader(
            rpc = SolRpc(call, SolSender { throw AssertionError("nothing is sent") }),
            scope = scope,
            now = { nowMs },
            pollMs = 5,
            random = { 0 },
        )
        return Run(call, trader, scope)
    }

    private fun hints(c: JsonObject) = c.getValue("hints").jsonArray.map { it.str() }

    private fun name(c: JsonObject) = c.getValue("name").str()

    private fun kindOf(ts: String) = ts.uppercase().replace('-', '_')

    private fun describe(r: SolRoute): String = when (r) {
        is SolRoute.OnPump -> "pump ${r.pump.pay.mint} ${Pump.routeVariant(r.pump)} " + r.pump.hops.joinToString(" ") { h ->
            when (h) {
                is CurveVenue -> "curve:${h.mint}:${h.quote.mint}"
                is PoolVenue -> "pool:${h.mint}:${h.quote.mint}:${h.poolAddress}"
            }
        }
        is SolRoute.OnDex -> "dex ${r.venue.dex} ${r.venue.pool} ${r.venue.mint} ${r.venue.quote.mint} ${Dex.module(r.venue).label(r.venue)}"
    }

    private fun describe(o: JsonObject): String = if (o.getValue("kind").str() == "pump") {
        "pump ${o.getValue("pay").str()} ${kindOf(o.getValue("variant").str())} " + o.getValue("hops").jsonArray.joinToString(" ") { e ->
            val h = e.jsonObject
            if (h.getValue("venue").str() == "curve") {
                "curve:${h.getValue("mint").str()}:${h.getValue("quote").str()}"
            } else {
                "pool:${h.getValue("mint").str()}:${h.getValue("quote").str()}:${h.getValue("pool").str()}"
            }
        }
    } else {
        "dex ${kindOf(o.getValue("dex").str())} ${o.getValue("pool").str()} ${o.getValue("mint").str()} ${o.getValue("pay").str()} ${o.getValue("label").str()}"
    }

    @Test
    fun recordCoversEveryVenue() {
        val kinds = cases.flatMap { c ->
            c["market"]?.jsonObject?.get("routes")?.jsonArray?.map { r ->
                val o = r.jsonObject
                o["dex"]?.str() ?: "pump-${o.getValue("variant").str()}"
            } ?: emptyList()
        }.toSet()
        for (k in listOf("pump-curve-v3", "pump-multi-hop", "raydium-amm", "raydium-cpmm", "launchlab", "meteora-dbc", "meteora-damm", "meteora-dlmm", "orca")) {
            assertTrue("no $k in the record: $kinds", k in kinds)
        }
        assertTrue("PumpSwap", kinds.any { it == "pump-pool-v1" || it == "pump-pool-v2" })
        assertTrue("a coin bought through SOL", cases.any { it["market"]?.jsonObject?.get("funding")?.jsonArray?.isNotEmpty() == true })
        assertTrue("nothing refused", replay.getValue("refused").jsonArray.isEmpty())
    }

    @Test
    fun marketsAndQuotes() = runBlocking {
        for (c in cases) {
            val r = run()
            try {
                val mint = c.getValue("mint").str()
                val market = r.trader.market(mint, hints = hints(c))
                val want = c.getValue("market").jsonObject
                val wantRoutes = want.getValue("routes").jsonArray.map { it.jsonObject }
                assertEquals(name(c), wantRoutes.map { describe(it) }, market.routes.map { describe(it) })
                assertEquals(name(c), (want["idle"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content, market.idle)
                val wantFunding = want["funding"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
                assertEquals(
                    name(c),
                    wantFunding.map { "${kindOf(it.getValue("dex").str())} ${it.getValue("pool").str()} ${it.getValue("label").str()}" },
                    (market.funding ?: emptyList()).map { "${it.dex} ${it.pool} ${Dex.module(it).label(it)}" },
                )
                if (c["meta"] is JsonNull || c["meta"] == null) {
                    assertTrue(name(c), market.routes.isEmpty())
                    continue
                }
                val raw = listOf(10_000_000L, 100_000_000L, 1_000_000_000L).map(BigInteger::valueOf)
                val sellIn = c.getValue("panel").jsonObject.bigs("sellIn").map { it!! }
                market.routes.forEachIndexed { i, route ->
                    val w = wantRoutes[i]
                    assertEquals("${name(c)} route $i buys", w.bigs("buys"), raw.map { SolRoutes.buyQuote(route, it) })
                    assertEquals("${name(c)} route $i sells", w.bigs("sells"), sellIn.map { SolRoutes.sellQuote(route, it) })
                    val spot = w.getValue("spot").jsonPrimitive.content.toDouble()
                    assertEquals("${name(c)} route $i spot", spot, SolRoutes.spotPrice(route), Math.abs(spot) * 1e-12)
                    val units = w.getValue("units").jsonObject
                    assertEquals("${name(c)} route $i units", units.getValue("buy").int(), SolRoutes.tradeUnits(route, Side.BUY, BigInteger.valueOf(100_000_000)))
                    assertEquals("${name(c)} route $i units", units.getValue("sell").int(), SolRoutes.tradeUnits(route, Side.SELL, sellIn[1]))
                }
                (market.funding ?: emptyList()).forEachIndexed { i, leg ->
                    assertEquals("${name(c)} leg $i", wantFunding[i].bigs("buys"), raw.map { SolRoutes.buyQuote(SolRoute.OnDex(leg), it) })
                }

                val panel = c.getValue("panel").jsonObject
                val solPriced = market.routes.firstOrNull()?.let { SolRoutes.pay(it).isSol } ?: false
                val buys = r.trader.quotes(mint, Side.BUY, raw, hints(c))
                if (solPriced || market.funding != null) {
                    panel.getValue("buys").jsonArray.forEachIndexed { i, q -> sameQuote("${name(c)} buy $i", q, buys[i]) }
                } else {
                    // Bought with a token no pool turns SOL into: the app does not buy it.
                    assertTrue(name(c), buys.all { it == null })
                }
                val sells = r.trader.quotes(mint, Side.SELL, sellIn, hints(c))
                panel.getValue("sells").jsonArray.forEachIndexed { i, q -> sameQuote("${name(c)} sell $i", q, sells[i]) }
            } finally {
                r.scope.cancel()
            }
        }
    }

    private fun sameQuote(what: String, want: JsonElement, got: trade.nesty.app.evm.Quote?) {
        if (want is JsonNull) {
            assertEquals(what, null, got)
            return
        }
        val w = want.jsonObject
        if (got == null) fail("$what: no quote, the extension quoted ${w.getValue("amountOut").str()}")
        got!!
        assertEquals(what, w.getValue("amountIn").big(), got.amountIn)
        assertEquals(what, w.getValue("amountOut").big(), got.out)
        assertEquals(what, (w["priceImpactBps"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toInt(), got.priceImpactBps)
        assertEquals(what, w.getValue("venue").str(), got.venue)
    }

    /** The transactions a buy or sell builds: the messages handed to the signer. */
    private suspend fun messages(start: suspend (SolSigner) -> kotlinx.coroutines.flow.StateFlow<trade.nesty.app.evm.Trade>): Pair<List<String>, String?> {
        val signer = CaptureSigner(wallet)
        val flow = try {
            start(signer)
        } catch (e: TradeException) {
            return emptyList<String>() to (e.message ?: e.code)
        }
        withTimeout(20_000) { flow.first { it.status != TradeStatus.WORKING } }
        return signer.messages.map { SolVectors.hex(it) } to null
    }

    private fun firstDifference(a: String, b: String): String {
        val at = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: a.length
        return "byte ${at / 2}: ${a.drop(at).take(40)} vs ${b.drop(at).take(40)}"
    }

    @Test
    fun buysAndSellsBuildTheSameTransactions() = runBlocking {
        var compared = 0
        for (c in cases) {
            if (c["meta"] is JsonNull || c["meta"] == null) continue
            val mint = c.getValue("mint").str()
            for (side in listOf(Side.BUY, Side.SELL)) {
                val r = run()
                try {
                    val want = c.getValue(if (side == Side.BUY) "buy" else "sell").jsonObject
                    val wantSigned = want["signed"]?.jsonArray?.map { it.jsonObject.getValue("message").str() } ?: emptyList()
                    val (got, refused) = messages { signer ->
                        if (side == Side.BUY) {
                            r.trader.buy(SolBuyRequest(mint, BigInteger.valueOf(100_000_000), prefs, poolHints = hints(c)), signer)
                        } else {
                            r.trader.sell(SolSellRequest(mint, 5_000, prefs, poolHints = hints(c)), signer)
                        }
                    }
                    val what = "${name(c)} $side"
                    if (wantSigned.isEmpty()) {
                        // The extension built nothing (refused, or a token-priced buy it could not pay for).
                        assertTrue("$what: the extension built nothing, the app built ${got.size}", got.isEmpty())
                        if (side == Side.BUY && want["refused"] == null) assertTrue("$what: $refused", refused != null)
                        continue
                    }
                    assertEquals("$what: transactions (refused: $refused)", wantSigned.size, got.size)
                    wantSigned.forEachIndexed { i, hex ->
                        if (hex != got[i]) fail("$what tx $i differs at ${firstDifference(hex, got[i])}")
                    }
                    compared += got.size
                } finally {
                    r.scope.cancel()
                }
            }
        }
        assertTrue("compared $compared", compared >= 25)
    }

    @Test
    fun validatorVerdicts() = runBlocking {
        var judged = 0
        for (c in cases) {
            val verdicts = c["verdicts"]?.jsonArray ?: continue
            if (verdicts.isEmpty()) continue
            val r = run()
            try {
                val mint = c.getValue("mint").str()
                val market = r.trader.market(mint, hints = hints(c))
                val best = SolRoutes.bestRoute(market.routes, Side.BUY, BigInteger.valueOf(100_000_000)) ?: error("${name(c)}: no route")
                for (e in verdicts) {
                    val v = e.jsonObject
                    val ctx = v.getValue("ctx").jsonObject
                    val unit = ctx.getValue("payUnit").jsonObject
                    val got = SolValidator.validateTrade(
                        SolVectors.instructions(v.getValue("instructions")),
                        SolValidationContext(
                            wallet = ctx.getValue("wallet").str(),
                            mint = ctx.getValue("mint").str(),
                            route = best.route,
                            side = if (ctx.getValue("side").str() == "buy") Side.BUY else Side.SELL,
                            maxSpend = ctx.getValue("maxSpend").big(),
                            maxPriorityLamports = ctx.getValue("maxPriorityLamports").big(),
                            payUnit = PayUnit(unit.getValue("symbol").str(), unit.getValue("decimals").int()),
                        ),
                    )
                    val what = "${name(c)}: ${v.getValue("name").str()}"
                    assertEquals(what, v.getValue("ok").bool(), got.ok)
                    assertEquals(what, (v["reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content, got.reason)
                    assertEquals(what, v.getValue("spend").big(), got.spend)
                    assertEquals(what, v.getValue("amountIn").big(), got.amountIn)
                    assertEquals(what, v.getValue("minOut").big(), got.minOut)
                    judged++
                }
            } finally {
                r.scope.cancel()
            }
        }
        assertTrue("judged $judged", judged >= 50)
    }

    @Test
    fun readsStayInsideTheRecord() = runBlocking {
        // Every account the app reads for these coins is one the extension read too.
        val missing = sortedSetOf<String>()
        for (c in cases) {
            val r = run()
            try {
                val mint = c.getValue("mint").str()
                r.trader.quotes(mint, Side.BUY, listOf(BigInteger.valueOf(100_000_000)), hints(c))
                r.trader.whyNone(mint, hints(c))
                missing += r.call.missing.map { "${name(c)}: $it" }
            } finally {
                r.scope.cancel()
            }
        }
        assertEquals(emptySet<String>(), missing)
    }
}
