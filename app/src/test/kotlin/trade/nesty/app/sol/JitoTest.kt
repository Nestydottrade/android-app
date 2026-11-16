package trade.nesty.app.sol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.TradeException
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.evm.big
import trade.nesty.app.evm.bool
import trade.nesty.app.evm.int
import trade.nesty.app.evm.str
import trade.nesty.app.keys.Base58
import java.math.BigInteger

/**
 * MEV protection on Solana: each trade transaction tips one block engine tip account and carries
 * the marker where it fits, the validator takes exactly that tip and no other, and the replayed
 * coins of every venue still build. Read-only: the signer keeps the message and refuses.
 */
class JitoTest {
    private val wallet = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"

    @Test
    fun theTipAccountsAndTheMarkerAreKeys() {
        assertEquals(8, Jito.TIP_ACCOUNTS.size)
        for (a in Jito.TIP_ACCOUNTS + Jito.DONT_FRONT) assertTrue(a, SolAddr.isAddress(a))
        assertTrue(Jito.DONT_FRONT.startsWith("jitodontfront"))
        assertEquals(9, Jito.ENGINES.size)
        assertTrue(Jito.ENGINES.all { it.startsWith("https://") && it.endsWith("/api/v1/transactions?bundleOnly=true") })
    }

    @Test
    fun onlyTheTipNestyBuildsIsReadAsATip() {
        val to = Jito.TIP_ACCOUNTS[3]
        val tip = BigInteger.valueOf(50_000)
        assertEquals(tip, Jito.tipOf(Jito.tip(wallet, tip, to, marked = true), wallet))
        assertEquals(tip, Jito.tipOf(Jito.tip(wallet, tip, to, marked = false), wallet))
        // Anywhere else, from anyone else, or with another account: not a tip.
        assertNull(Jito.tipOf(Spl.transfer(wallet, "So11111111111111111111111111111111111111112", tip), wallet))
        assertNull(Jito.tipOf(Jito.tip("FakeWa11et1111111111111111111111111111111111", tip, to, marked = true), wallet))
        val extra = Jito.tip(wallet, tip, to, marked = true).let { Instruction(it.programId, it.keys + meta(SolAddr.SYSVAR_CLOCK), it.data) }
        assertNull(Jito.tipOf(extra, wallet))
    }

    // ── the replayed coins ─────────────────────────────────────────────────────────

    private val replay = SolVectors.load("replay")
    private val nowMs = replay.getValue("nowMs").jsonPrimitive.content.toLong()
    private val replayWallet = replay.getValue("wallet").str()
    private val p = replay.getValue("prefs").jsonObject
    private val tip = BigInteger.valueOf(200_000)
    private val tipped = SolPrefs(p.getValue("slippageBps").int(), p.getValue("priorityLamports").big(), p.getValue("maxGasUsd").jsonPrimitive.content.toDouble(), tipLamports = tip)
    private val cases = replay.getValue("cases").jsonArray.map { it.jsonObject }.filter { it["error"] == null }

    private fun hints(c: JsonObject) = c.getValue("hints").jsonArray.map { it.str() }

    /** A legacy message's keys and instructions: (program, account indexes, data). */
    private class Decoded(val keys: List<String>, val ixs: List<Triple<String, List<Int>, ByteArray>>)

