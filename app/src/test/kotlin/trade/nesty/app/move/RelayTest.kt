package trade.nesty.app.move

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import trade.nesty.app.Fixtures
import trade.nesty.app.evm.EvmChains
import trade.nesty.app.evm.EvmTestKit
import trade.nesty.app.evm.EvmVectors
import trade.nesty.app.evm.ValidationContext
import trade.nesty.app.evm.Validator
import trade.nesty.app.evm.arr
import trade.nesty.app.evm.big
import trade.nesty.app.evm.bool
import trade.nesty.app.evm.long
import trade.nesty.app.evm.obj
import trade.nesty.app.evm.opt
import trade.nesty.app.evm.s
import trade.nesty.app.evm.toHex
import trade.nesty.app.sol.RelayDepositContext
import trade.nesty.app.sol.SolSend
import trade.nesty.app.sol.SolTransferContext
import trade.nesty.app.sol.SolValidator
import trade.nesty.app.sol.SolVectors
import java.math.BigInteger

/**
 * The bridge's checks against what the extension's own code decided (android/tools/vectors/
 * bridge.test.ts): every quote, as sent and as answered, passed or refused reason for reason;
 * the status reads; the depository's rules in the validator; Relay's Solana deposit and the
 * validators of a deposit and a SOL send; and Solana's rent rule for a send. Nothing reaches a
 * network: each answer is given inside the client.
 */
class RelayTest {
    private val evm = EvmVectors.load("bridge")
    private val sol = SolVectors.load("bridge")

    /** A client that answers `body` with `status`, and keeps what it was sent. */
    private class Answering(status: Int, body: JsonElement) {
        var sent: Triple<String, String, String?>? = null
        private val text = body.toString()
        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            val b = r.body?.let { rb -> Buffer().also { rb.writeTo(it) }.readUtf8() }
            sent = Triple(r.url.encodedPath + (r.url.encodedQuery?.let { "?$it" } ?: ""), r.method, b)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val relay = HttpRelay(http)
    }

    private fun answering(o: JsonObject) = Answering(o.opt("status")?.jsonPrimitive?.content?.toInt() ?: 200, o.getValue("answer"))

    private fun ask(o: JsonObject, fromSolana: Boolean = false) = Relay.Ask(
        user = o.s("user"),
        recipient = o.s("recipient"),
        fromChainId = if (fromSolana) Relay.SOLANA_CHAIN_ID else o.getValue("fromChainId").long(),
        toChainId = o.getValue("toChainId").long(),
        amount = o.getValue("amount").big(),
        token = o.opt("token")?.jsonPrimitive?.content,
    )

