package trade.nesty.app.exit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Fill
import java.math.BigInteger

/** The watcher against a made-up market: what it sells, when, and what it says. */
class ExitWatcherTest {
    private class Market : ExitMarket {
        var held: BigInteger? = BigInteger.valueOf(1_000)
        var value: SellValue = SellValue.Usd(100.0)
        var signs = true
        var fails = 0
        val sells = mutableListOf<Int>()
        var fills = listOf(
            Fill(wallet = "0xw", feed = Chain.BSC.feed, token = "0xt", side = "buy", tx = "0x1", at = 1, raw = "1000", usd = 98.0, feeUsd = 2.0),
        )

        override fun canSign(plan: ExitPlan) = signs
        override suspend fun held(plan: ExitPlan) = held
        override suspend fun sellValue(plan: ExitPlan, amount: BigInteger) = value
        override suspend fun sell(plan: ExitPlan, bps: Int): SellEnd {
            sells += bps
            if (fails > 0) {
                fails -= 1
                return SellEnd(false, error = "Sell failed: price moved")
            }
            val got = (value as? SellValue.Usd)?.usd?.let { it * bps / 10_000 }
            held = held?.let { it - it * BigInteger.valueOf(bps.toLong()) / BigInteger.valueOf(10_000) }
            return SellEnd(true, got)
        }
        override fun fills(plan: ExitPlan) = fills
    }

    private val market = Market()
    private val store = ExitStore(MemoryDisk())
    private var clock = 1_000L
    private val watcher = ExitWatcher(store, market) { clock }
    private val plan = ExitPlan("w1", "0xw", Chain.BSC.feed, "0xt", "KILN", 18, ExitDraft(tpX = 2, tpPct = 50, slPct = 50).rules(), createdAt = 1)

    private fun rule(id: String) = store.get(plan.key)!!.rules.first { it.id == id }

    @Test fun sellsHalfAtTwiceTheCostOnce() = runTest {
        watcher.set(plan)
        val events = async(start = CoroutineStart.UNDISPATCHED) { watcher.events.take(1).toList() }
        watcher.check(store.get(plan.key)!!)
        assertEquals(10_000, watcher.statusOf(plan.key)?.nowBps)
        assertTrue(market.sells.isEmpty())

        // The $100 cost (fees in) is worth $200 now: 2×.
        market.value = SellValue.Usd(200.0)
        clock = 2_000
        watcher.check(store.get(plan.key)!!)
        assertEquals(listOf(5_000), market.sells)
        assertEquals(2_000L, rule("tp").firedAt)
        assertEquals(20_000, rule("tp").firedBps)
        val sold = events.await().single() as ExitEvent.Sold
        assertEquals(100.0, sold.usd!!, 1e-9)
        // Half the holding cost $50 of the $100.
        assertEquals(50.0, sold.gainUsd!!, 1e-9)

        // Still 2× on the half that is left: nothing more to sell.
        market.value = SellValue.Usd(100.0)
        watcher.check(store.get(plan.key)!!)
        assertEquals(listOf(5_000), market.sells)
    }

    @Test fun theStopLossSellsAllAndThePlanEnds() = runTest {
        watcher.set(plan)
        market.value = SellValue.Usd(50.0)
        watcher.check(store.get(plan.key)!!)
        assertEquals(listOf(10_000), market.sells)
        assertTrue(rule("sl").firedAt != null)
        // Nothing left: the plan is done.
        assertEquals(BigInteger.ZERO, market.held)
        watcher.check(store.get(plan.key)!!)
        assertNull(store.get(plan.key))
    }

    @Test fun anEmptyReadIsReadAgainBeforeThePlanEnds() = runTest {
        watcher.set(plan)
        market.held = BigInteger.ZERO
        watcher.check(store.get(plan.key)!!)
        assertEquals("You hold none of it now.", watcher.statusOf(plan.key)?.paused)
        watcher.check(store.get(plan.key)!!)
        assertNull(store.get(plan.key))
    }

