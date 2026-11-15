package trade.nesty.app.exit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Fill
import java.math.BigInteger

/** The extension's tests/exits.test.ts, ported, then what the phone adds: dollars with fees, the board's three steps. */
class ExitPlansTest {
    private fun tp(id: String, x: Int, sell: Int) = ExitRule(id, ExitKind.TP, x, sell)
    private fun big(n: Long) = BigInteger.valueOf(n)

    @Test fun refusesATakeProfitAtOrBelow1xAndAStopOf100() {
        assertFalse(tp("a", 10_000, 5_000).valid)
        assertFalse(ExitRule("b", ExitKind.SL, 10_000, 1).valid)
        assertTrue(tp("c", 20_000, 5_000).valid)
        assertFalse(tp("d", 20_000, 0).valid)
        assertFalse(tp("e", 20_000, 10_001).valid)
    }

    @Test fun valuesOnlyThePartOfTheHoldingWithACost() {
        // Held more than bought (a transfer in): the extra has no cost.
        assertEquals(CostBasis(big(100), 1_000.0), ExitMath.covered(big(150), CostBasis(big(100), 1_000.0)))
        // Sold half: what is left keeps the average.
        assertEquals(CostBasis(big(25), 250.0), ExitMath.covered(big(25), CostBasis(big(50), 500.0)))
        assertEquals(CostBasis.NONE, ExitMath.covered(big(40), CostBasis.NONE))
    }

    @Test fun measuresThePriceAsAMultipleOfTheAverageCost() {
        // 200 held pay $4 000; 100 of them cost $1 000: 2×.
        assertEquals(20_000, ExitMath.multipleBps(4_000.0, big(200), CostBasis(big(100), 1_000.0)))
        assertEquals(0, ExitMath.multipleBps(1.0, BigInteger.ZERO, CostBasis(big(100), 1_000.0)))
        assertEquals(0, ExitMath.multipleBps(1.0, big(1), CostBasis.NONE))
        // Floors, as the extension's integer division does.
        assertEquals(19_999, ExitMath.multipleBps(1_999.99, big(100), CostBasis(big(100), 1_000.0)))
    }

    @Test fun firesOneStepPerCheckStopsBeforeTakeProfits() {
        val rules = listOf(tp("a", 20_000, 5_000), tp("b", 30_000, 10_000), ExitRule("s", ExitKind.SL, 4_000, 10_000))
        assertNull(ExitMath.dueRule(rules, null, 15_000).rule)
        assertEquals("b", ExitMath.dueRule(rules, null, 35_000).rule?.id)
        assertEquals("a", ExitMath.dueRule(listOf(rules[1].copy(done = true), rules[0]), null, 35_000).rule?.id)
        assertEquals("s", ExitMath.dueRule(rules, null, 6_000).rule?.id)
        assertNull(ExitMath.dueRule(rules, null, 6_001).rule)
        assertEquals("a", ExitMath.dueRule(rules, null, 20_000).rule?.id)
    }

    @Test fun trailsFromTheHighestPriceSeen() {
        val rules = listOf(ExitRule("t", ExitKind.TRAIL, 2_500, 10_000))
        val up = ExitMath.dueRule(rules, null, 40_000)
        assertEquals(Due(null, 40_000), up)
        assertNull(ExitMath.dueRule(rules, up.peakBps, 31_000).rule)
        assertEquals("t", ExitMath.dueRule(rules, up.peakBps, 30_000).rule?.id)
        // The peak never goes down.
        assertEquals(40_000, ExitMath.dueRule(rules, 40_000, 35_000).peakBps)
    }

    @Test fun keepsFiredStepsFiredWhenThePlanIsSavedAgain() {
        val prev = listOf(tp("a", 20_000, 5_000).copy(done = true, firedAt = 1))
        val kept = ExitMath.mergeRules(prev, listOf(tp("a", 20_000, 5_000), tp("b", 30_000, 5_000)))
        assertTrue(kept[0].done)
        assertEquals(1L, kept[0].firedAt)
        assertFalse(kept[1].done)
        // Changing a step re-arms it.
        val changed = ExitMath.mergeRules(prev, listOf(tp("a", 25_000, 5_000)))[0]
        assertFalse(changed.done)
        assertNull(changed.firedAt)
    }

