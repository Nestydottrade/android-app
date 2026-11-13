package trade.nesty.app.evm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import trade.nesty.app.evm.EvmTestKit.key

/**
 * The arithmetic on every trade, as math.json has it: minimums after slippage, approval
 * targets, price impact, fees per chain and speed (and the speeds themselves), static gas,
 * expected fees, and v4 pool ids.
 */
class MathTest {
    private val v = EvmVectors.load("math")

    @Test
    fun minimumsAndApprovals() {
        for (e in v.arr("minOutAfter")) {
            val o = e.jsonObject
            assertEquals(o.toString(), o.getValue("min").big(), TradeMath.minOutAfter(o.getValue("out").big(), o.getValue("taxBps").int(), o.getValue("slippageBps").int()))
        }
        for (e in v.arr("approvalTarget")) {
            val o = e.jsonObject
            assertEquals(
                o.toString(),
                o.getValue("target").big(),
                TradeMath.approvalTarget(o.getValue("unlimited").bool(), o.getValue("bufferBps").int(), o.getValue("held").big()),
            )
        }
    }

    @Test
    fun priceImpact() {
        for (e in v.arr("priceImpact")) {
            val o = e.jsonObject
            val bps = o["bps"]?.takeIf { it !is JsonNull }?.int()
            assertEquals(
                o.toString(),
                bps,
                TradeMath.priceImpactBps(o.getValue("amountIn").big(), o.getValue("out").big(), o.getValue("refIn").big(), o.getValue("refOut").big()),
            )
        }
    }

    @Test
    fun feesPerChainAndSpeed() {
        for (e in v.arr("fees")) {
            val o = e.jsonObject
            val chain = EvmTestKit.chain(o.getValue("chainId").long())
            val preset = when (o.s("preset")) {
                "p1" -> chain.p1
                "p2" -> chain.p2
                "p3" -> chain.p3
                else -> error("preset ${o.s("preset")}")
            }
            // The speeds are the extension's.
            assertEquals("$o priority", o.s("priorityFeeGwei"), preset.priorityFeeGwei)
            assertEquals("$o multiplier", o.getValue("maxFeeMultiplier").str().toDouble(), preset.maxFeeMultiplier, 0.0)
            assertEquals("$o slippage", o.getValue("slippageBps").int(), preset.slippageBps)
            val fees = TradeMath.computeFees(chain, o.getValue("baseFee").big(), preset)
            assertEquals("$o max fee", o.getValue("maxFeePerGas").big(), fees.maxFeePerGas)
            assertEquals("$o tip", o.getValue("maxPriorityFeePerGas").big(), fees.maxPriorityFeePerGas)
        }
    }

    @Test
    fun gas() {
        for (e in v.arr("staticGas")) {
            val o = e.jsonObject
            assertEquals(o.toString(), o.getValue("gas").big(), TradeMath.staticGas(EvmTestKit.chain(o.getValue("chainId").long()), GasKind.of(o.s("kind"))))
        }
        for (e in v.arr("expectedFee")) {
            val o = e.jsonObject
            val fees = Fees(o.getValue("maxFeePerGas").big(), o.getValue("maxPriorityFeePerGas").big())
            val base = o["base"]?.takeIf { it !is JsonNull }?.big()
            assertEquals(
                o.toString(),
                o.getValue("fee").big(),
                TradeMath.expectedFeeWei(
                    EvmTestKit.chain(o.getValue("chainId").long()),
                    fees,
                    base,
                    o.getValue("gas").big(),
                    o.getValue("simulated").bool(),
                    GasKind.of(o.s("kind")),
                ),
            )
        }
    }

    @Test
    fun poolIds() {
        for (e in v.arr("poolIds")) {
            val o = e.jsonObject
            assertEquals(o.toString(), o.s("id").lowercase(), poolIdOf(key(o.obj("key"))))
        }
    }
}