    @Test fun aTrailingStopFollowsThePeakAcrossChecks() = runTest {
        watcher.set(plan.copy(rules = ExitDraft(tpX = 10, trailOn = true, trailPct = 30, slPct = 70).rules()))
        market.value = SellValue.Usd(400.0)
        watcher.check(store.get(plan.key)!!)
        assertEquals(40_000, store.get(plan.key)!!.peakBps)
        market.value = SellValue.Usd(290.0)
        watcher.check(store.get(plan.key)!!)
        assertTrue(market.sells.isEmpty())
        // 30% below the 4× peak.
        market.value = SellValue.Usd(280.0)
        watcher.check(store.get(plan.key)!!)
        assertEquals(listOf(10_000), market.sells)
        assertEquals(40_000, store.get(plan.key)?.peakBps)
        // Saving the plan again keeps the peak it reached.
        watcher.set(plan.copy(rules = ExitDraft(tpX = 5, trailOn = true, trailPct = 30, slPct = 70).rules()))
        assertEquals(40_000, store.get(plan.key)?.peakBps)
    }

    @Test fun aFailedSellIsTriedThreeTimesThenGivenUp() = runTest {
        watcher.set(plan)
        market.value = SellValue.Usd(200.0)
        market.fails = 5
        val events = async(start = CoroutineStart.UNDISPATCHED) { watcher.events.take(3).toList() }
        repeat(4) { watcher.check(store.get(plan.key)!!) }
        assertEquals(listOf(5_000, 5_000, 5_000), market.sells)
        val tp = rule("tp")
        assertTrue(tp.failed)
        assertEquals(3, tp.tries)
        assertEquals("Sell failed: price moved", tp.error)
        val failed = events.await().map { it as ExitEvent.Failed }
        assertEquals(listOf(false, false, true), failed.map { it.givenUp })
    }

    @Test fun whatIsDueWhileNothingCanSignIsSaidOnceAndWaits() = runTest {
        watcher.set(plan)
        market.signs = false
        market.value = SellValue.Usd(200.0)
        val events = async(start = CoroutineStart.UNDISPATCHED) { watcher.events.take(1).toList() }
        repeat(3) { watcher.check(store.get(plan.key)!!) }
        assertTrue(market.sells.isEmpty())
        assertEquals(ExitWatcher.LOCKED, watcher.statusOf(plan.key)?.paused)
        assertTrue(events.await().single() is ExitEvent.Waiting)
        assertFalse(rule("tp").done)
        // Unlocked again: it sells.
        market.signs = true
        watcher.check(store.get(plan.key)!!)
        assertEquals(listOf(5_000), market.sells)
    }

    @Test fun noCostOnRecordAndNoRouteOnlyPause() = runTest {
        watcher.set(plan)
        market.value = SellValue.None("No route to sell right now.")
        watcher.check(store.get(plan.key)!!)
        assertEquals("No route to sell right now.", watcher.statusOf(plan.key)?.paused)
        market.value = SellValue.Usd(500.0)
        market.fills = emptyList()
        watcher.check(store.get(plan.key)!!)
        assertEquals("No cost on record for what you hold.", watcher.statusOf(plan.key)?.paused)
        assertTrue(market.sells.isEmpty())
    }

    @Test fun aStepMarkedDoneWithoutAnAnswerSaysTheAppClosed() {
        val disk = MemoryDisk()
        ExitStore(disk).put(plan.copy(rules = plan.rules.map { if (it.id == "tp") it.copy(done = true) else it }))
        val again = ExitStore(disk)
        ExitWatcher(again, market)
        assertEquals(ExitWatcher.CLOSED, again.plans.single().rules.first { it.id == "tp" }.error)
    }

    @Test fun ticksCheckEachPlanOnceAtATime() = runTest {
        watcher.set(plan)
        watcher.set(plan.copy(token = "0xu", symbol = "WREN"))
        val job = launch { watcher.tick(this) }
        job.join()
        yield()
        assertEquals(2, watcher.status.size)
    }
}
