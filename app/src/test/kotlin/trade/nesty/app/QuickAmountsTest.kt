package trade.nesty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Settings

class QuickAmountsTest {
    @Test
    fun fourDifferentAmountsFromOneDollarAreKept() {
        assertTrue(Settings.okAmounts(listOf(5, 20, 50, 250)))
        assertTrue(Settings.okAmounts(Settings.QUICK_STEPS))
        assertTrue(Settings.okAmounts(listOf(1, 2, 3, Settings.QUICK_MAX)))
    }

    @Test
    fun aRepeatAZeroOrAMissingAmountIsRefused() {
        assertFalse(Settings.okAmounts(listOf(10, 10, 50, 100)))
        assertFalse(Settings.okAmounts(listOf(0, 25, 50, 100)))
        assertFalse(Settings.okAmounts(listOf(10, 25, 50)))
        assertFalse(Settings.okAmounts(listOf(10, 25, 50, Settings.QUICK_MAX + 1)))
    }
}
