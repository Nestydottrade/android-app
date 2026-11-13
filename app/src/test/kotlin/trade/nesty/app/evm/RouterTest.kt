package trade.nesty.app.evm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import trade.nesty.app.evm.EvmTestKit.assertAction
import trade.nesty.app.evm.EvmTestKit.assertCalls
import trade.nesty.app.evm.EvmTestKit.assertTx
import trade.nesty.app.evm.EvmTestKit.pool
import trade.nesty.app.evm.EvmTestKit.venue
import java.math.BigInteger

/**
 * Nesty Router trades (a curve quoted in an ERC-20, in one transaction): the steps, the
 * execute calldata, the one-time Permit2 transfer and its signature by the MADE-UP key, and
 * the checks on that transfer, as router.json has them for Flap (USDT, USDC), Pons (USDG,
 * v3 and v2 legs) and Virtuals.
 */
class RouterTest {
    private val v = EvmVectors.load("router")
    private val b = EvmVectors.load("builds")
    private val amounts = b.obj("amounts")
    private val deadline = b.getValue("deadline").big()

    @Test
    fun routerTradesMatchTheExtension() {
        val entries = v.arr("router").map { it.jsonObject }
        assertEquals(10, entries.size)
        var lastRoute: Pair<FundedRoute, TransferPermit>? = null
        for (o in entries) {
            val name = o.s("name")
            val chainId = o.getValue("chainId").long()
            val chain = EvmTestKit.chain(chainId)
            if (o["checks"] != null) {
                val (r, transfer) = checkNotNull(lastRoute) { "$name: no route before" }
                val ctx = { maxPermit: BigInteger -> fctx(r, maxPermit) }
                for (c in o.arr("checks")) {
                    val (case, expected) = c.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonObject }
                    val got = when (case) {
                        "ok" -> NestyRouter.checkTransferPermit(transfer, r.router, ctx(amounts.getValue("sellIn").big()))
                        "stranger spender" -> NestyRouter.checkTransferPermit(transfer, EvmTestKit.STRANGER, ctx(amounts.getValue("sellIn").big()))
                        "above the sale" -> NestyRouter.checkTransferPermit(transfer, r.router, ctx(BigInteger.ONE))
                        "other coin" -> NestyRouter.checkTransferPermit(transfer.copy(token = EvmTestKit.STRANGER, amount = BigInteger.ONE), r.router, ctx(amounts.getValue("sellIn").big()))
                        "too long" -> NestyRouter.checkTransferPermit(
                            transfer.copy(deadline = BigInteger.valueOf(EvmTestKit.NOW_S + 1801)),
                            r.router,
                            ctx(amounts.getValue("sellIn").big()),
                        )
                        else -> error("case $case")
                    }
                    assertAction("$name: $case", expected, got)
                }
                continue
            }
            val p = pool(o.obj("pool"))
            val venue = venue(p.venueId)
            val vctx = EvmTestKit.venueCtx(chainId)
            val funding = checkNotNull(venue.funding(vctx, p)) { "$name: not funded" }
            assertEquals("$name: asset", o.s("asset").lowercase(), funding.asset.lowercase())
            assertEquals("$name: leg", pool(o.obj("leg")), funding.leg)
            val legVenue = venue(funding.leg.venueId)
            val r = FundedRoute(vctx, chain.nestyRouter!!, EvmTestKit.MADE_UP_WALLET, venue, p, legVenue, funding.leg, funding.asset, deadline)

            val buyCalls = NestyRouter.buyCalls(r, amounts.getValue("buyIn").big(), BigInteger.valueOf(777), amounts.getValue("buyMin").big())
            assertCalls("$name: buy steps", o.arr("buyCalls"), buyCalls)
            val buyTx = TxDraft(
                r.router,
                NestyRouter.encodeExecute(buyCalls, null, p.token, amounts.getValue("buyMin").big(), listOf(funding.asset, chain.wrappedNative), deadline),
                amounts.getValue("buyIn").big(),
            )
            assertTx("$name: buy", o.obj("buyTx"), buyTx)

            val transfer = EvmTestKit.transferPermit(o.obj("transfer"))
            // The vector's nonce came from fixed bytes (i * 7 + 1): the same builder gives it.
            val built = Permit2.buildTransfer(p.token, amounts.getValue("sellIn").big(), EvmTestKit.NOW_S) { n -> ByteArray(n) { i -> ((i * 7 + 1) and 0xff).toByte() } }
            assertEquals("$name: transfer permit", transfer, built)
            val signature = Permit2.transfer(chainId, transfer, r.router).sign(EvmTestKit.madeUpKey)
            assertEquals("$name: transfer signature", o.s("signature").lowercase(), signature.toHex())

            val sellCalls = NestyRouter.sellCalls(r, BigInteger.valueOf(555), amounts.getValue("sellMin").big())
            val expectedSell = o["sellCalls"]
            if (expectedSell == null || expectedSell is JsonNull) {
                assertNull("$name: no way back", sellCalls)
            } else {
                assertCalls("$name: sell steps", expectedSell.jsonArray, checkNotNull(sellCalls))
                val sellTx = TxDraft(
                    r.router,
                    NestyRouter.encodeExecute(
                        sellCalls,
                        NestyRouter.SignedTransfer(transfer, signature),
                        NestyRouter.NATIVE,
                        amounts.getValue("sellMin").big(),
                        listOf(p.token, funding.asset, chain.wrappedNative),
                        deadline,
                    ),
                    BigInteger.ZERO,
                )
                assertTx("$name: sell", o.obj("sellTx"), sellTx)
            }
            lastRoute = r to transfer
        }
    }

    private fun fctx(r: FundedRoute, maxPermit: BigInteger) = validationContext(
        chain = r.ctx.chain,
        from = EvmTestKit.MADE_UP_WALLET,
        own = listOf(EvmTestKit.MADE_UP_WALLET, EvmTestKit.OTHER_WALLET),
        nowS = EvmTestKit.NOW_S,
        venue = r.venue,
        ctx = r.ctx,
        pool = r.pool,
        token = r.pool.token,
        fundingAsset = r.asset,
        maxPermit = maxPermit,
        maxValue = BigInteger.ZERO,
    )
}
