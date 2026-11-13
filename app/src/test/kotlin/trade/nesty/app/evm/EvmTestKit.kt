package trade.nesty.app.evm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import trade.nesty.app.keys.Evm
import java.math.BigInteger

/**
 * Turns the TypeScript vectors back into the Kotlin engine's types. Addresses are kept
 * lowercase, as the engine keeps them.
 */
object EvmTestKit {
    /** The MADE-UP vector key (shared.ts): never funded, never used on a chain. */
    val madeUpKey: ByteArray = "4c1e9a7b".repeat(8).hexBytes()
    const val MADE_UP_WALLET = "0x11064ffa76d1c861e9c19b3d36f66572f16f9576"
    const val OTHER_WALLET = "0x00000000000000000000000000000000000be5a1"
    const val STRANGER = "0x000000000000000000000000000000000000dead"
    const val NOW_S = 1_794_614_400L

    /** Signs with the made-up key, for tests only. */
    class KeySigner(private val key: ByteArray = madeUpKey) : EvmSigner {
        override val address: String = Evm.address(key).lowercase()
        override fun signHash(hash: ByteArray): EcSignature = Secp256k1.sign(hash, key)
    }

    /** An RPC that answers nothing: builds and checks must not read the chain. */
    val noRpc = EvmRpc { _, method, _ -> throw AssertionError("unexpected read: $method") }

    fun chain(id: Long): EvmChain = EvmChains.byId(id) ?: error("no chain $id")

    fun venueCtx(chainId: Long, rpc: EvmRpc = noRpc): VenueCtx {
        val chain = chain(chainId)
        return VenueCtx(chain, ChainReader(chain, rpc))
    }

    private fun lc(e: JsonElement?): String? = e?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.lowercase()

    private fun req(o: JsonObject, k: String): String = lc(o[k]) ?: error("no $k in $o")

    fun key(o: JsonObject) = PoolKey(req(o, "currency0"), req(o, "currency1"), o.getValue("fee").int(), o.getValue("tickSpacing").int(), req(o, "hooks"))

    private fun via(o: JsonObject?): Via? = o?.let { Via(req(it, "stable"), req(it, "pool"), it.getValue("fee").int(), it.opt("v4Key")?.jsonObject?.let(::key)) }

    /** A pool as the TypeScript engine wrote it. */
    fun pool(o: JsonObject): PoolRef {
        val venueId = o.s("venueId")
        val d = o["data"]?.takeIf { it !is JsonNull }?.jsonObject
        val data: PoolData = when {
            d == null -> NoPoolData
            venueId == "fourmeme" -> FourPool(
                quote = lc(d["quote"]),
                leg = d.opt("leg")?.jsonObject?.let(::pool),
                feeBps = d.getValue("feeBps").int(),
                tax = d.opt("tax")?.jsonObject?.let { FourTax(it.getValue("buyBps").int(), it.getValue("sellBps").int()) },
            )
            venueId == "flap" -> FlapPool(req(d, "portal"), lc(d["quote"]), d.opt("symbol")?.str(), d.opt("leg")?.jsonObject?.let(::pool))
            venueId == "pons" -> PonsPool(req(d, "curve"), lc(d["quote"]), d.opt("symbol")?.str(), d.opt("leg")?.jsonObject?.let(::pool))
            venueId == "montra" -> MontraPool(key(d.obj("key")))
            venueId == "uniswap" -> when (d.s("kind")) {
                "v2" -> UniV2(req(d, "pair"), req(d, "quote"), req(d, "token0"), via(d.opt("via")?.jsonObject))
                "v3" -> UniV3(req(d, "pool"), req(d, "quote"), d.getValue("fee").int(), via(d.opt("via")?.jsonObject))
                "v4" -> UniV4(req(d, "poolId"), key(d.obj("key")), req(d, "quote"), via(d.opt("via")?.jsonObject))
                else -> error("kind ${d.s("kind")}")
            }
            venueId.startsWith("pancake-v2") || venueId.startsWith("aerodrome") -> V2RouterPool(req(d, "pair"), d.getValue("stable").bool(), lc(d["via"]))
            venueId.startsWith("pancake-v3") -> V3RouterPool(
                req(d, "pool"),
                d.getValue("fee").int(),
                d.opt("via")?.jsonObject?.let { V3RouterVia(req(it, "stable"), it.getValue("fee").int()) },
            )
            else -> error("venue $venueId")
        }
        return PoolRef(venueId, o.s("id").lowercase(), o.s("label"), req(o, "token"), data)
    }

    fun venue(id: String): Venue = EvmVenues.byId(id) ?: error("no venue $id")

