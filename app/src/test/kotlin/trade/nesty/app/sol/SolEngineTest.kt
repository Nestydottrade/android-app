package trade.nesty.app.sol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.evm.StepKind
import trade.nesty.app.evm.StepStatus
import trade.nesty.app.evm.Trade
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.evm.big
import trade.nesty.app.evm.int
import trade.nesty.app.evm.str
import trade.nesty.app.keys.Base58
import trade.nesty.app.keys.Sol
import java.math.BigInteger
import java.util.Collections

/**
 * The Solana trade engine end to end over a made-up chain: real pools from the replay record, a
 * MADE-UP wallet, and every answer about the trade itself (balances, simulation, statuses, the
 * landed transaction) made up by the test. What is "sent" stays in the test.
 *
 * validate → simulate → sign → send → confirm → fill, and every way it stops before a signature.
 */
class SolEngineTest {
    private val replay = SolVectors.load("replay")
    private val nowMs = replay.getValue("nowMs").jsonPrimitive.content.toLong()
    private val p = replay.getValue("prefs").jsonObject
    private val prefs = SolPrefs(p.getValue("slippageBps").int(), p.getValue("priorityLamports").big(), p.getValue("maxGasUsd").jsonPrimitive.content.toDouble())
    private val cases = replay.getValue("cases").jsonArray.map { it.jsonObject }.filter { it["error"] == null }

    private fun routesOf(c: JsonObject) = c["market"]?.jsonObject?.get("routes")?.jsonArray?.map { it.jsonObject } ?: emptyList()

    /** A coin on its pump.fun curve, bought with SOL in one transaction. */
    private val direct = cases.first { c ->
        val r = routesOf(c).firstOrNull()
        r != null && r["variant"]?.str() == "curve-v3" && r.getValue("pay").str() == SolAddr.WSOL_MINT && (c["verdicts"]?.jsonArray?.size ?: 0) > 0
    }

    /** A pump.fun coin priced in USDC: SOL into USDC first, then the coin. */
    private val funded = cases.first { c ->
        val r = routesOf(c).firstOrNull()
        r != null && r["kind"]?.str() == "pump" && r.getValue("pay").str() == SolAddr.USDC_MINT && (c["market"]?.jsonObject?.get("funding")?.jsonArray?.size ?: 0) > 0
    }

    private val wallet = Sol.address(SolVectors.MADE_UP_SEED)

    private class Env(val call: ReplaySolCall, val sender: KeepSender, val clock: TestClock, val trader: SolTrader, val scope: CoroutineScope, val landed: MutableList<Trade>)

    private fun env(): Env {
        val call = ReplaySolCall(replay)
        assertEquals(wallet, call.wallet)
        val sender = KeepSender()
        val clock = TestClock(nowMs)
        val landed: MutableList<Trade> = Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val trader = SolTrader(rpc = SolRpc(call, sender), scope = scope, now = clock::now, pollMs = 1, random = { 0 }, onLanded = { landed += it })
        return Env(call, sender, clock, trader, scope, landed)
    }

    private fun hints(c: JsonObject) = c.getValue("hints").jsonArray.map { it.str() }

    private fun mint(c: JsonObject) = c.getValue("mint").str()

    /** The trade once it has ended and `until` holds: a step's fill lands after the step itself. */
    private suspend fun settle(flow: StateFlow<Trade>, until: (Trade) -> Boolean = { true }): Trade =
        withTimeout(30_000) { flow.first { it.status != TradeStatus.WORKING && until(it) } }

    private fun filled(t: Trade) = t.steps.all { it.status != StepStatus.CONFIRMED || it.feeWei != null }

    private fun confirmed(err: JsonElement = JsonNull) = buildJsonObject {
        put("confirmationStatus", "confirmed")
        put("err", err)
    }

