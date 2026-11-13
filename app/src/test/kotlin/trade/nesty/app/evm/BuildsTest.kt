package trade.nesty.app.evm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import trade.nesty.app.evm.EvmTestKit.assertTx
import trade.nesty.app.evm.EvmTestKit.pool
import trade.nesty.app.evm.EvmTestKit.venue

/**
 * Every venue's buy, sell, Permit2 sell and prefunded sell, byte for byte as the TypeScript
 * engine built them in builds.json: four.meme, Flap, Pons, Virtuals, Montra, the Uniswap v4
 * launch pools (Stockereum, Klik, Clanker, Doppler, Flaunch, Zora, Pons graduated), Uniswap
 * v2/v3, PancakeSwap and Aerodrome. Coins and pools are made up; nothing reads a chain.
 */
class BuildsTest {
    private val v = EvmVectors.load("builds")

    @Test
    fun buildsLikeTheExtension() {
        val builds = v.arr("builds")
        assertEquals(36, builds.size)
        for (b in builds) {
            val o = b.jsonObject
            val name = o.s("name")
            val chainId = o.getValue("chainId").long()
            val p = pool(o.obj("pool"))
            val venue = venue(p.venueId)
            val ctx = EvmTestKit.venueCtx(chainId)
            assertEquals("$name: venue supports its chain", true, venue.supports(chainId))
            val buy = o.obj("buy")
            val sell = o.obj("sell")
            val buyParams = BuyParams(buy.s("wallet").lowercase(), buy.getValue("amountIn").big(), buy.getValue("minOut").big(), buy.getValue("deadline").big())
            val sellParams = SellParams(sell.s("wallet").lowercase(), sell.getValue("amountIn").big(), sell.getValue("minOut").big(), sell.getValue("deadline").big())
            assertTx("$name: buy", o.obj("buyTx"), venue.buildBuy(ctx, p, buyParams))
            assertTx("$name: sell", o.obj("sellTx"), venue.buildSell(ctx, p, sellParams))

            val approval = o.obj("approval")
            val got = venue.sellApproval(ctx, p)
            assertEquals("$name: spender", approval.s("spender").lowercase(), got.spender.lowercase())
            assertEquals("$name: via Permit2", approval.getValue("viaPermit2").bool(), got.viaPermit2)
            assertEquals("$name: router", approval.s("router").lowercase(), got.router.lowercase())

            o.opt("sellPermit")?.jsonObject?.let { sp ->
                val signed = sp.obj("permit")
                val permit = EvmTestKit.permitSingle(signed.obj("permit"))
                // The made-up key signs the permit as viem did.
                val sig = Permit2.single(chainId, permit).sign(EvmTestKit.madeUpKey)
                assertEquals("$name: permit signature", signed.s("signature").lowercase(), sig.toHex())
                assertTx("$name: sell with permit", sp.obj("tx"), venue.buildSell(ctx, p, sellParams.copy(permit = SignedPermit(permit, sig))))
            }

            val prefunded = venue.buildSellPrefunded(ctx, p, PrefundedParams(EvmTestKit.MADE_UP_WALLET, sell.getValue("minOut").big(), sell.getValue("deadline").big()))
            val expected = o["prefunded"]
            if (expected == null || expected is JsonNull) {
                assertNull("$name: no prefunded sell", prefunded)
            } else {
                assertTx("$name: prefunded sell", expected.jsonObject, checkNotNull(prefunded) { "$name: prefunded sell missing" })
            }
        }
    }
}