    fun tx(o: JsonObject) = TxDraft(req(o, "to"), o.s("data").hexBytes(), o.getValue("value").big())

    fun assertTx(msg: String, expected: JsonObject, actual: TxDraft) {
        assertEquals("$msg: to", expected.s("to").lowercase(), actual.to.lowercase())
        assertEquals("$msg: data", expected.s("data").lowercase(), actual.data.toHex())
        assertEquals("$msg: value", expected.getValue("value").big(), actual.value)
    }

    /** The validator's context of a vector, as builds.test.ts made it (validationContext). */
    fun vctx(o: JsonObject): ValidationContext {
        val chain = chain(o.getValue("chainId").long())
        val pool = o.opt("pool")?.jsonObject?.let(::pool)
        val venue = pool?.let { venue(it.venueId) }
        return validationContext(
            chain = chain,
            from = req(o, "from"),
            own = o.arr("own").map { it.str().lowercase() },
            nowS = o.getValue("nowS").long(),
            venue = venue,
            ctx = VenueCtx(chain, ChainReader(chain, noRpc)),
            pool = pool,
            token = lc(o["token"]),
            fundingAsset = lc(o["fundingAsset"]),
            maxApproval = o.opt("maxApproval")?.big(),
            maxPermit = o.opt("maxPermit")?.big(),
            maxValue = o.getValue("maxValue").big(),
        ).copy(payee = lc(o["payee"]))
    }

    /** Labels name addresses as each side spells them: compared without case. */
    fun assertAction(msg: String, expected: JsonObject, actual: BlockedAction) {
        assertEquals("$msg: label", expected.s("label").lowercase(), actual.label.lowercase())
        assertEquals("$msg: ok (${expected.s("label")})", expected.getValue("ok").bool(), actual.ok)
        assertEquals("$msg: reason (${expected.s("label")})", expected.opt("reason")?.str()?.lowercase(), actual.reason?.lowercase())
    }

    fun assertResult(msg: String, expected: JsonObject, actual: ValidationResult) {
        val actions = expected.arr("actions").map { it.jsonObject }
        val got = actual.actions.joinToString("\n") { "  ${it.label} ${it.ok} ${it.reason ?: ""}" }
        assertEquals("$msg: ok\n$got", expected.getValue("ok").bool(), actual.ok)
        assertEquals("$msg: actions\n$got", actions.size, actual.actions.size)
        actions.forEachIndexed { i, a -> assertAction("$msg #$i", a, actual.actions[i]) }
    }

    fun permitSingle(o: JsonObject): PermitSingle {
        val d = o.obj("details")
        return PermitSingle(
            token = req(d, "token"),
            amount = d.getValue("amount").big(),
            expiration = d.getValue("expiration").long(),
            nonce = d.getValue("nonce").long(),
            spender = req(o, "spender"),
            sigDeadline = o.getValue("sigDeadline").big(),
        )
    }

    fun transferPermit(o: JsonObject): TransferPermit {
        val p = o.obj("permitted")
        return TransferPermit(req(p, "token"), p.getValue("amount").big(), o.getValue("nonce").big(), o.getValue("deadline").big())
    }

    fun assertCalls(msg: String, expected: JsonArray, actual: List<RouterCall>) {
        assertEquals("$msg: steps", expected.size, actual.size)
        expected.forEachIndexed { i, e ->
            val o = e.jsonObject
            val c = actual[i]
            assertEquals("$msg #$i target", o.s("target").lowercase(), c.target.lowercase())
            assertEquals("$msg #$i value", o.getValue("value").big(), c.value)
            assertEquals("$msg #$i data", o.s("data").lowercase(), c.data.toHex())
            assertEquals("$msg #$i injectToken", o.s("injectToken").lowercase(), c.injectToken.lowercase())
            assertEquals("$msg #$i injectOffset", o.getValue("injectOffset").big(), c.injectOffset)
            assertEquals("$msg #$i spender", o.s("spender").lowercase(), c.spender.lowercase())
        }
    }

    fun bigs(e: JsonElement?): List<BigInteger?>? =
        e?.takeIf { it !is JsonNull }?.jsonArray?.map { x -> x.takeIf { it !is JsonNull }?.let { BigInteger(it.jsonPrimitive.content) } }

    fun assertSameBigs(msg: String, expected: JsonElement?, actual: List<BigInteger?>?) {
        assertEquals(msg, bigs(expected), actual)
    }

    fun assertNoNullsIn(msg: String, list: List<*>) = assertTrue(msg, list.none { it == null })
}