    private fun decode(m: ByteArray): Decoded {
        var at = 3
        fun compact(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val b = m[at++].toInt() and 0xff
                v = v or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
            }
        }
        val keys = List(compact()) { Base58.encode(m.copyOfRange(at, at + 32)).also { at += 32 } }
        at += 32
        val ixs = List(compact()) {
            val program = keys[m[at++].toInt() and 0xff]
            val accounts = List(compact()) { m[at++].toInt() and 0xff }
            val n = compact()
            val data = m.copyOfRange(at, at + n).also { at += n }
            Triple(program, accounts, data)
        }
        return Decoded(keys, ixs)
    }

    @Test
    fun everyTradeTransactionTipsOnceAndStillFits() = runBlocking {
        var checked = 0
        for (c in cases) {
            if (c["meta"] is JsonNull || c["meta"] == null) continue
            val mint = c.getValue("mint").str()
            for (side in listOf(Side.BUY, Side.SELL)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val trader = SolTrader(
                        rpc = SolRpc(ReplaySolCall(replay), SolSender { throw AssertionError("nothing is sent") }, SolSender { throw AssertionError("nothing is sent") }),
                        scope = scope,
                        now = { nowMs },
                        pollMs = 5,
                        random = { 0 },
                    )
                    val signer = CaptureSigner(replayWallet)
                    val flow = try {
                        if (side == Side.BUY) {
                            trader.buy(SolBuyRequest(mint, BigInteger.valueOf(100_000_000), tipped, poolHints = hints(c)), signer)
                        } else {
                            trader.sell(SolSellRequest(mint, 5_000, tipped, poolHints = hints(c)), signer)
                        }
                    } catch (e: TradeException) {
                        continue
                    }
                    withTimeout(20_000) { flow.first { it.status != TradeStatus.WORKING } }
                    // Each message reached the signer: the validator took it, tip and all.
                    for (m in signer.messages) {
                        assertTrue("${c.getValue("name").str()} $side: ${m.size} bytes", m.size + 65 <= SolTx.MAX_TX_BYTES)
                        val d = decode(m)
                        val tips = d.ixs.filter { (program, accounts, data) ->
                            program == SolAddr.SYSTEM_PROGRAM && data.size == 12 && le(data, 0, 4) == BigInteger.TWO && d.keys[accounts[1]] in Jito.TIP_ACCOUNTS
                        }
                        assertEquals("${c.getValue("name").str()} $side: one tip", 1, tips.size)
                        assertEquals(tip, le(tips[0].third, 4, 8))
                        assertEquals(replayWallet, d.keys[tips[0].second[0]])
                        checked++
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
        assertTrue("checked $checked", checked >= 25)
    }

    @Test
    fun theValidatorTakesOneTipUpToTheSpeedsTip() = runBlocking {
        var judged = 0
        for (c in cases) {
            val verdicts = c["verdicts"]?.jsonArray ?: continue
            val ok = verdicts.map { it.jsonObject }.firstOrNull { it.getValue("ok").bool() } ?: continue
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val trader = SolTrader(rpc = SolRpc(ReplaySolCall(replay)), scope = scope, now = { nowMs }, pollMs = 5, random = { 0 })
                val market = trader.market(c.getValue("mint").str(), hints = hints(c))
                val best = SolRoutes.bestRoute(market.routes, Side.BUY, BigInteger.valueOf(100_000_000)) ?: continue
                val ctxJson = ok.getValue("ctx").jsonObject
                val unit = ctxJson.getValue("payUnit").jsonObject
                fun ctx(maxTip: Long) = SolValidationContext(
                    wallet = ctxJson.getValue("wallet").str(),
                    mint = ctxJson.getValue("mint").str(),
                    route = best.route,
                    side = if (ctxJson.getValue("side").str() == "buy") Side.BUY else Side.SELL,
                    maxSpend = ctxJson.getValue("maxSpend").big(),
                    maxPriorityLamports = ctxJson.getValue("maxPriorityLamports").big(),
                    payUnit = PayUnit(unit.getValue("symbol").str(), unit.getValue("decimals").int()),
                    maxTipLamports = BigInteger.valueOf(maxTip),
                )
                val ixs = SolVectors.instructions(ok.getValue("instructions"))
                val w = ctxJson.getValue("wallet").str()
                val tipIx = Jito.tip(w, BigInteger.valueOf(50_000), Jito.TIP_ACCOUNTS[0], marked = true)
                assertTrue(SolValidator.validateTrade(ixs + tipIx, ctx(50_000)).ok)
                assertEquals("Above your MEV protection tip", SolValidator.validateTrade(ixs + tipIx, ctx(0)).reason)
                assertEquals("Only one tip", SolValidator.validateTrade(ixs + tipIx + tipIx, ctx(100_000)).reason)
                // The same transfer to anyone else is still refused.
                val other = Spl.transfer(w, Jito.DONT_FRONT, BigInteger.valueOf(50_000))
                assertFalse(SolValidator.validateTrade(ixs + other, ctx(50_000)).ok)
                judged++
            } finally {
                scope.cancel()
            }
        }
        assertTrue("judged $judged", judged >= 3)
    }
}