    /** A landed transaction: the fee, and what `owner` holds of each mint before and after. */
    private fun landedTx(fee: Long, owner: String, tokens: Map<String, Long>, lamports: Pair<Long, Long> = 10_000_000_000L to 10_000_000_000L - fee, err: JsonElement = JsonNull) = buildJsonObject {
        put("meta", buildJsonObject {
            put("err", err)
            put("fee", fee)
            put("preBalances", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(lamports.first)) })
            put("postBalances", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(lamports.second)) })
            put("preTokenBalances", buildJsonArray {})
            put("postTokenBalances", buildJsonArray {
                tokens.entries.forEachIndexed { i, (m, amount) ->
                    add(buildJsonObject {
                        put("accountIndex", i + 1)
                        put("mint", m)
                        put("owner", owner)
                        put("uiTokenAmount", buildJsonObject { put("amount", amount.toString()) })
                    })
                }
            })
        })
        put("transaction", buildJsonObject {
            put("message", buildJsonObject { put("accountKeys", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(owner)) }) })
        })
    }

    private fun verifies(raw: ByteArray): Boolean {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(Sol.publicKey(SolVectors.MADE_UP_SEED), 0))
        val message = raw.copyOfRange(65, raw.size)
        v.update(message, 0, message.size)
        return v.verifySignature(raw.copyOfRange(1, 65))
    }

    private fun recordedMessage(c: JsonObject, side: String, i: Int = 0) =
        c.getValue(side).jsonObject.getValue("signed").jsonArray[i].jsonObject.getValue("message").str()

    private fun recordedSwapIndex(c: JsonObject): Int =
        c.getValue("buy").jsonObject.getValue("signed").jsonArray[0].jsonObject.getValue("instructions").jsonArray.indexOfFirst {
            it.jsonObject.getValue("programId").str() == Pump.PUMP_PROGRAM
        }

    private val fee = prefs.priorityLamports.toLong() + 5_000

    @Test
    fun aBuyIsSignedSentConfirmedAndFilled() = runBlocking {
        val e = env()
        try {
            var asked = 0
            e.call.status = {
                e.clock.advance(300)
                if (++asked < 3) JsonNull else confirmed()
            }
            e.call.transaction = { landedTx(fee, wallet, mapOf(mint(direct) to 123_456_789L)) }
            val signer = MadeUpSigner()
            val t = settle(e.trader.buy(SolBuyRequest(mint(direct), BigInteger.valueOf(100_000_000), prefs, poolHints = hints(direct)), signer), ::filled)
            assertEquals(TradeStatus.DONE, t.status)
            val step = t.steps.single()
            assertEquals(StepStatus.CONFIRMED, step.status)
            val raw = e.sender.distinct().single()
            assertTrue(verifies(raw))
            assertEquals(Base58.encode(raw.copyOfRange(1, 65)), step.hash)
            // The extension's transaction, byte for byte, now signed by the made-up key.
            assertEquals(recordedMessage(direct, "buy"), SolVectors.hex(raw.copyOfRange(65, raw.size)))
            assertEquals(BigInteger.valueOf(123_456_789), step.amountOut)
            assertEquals(BigInteger.valueOf(fee), step.feeWei)
            assertEquals(1, signer.signed.get())
            assertEquals(1, e.call.simulations.get())
            assertEquals(1, e.landed.size)
            assertEquals(SolTrader.CHAIN_ID, t.chainId)
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun aRefusedTradeIsNeitherSimulatedNorSigned() = runBlocking {
        val e = env()
        try {
            // A priority fee of 1 SOL is far above the $10 network-fee limit.
            val greedy = prefs.copy(priorityLamports = BigInteger.valueOf(1_000_000_000))
            val signer = MadeUpSigner()
            val t = settle(e.trader.buy(SolBuyRequest(mint(direct), BigInteger.valueOf(100_000_000), greedy, poolHints = hints(direct)), signer))
            assertEquals(TradeStatus.BLOCKED, t.status)
            assertEquals(StepStatus.BLOCKED, t.steps.single().status)
            assertNotNull(t.blocked)
            assertTrue(t.blocked!!.any { !it.ok && it.reason == "Above your max network fee" })
            assertEquals(0, signer.signed.get())
            assertEquals(0, e.call.simulations.get())
            assertTrue(e.sender.raws.isEmpty())
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun aWalletShortOfSolIsSkipped() = runBlocking {
        val e = env()
        try {
            e.call.lamports = { 1_000 }
            val signer = MadeUpSigner()
            val t = settle(e.trader.buy(SolBuyRequest(mint(direct), BigInteger.valueOf(100_000_000), prefs, poolHints = hints(direct)), signer))
            val step = t.steps.single()
            assertEquals(StepStatus.SKIPPED, step.status)
            // 0.1 SOL, the 0.0005 SOL priority fee and 0.006 SOL for new accounts.
            assertEquals("Insufficient SOL: about 0.1065 needed.", step.error)
            assertEquals(TradeStatus.FAILED, t.status)
            assertEquals(0, signer.signed.get())
            assertEquals(0, e.call.simulations.get())
            assertTrue(e.sender.raws.isEmpty())
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun aFailedSimulationSignsNothing() = runBlocking {
        val e = env()
        try {
            val at = recordedSwapIndex(direct)
            assertTrue(at >= 2)
            e.call.simulation = {
                buildJsonObject {
                    put("err", buildJsonObject { put("InstructionError", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(at)); add(buildJsonObject { put("Custom", 6002) }) }) })
                    put("logs", buildJsonArray {})
                }
            }
            val signer = MadeUpSigner()
            val t = settle(e.trader.buy(SolBuyRequest(mint(direct), BigInteger.valueOf(100_000_000), prefs, poolHints = hints(direct)), signer))
            val step = t.steps.single()
            assertEquals(StepStatus.FAILED, step.status)
            assertEquals("Price moved past your slippage. Raise slippage or try again.", step.error)
            assertEquals(0, signer.signed.get())
            assertTrue(e.sender.raws.isEmpty())
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun anExpiredBlockhashFailsTheTrade() = runBlocking {
        val e = env()
        try {
            e.call.status = {
                e.clock.advance(500)
                JsonNull
            }
            e.call.height = { e.call.lastValid + 1 }
            val t = settle(e.trader.buy(SolBuyRequest(mint(direct), BigInteger.valueOf(100_000_000), prefs, poolHints = hints(direct)), MadeUpSigner()))
            val step = t.steps.single()
            assertEquals(StepStatus.FAILED, step.status)
            assertEquals("Expired before it landed. Try a higher priority fee.", step.error)
            // Sent again while it waited, always the same transaction.
            assertEquals(1, e.sender.distinct().size)
            assertTrue(e.sender.raws.size > 1)
            assertTrue(e.landed.isEmpty())
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun aTransactionThatLandsWithAnErrorFails() = runBlocking {
        val e = env()
        try {
            val at = recordedSwapIndex(direct)
            val err = buildJsonObject { put("InstructionError", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(at)); add(buildJsonObject { put("Custom", 6002) }) }) }
            e.call.status = { confirmed(err) }
            e.call.transaction = { landedTx(fee, wallet, emptyMap(), err = err) }
            val t = settle(e.trader.buy(SolBuyRequest(mint(direct), BigInteger.valueOf(100_000_000), prefs, poolHints = hints(direct)), MadeUpSigner())) { it.swap.feeWei != null }
            val step = t.steps.single()
            assertEquals(StepStatus.FAILED, step.status)
            assertEquals("Price moved past your slippage. Raise slippage or try again.", step.error)
            assertEquals(BigInteger.valueOf(fee), step.feeWei)
            assertTrue(e.landed.isEmpty())
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun aSellCountsTheSolItPaid() = runBlocking {
        val e = env()
        try {
            e.call.status = { confirmed() }
            // 0.049 SOL in, after a fee the sell paid out of it.
            e.call.transaction = { landedTx(fee, wallet, emptyMap(), lamports = 10_000_000_000L to 10_000_000_000L + 49_000_000 - fee) }
            val t = settle(e.trader.sell(SolSellRequest(mint(direct), 5_000, prefs, poolHints = hints(direct)), MadeUpSigner()), ::filled)
            assertEquals(TradeStatus.DONE, t.status)
            val step = t.steps.single()
            assertEquals(BigInteger.valueOf(49_000_000), step.amountOut)
            val raw = e.sender.distinct().single()
            assertTrue(verifies(raw))
            assertEquals(recordedMessage(direct, "sell"), SolVectors.hex(raw.copyOfRange(65, raw.size)))
            assertEquals(1, e.landed.size)
        } finally {
            e.scope.cancel()
        }
    }

    @Test
    fun aCoinPricedInUsdcIsBoughtThroughSolInTwoTransactions() = runBlocking {
        val e = env()
        try {
            val usdc = 7_000_000L
            e.call.status = { confirmed() }
            e.call.transaction = { sig ->
                val sent = e.sender.distinct().map { Base58.encode(it.copyOfRange(1, 65)) }
                when (sent.indexOf(sig)) {
                    0 -> landedTx(fee, wallet, mapOf(SolAddr.USDC_MINT to usdc))
                    1 -> landedTx(fee, wallet, mapOf(mint(funded) to 999L))
                    else -> JsonNull
                }
            }
            // Once the first lands, the wallet holds the USDC it bought.
            e.call.overrides[SolAddr.associatedTokenAddress(wallet, SolAddr.USDC_MINT, SolAddr.TOKEN_PROGRAM)] =
                e.call.tokenAccount(SolAddr.USDC_MINT, wallet, BigInteger.valueOf(usdc), SolAddr.TOKEN_PROGRAM)
            val signer = MadeUpSigner()
            val t = settle(e.trader.buy(SolBuyRequest(mint(funded), BigInteger.valueOf(100_000_000), prefs, poolHints = hints(funded)), signer), ::filled)
            assertEquals(t.steps.toString(), TradeStatus.DONE, t.status)
            assertEquals(listOf(StepKind.SWAP, StepKind.FUND), t.steps.map { it.kind })
            assertTrue(t.steps.all { it.status == StepStatus.CONFIRMED })
            val sent = e.sender.distinct()
            assertEquals(2, sent.size)
            assertTrue(sent.all { verifies(it) })
            // SOL into USDC first: the extension's own first transaction, byte for byte.
            assertEquals(recordedMessage(funded, "buy"), SolVectors.hex(sent[0].copyOfRange(65, sent[0].size)))
            assertEquals(BigInteger.valueOf(999), t.steps[0].amountOut)
            assertEquals(2, signer.signed.get())
            assertEquals(1, e.landed.size)
        } finally {
            e.scope.cancel()
        }
    }
}