    /** The request as the extension sent it: path, method, and the body's keys in their order. */
    private fun assertSent(name: String, expected: JsonElement?, sent: Triple<String, String, String?>?) {
        if (expected == null || expected is JsonNull) {
            assertNull("$name: nothing should be sent", sent)
            return
        }
        val e = expected.jsonObject
        checkNotNull(sent) { "$name: nothing was sent" }
        assertEquals("$name: path", e.s("path"), sent.first)
        assertEquals("$name: method", e.s("method"), sent.second)
        val body = e["body"]
        if (body == null || body is JsonNull) {
            assertNull("$name: body", sent.third)
        } else {
            val got = Json.parseToJsonElement(sent.third ?: "{}").jsonObject
            assertEquals("$name: body", body, got)
            assertEquals("$name: body order", body.jsonObject.keys.toList(), got.keys.toList())
        }
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull

    private fun assertTerms(name: String, r: JsonObject, t: Relay.Terms) {
        assertEquals("$name: amountIn", r.s("amountIn"), t.amountIn.toString())
        assertEquals("$name: amountOut", r.s("amountOut"), t.amountOut.toString())
        assertEquals("$name: minOut", r.s("minOut"), t.minOut.toString())
        val fee = num(r["feeUsd"])
        if (fee == null) assertNull("$name: feeUsd", t.feeUsd) else assertEquals("$name: feeUsd", fee, t.feeUsd!!, 0.0)
        val time = num(r["timeEstimateS"])
        if (time == null) assertNull("$name: time", t.timeEstimateS) else assertEquals("$name: time", time, t.timeEstimateS!!, 0.0)
        assertEquals("$name: requestId", r.s("requestId"), t.requestId)
        assertEquals("$name: statusPath", r.s("statusPath"), t.statusPath)
    }

    private fun assertTx(name: String, e: JsonObject, to: String, data: ByteArray, value: BigInteger) {
        assertEquals("$name: to", e.s("to").lowercase(), to.lowercase())
        assertEquals("$name: data", e.s("data").lowercase(), data.toHex())
        assertEquals("$name: value", e.getValue("value").big(), value)
    }

    private inline fun verdict(name: String, o: JsonObject, sent: () -> Triple<String, String, String?>?, run: () -> Unit) {
        val ok = o.getValue("ok").bool()
        try {
            run()
            if (!ok) fail("$name: should be refused with ${o.s("error")}")
        } catch (e: Relay.RelayError) {
            if (ok) throw AssertionError("$name: refused with ${e.message}", e)
            assertEquals("$name: reason", o.s("error"), e.message)
        }
        assertSent(name, o["sent"], sent())
    }

    @Test
    fun nativeQuotesAgreeWithTheExtension() = runBlocking {
        val cases = evm.arr("evm")
        assertEquals(39, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val name = o.s("name")
            val a = answering(o)
            verdict(name, o, { a.sent }) {
                val q = runBlocking { a.relay.quote(ask(o.obj("ask"))) }
                val r = o.obj("result")
                assertTerms(name, r, q.terms)
                assertTx(name, r.obj("tx"), q.tx.to, q.tx.data, q.tx.value)
            }
        }
    }

    @Test
    fun tokenQuotesAgreeWithTheExtension() = runBlocking {
        for (c in evm.arr("token")) {
            val o = c.jsonObject
            val name = o.s("name")
            val a = answering(o)
            verdict(name, o, { a.sent }) {
                val q = runBlocking { a.relay.quoteToken(ask(o.obj("ask"))) }
                val r = o.obj("result")
                assertTerms(name, r, q.terms)
                assertEquals("$name: token", r.s("token").lowercase(), q.token.lowercase())
                assertTx(name, r.obj("tx"), q.tx.to, q.tx.data, q.tx.value)
            }
        }
    }

    @Test
    fun statusReadsAgreeWithTheExtension() = runBlocking {
        for (c in evm.arr("status")) {
            val o = c.jsonObject
            val name = o.s("name")
            val a = answering(o)
            verdict(name, o, { a.sent }) {
                val s = runBlocking { a.relay.status(o.s("path"), o.getValue("toChainId").long()) }
                val r = o.obj("result")
                assertEquals("$name: status", r.s("status"), s.status)
                assertEquals("$name: outHash", r.opt("outHash")?.jsonPrimitive?.content, s.outHash)
            }
        }
    }

    @Test
    fun depositCalldataMatches() {
        val wallet = evm.s("wallet")
        val id = evm.s("depositId").removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(evm.obj("calldata").s("depositNative"), Relay.depositNative.encode(wallet, id).toHex())
        val usdc = "0xb47c4c8f73f009cc0c0d3f92b388d13e293f0b65"
        assertEquals(evm.obj("calldata").s("depositErc20"), Relay.depositErc20.encode(wallet, usdc, BigInteger.valueOf(12_345_678), id).toHex())
        assertEquals(evm.s("depository").lowercase(), Relay.DEPOSITORY)
    }

    @Test
    fun theDepositoryRulesAgreeWithTheExtension() {
        val cases = evm.arr("rules")
        assertEquals(19, cases.size)
        val usdc = "0xb47c4c8f73f009cc0c0d3f92b388d13e293f0b65"
        for (c in cases) {
            val o = c.jsonObject
            val chain = EvmChains.byId(o.getValue("chainId").long())!!
            val rule = if (o.s("rule") == "native") Relay.rule else Relay.tokenRule(usdc)
            val ctx = ValidationContext(
                chain = chain,
                from = evm.s("wallet").lowercase(),
                ownWallets = listOf(evm.s("wallet").lowercase(), evm.s("other").lowercase()),
                token = o.opt("token")?.jsonPrimitive?.content?.lowercase(),
                extraContracts = mapOf(Relay.DEPOSITORY to rule),
                maxApproval = o.opt("maxApproval")?.big(),
                maxValue = o.getValue("maxValue").big(),
                payee = o.opt("payee")?.jsonPrimitive?.content?.lowercase(),
                nowS = 1_790_000_000,
            )
            EvmTestKit.assertResult(o.s("name"), o, Validator.validateTx(EvmTestKit.tx(o.obj("tx")), ctx))
        }
    }

    // ── Solana ─────────────────────────────────────────────────────────────────────────

    @Test
    fun solanaDepositMatches() {
        assertEquals(sol.s("program"), RelaySol.PROGRAM)
        assertEquals(sol.s("state"), RelaySol.STATE)
        assertEquals(sol.s("vault"), RelaySol.VAULT)
        assertEquals(sol.s("discriminator"), SolVectors.hex(RelaySol.DEPOSIT_NATIVE))
        val d = sol.obj("deposit")
        val ix = RelaySol.depositNative(sol.s("wallet"), d.getValue("amount").big(), SolVectors.bytes(d.s("id")))
        val want = SolVectors.instruction(d.obj("instruction"))
        assertEquals(want.programId, ix.programId)
        assertEquals(want.keys, ix.keys)
        assertEquals(SolVectors.hex(want.data), SolVectors.hex(ix.data))
    }

    @Test
    fun solanaQuotesAgreeWithTheExtension() = runBlocking {
        for (c in sol.arr("quotes")) {
            val o = c.jsonObject
            val name = o.s("name")
            val a = answering(o)
            verdict(name, o, { a.sent }) {
                val q = runBlocking { a.relay.quoteFromSolana(ask(o.obj("ask"), fromSolana = true)) }
                val r = o.obj("result")
                assertTerms(name, r, q.terms)
                assertEquals("$name: depositId", r.s("depositId"), SolVectors.hex(q.depositId))
            }
        }
    }

    @Test
    fun solanaDepositValidatorAgrees() {
        for (c in sol.arr("deposits")) {
            val o = c.jsonObject
            val ctx = RelayDepositContext(
                wallet = sol.s("wallet"),
                amount = o.getValue("amount").big(),
                id = SolVectors.bytes(o.s("id")),
                maxSpend = o.getValue("maxSpend").big(),
                maxPriorityLamports = o.getValue("maxPriorityLamports").big(),
            )
            val r = SolValidator.validateRelayDeposit(SolVectors.instructions(o.getValue("instructions")), ctx)
            EvmTestKit.assertResult(o.s("name"), o, trade.nesty.app.evm.ValidationResult(r.ok, r.actions))
        }
    }

    @Test
    fun solanaSendValidatorAgrees() {
        for (c in sol.arr("transfers")) {
            val o = c.jsonObject
            val ctx = SolTransferContext(
                wallet = sol.s("wallet"),
                to = o.s("to"),
                asset = null,
                amount = o.getValue("amount").big(),
                maxPriorityLamports = o.getValue("maxPriorityLamports").big(),
            )
            val r = SolValidator.validateTransfer(SolVectors.instructions(o.getValue("instructions")), ctx)
            EvmTestKit.assertResult(o.s("name"), o, trade.nesty.app.evm.ValidationResult(r.ok, r.actions))
        }
    }

    @Test
    fun solanaRentRuleAgrees() {
        val cases = sol.arr("blocks")
        assertEquals(8, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val got = SolSend.sendBlock(o.getValue("lamports").big(), o.getValue("value").big(), o.getValue("fee").big(), o.getValue("minBalance").big())
            assertEquals(o.toString(), o.opt("block")?.jsonPrimitive?.content, got)
        }
    }

    // ── exact output: a buy's move, quoted by the bridge itself (a made-up wallet, never funded) ──

    private val exactOut: JsonElement by lazy { Json.parseToJsonElement(Fixtures.text("evm/relay-exact-out")) }
    private val probeWallet = "0x20f5b9d5839f1c0c93e0a758b97b5223ee4d48f8"
    private val out = BigInteger("20000000000000000")
    private val inAmount = BigInteger("6582973617896588")

    private fun exactAsk(amount: BigInteger = out, cap: BigInteger = inAmount) = Relay.Ask(probeWallet, probeWallet, 8453, 56, amount, cap)

    @Test
    fun exactOutputPassesUpToTheCap() {
        val q = Relay.checkQuote(Relay.parse(exactOut), exactAsk())
        assertEquals(inAmount, q.terms.amountIn)
        assertEquals(inAmount, q.tx.value)
        assertEquals(out, q.terms.amountOut)
        assertEquals(Relay.DEPOSITORY, q.tx.to.lowercase())
        assertEquals(0.000412 + 0.061467, q.terms.feeUsd!!, 0.0)
        assertTrue(Relay.quoteBody(exactAsk()).contains("\"tradeType\":\"EXACT_OUTPUT\""))
    }

    private fun refused(reason: String, block: () -> Unit) {
        try {
            block()
            fail("should be refused: $reason")
        } catch (e: Relay.RelayError) {
            assertEquals(reason, e.message)
        }
    }

    @Test
    fun exactOutputRefusesMoreThanConfirmedOrLessOut() {
        refused("Quote asks for more than you confirmed") { Relay.checkQuote(Relay.parse(exactOut), exactAsk(cap = inAmount - BigInteger.ONE)) }
        refused("Quote pays out less than asked") { Relay.checkQuote(Relay.parse(exactOut), exactAsk(amount = out + BigInteger.ONE)) }
        // The deposit's value must be what the quote says goes in.
        val tampered = Json.parseToJsonElement(exactOut.toString().replace("\"value\":\"6582973617896588\"", "\"value\":\"6582973617896589\""))
        refused("Quote value differs from the amount") { Relay.checkQuote(Relay.parse(tampered), exactAsk(cap = inAmount * BigInteger.TWO)) }
        // Credited to someone else: refused whatever the amounts.
        val stranger = Json.parseToJsonElement(exactOut.toString().replace("00000000000000000000000020f5b9d5839f1c0c93e0a758b97b5223ee4d48f8", "000000000000000000000000000000000000000000000000000000000000dead"))
        refused("Deposit is not credited to your wallet") { Relay.checkQuote(Relay.parse(stranger), exactAsk()) }
    }

    @Test
    fun exactInputBodyIsTheExtensionsAndOutputOnlyChangesTheType() {
        val a = Relay.Ask(probeWallet, probeWallet, 8453, 56, inAmount)
        val body = Json.parseToJsonElement(Relay.quoteBody(a)).jsonObject
        assertEquals("EXACT_INPUT", body.s("tradeType"))
        val b = Json.parseToJsonElement(Relay.quoteBody(a.copy(cap = inAmount))).jsonObject
        assertEquals(body.keys.toList(), b.keys.toList())
        assertEquals("EXACT_OUTPUT", b.s("tradeType"))
    }

    @Test
    fun solanaExactOutputReadsTheDepositAmount() {
        // The extension's good Solana quote, asked as exact output: what goes in is read from the deposit.
        val o = sol.arr("quotes")[0].jsonObject
        val a = ask(o.obj("ask"), fromSolana = true)
        val q = Relay.parse(o.getValue("answer"))
        // Its payout (4e16) is what is asked; the deposit's 123456789 lamports must fit the cap.
        val asked = a.copy(amount = BigInteger("40000000000000000"), cap = BigInteger.valueOf(123_456_789))
        assertEquals(BigInteger.valueOf(123_456_789), Relay.checkSolanaQuote(q, asked).terms.amountIn)
        refused("Quote asks for more than you confirmed") { Relay.checkSolanaQuote(q, asked.copy(cap = BigInteger.valueOf(123_456_788))) }
        assertTrue(o.getValue("answer").jsonObject.getValue("steps").jsonArray.isNotEmpty())
    }
}