    @Test fun labelsSteps() {
        assertEquals("2.5× · sell 50%", ExitMath.ruleText(tp("a", 25_000, 5_000)))
        assertEquals("−40% · sell all", ExitMath.ruleText(ExitRule("s", ExitKind.SL, 4_000, 10_000)))
        assertEquals("−25% from peak · sell all", ExitMath.ruleText(ExitRule("t", ExitKind.TRAIL, 2_500, 10_000)))
        assertEquals("2.1×", ExitMath.times(21_000))
        assertEquals("10×", ExitMath.times(100_000))
    }

    private fun fill(side: String, raw: Long, usd: Double?, fee: Double? = null, at: Long) =
        Fill(wallet = "0xw", feed = Chain.BSC.feed, token = "0xt", side = side, tx = "0x$at", at = at, raw = raw.toString(), usd = usd, feeUsd = fee)

    @Test fun theCostHasTheFeesAndSellsTakeOutAtTheAverage() {
        val fills = listOf(
            // Given newest first, as the store keeps them.
            fill("sell", 150, 90.0, 0.5, at = 3),
            fill("buy", 100, 98.0, 2.0, at = 2),
            fill("buy", 100, 49.0, 1.0, at = 1),
        )
        // 200 tokens for $150 with fees; selling 150 takes $112.50 out at the average.
        val b = ExitMath.costBasis(fills)
        assertEquals(big(50), b.tokens)
        assertEquals(37.5, b.costUsd, 1e-9)
    }

    @Test fun aBuyWithoutADollarFigureHasNoCost() {
        val b = ExitMath.costBasis(listOf(fill("buy", 100, null, at = 1), fill("buy", 50, 10.0, at = 2)))
        assertEquals(big(50), b.tokens)
        assertEquals(10.0, b.costUsd, 1e-9)
        // A sale before any priced buy takes nothing.
        assertEquals(CostBasis.NONE, ExitMath.costBasis(listOf(fill("sell", 10, 5.0, at = 1))))
    }

    @Test fun theBoardsThreeStepsRoundTrip() {
        val d = ExitDraft(tpX = 5, tpPct = 25, trailOn = true, trailPct = 20, slPct = 70)
        val rules = d.rules()
        assertEquals(listOf("tp", "trail", "sl"), rules.map { it.id })
        assertEquals(ExitRule("tp", ExitKind.TP, 50_000, 2_500), rules[0])
        assertEquals(ExitRule("trail", ExitKind.TRAIL, 2_000, 10_000), rules[1])
        assertEquals(ExitRule("sl", ExitKind.SL, 7_000, 10_000), rules[2])
        assertTrue(rules.all { it.valid })
        assertEquals(d, ExitDraft.of(rules))
        // The trailing stop off: no step for it, and the suggestion keeps its own default.
        val off = ExitDraft(trailOn = false)
        assertEquals(listOf("tp", "sl"), off.rules().map { it.id })
        assertEquals(off, ExitDraft.of(off.rules()))
    }

    @Test fun whereAStepFires() {
        assertEquals(20_000, ExitMath.triggerAt(tp("a", 20_000, 5_000), null))
        assertEquals(5_000, ExitMath.triggerAt(ExitRule("s", ExitKind.SL, 5_000, 10_000), null))
        assertEquals(21_000, ExitMath.triggerAt(ExitRule("t", ExitKind.TRAIL, 3_000, 10_000), 30_000))
        assertNull(ExitMath.triggerAt(ExitRule("t", ExitKind.TRAIL, 3_000, 10_000), null))
    }

    @Test fun plansAreKeptPerWalletAndCoin() = runBlocking {
        val disk = MemoryDisk()
        val store = ExitStore(disk)
        val plan = ExitPlan("w1", "0xw", Chain.BSC.feed, "0xToken", "KILN", 18, ExitDraft().rules(), createdAt = 1)
        store.put(plan)
        // A Solana mint keeps its case; an EVM address does not matter.
        assertNotNull(store.of("w1", Chain.BSC.feed, "0xtoken"))
        assertNull(store.of("w2", Chain.BSC.feed, "0xtoken"))
        assertEquals(listOf(plan), ExitStore(disk).plans)
        store.patchRule(plan.key, "tp") { it.copy(done = true) }
        assertTrue(ExitStore(disk).plans.single().rules.first { it.id == "tp" }.done)
        store.remove(plan.key)
        assertTrue(ExitStore(disk).plans.isEmpty())
    }
}

class MemoryDisk : ExitDisk {
    var json: String? = null
    override fun read() = json
    override fun write(json: String) {
        this.json = json
    }
}
