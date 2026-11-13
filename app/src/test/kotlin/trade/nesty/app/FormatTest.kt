package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Test
import trade.nesty.app.ui.Fmt

class FormatTest {
    @Test
    fun compactKeepsAboutThreeFigures() {
        assertEquals("0", Fmt.compact(0.0))
        assertEquals("0.42", Fmt.compact(0.42))
        assertEquals("4.20", Fmt.compact(4.2))
        assertEquals("820", Fmt.compact(820.0))
        assertEquals("9.8K", Fmt.compact(9_800.0))
        assertEquals("41.3K", Fmt.compact(41_300.0))
        assertEquals("412K", Fmt.compact(412_000.0))
        assertEquals("2.74M", Fmt.compact(2_740_000.0))
        assertEquals("27.4M", Fmt.compact(27_400_000.0))
        assertEquals("412M", Fmt.compact(412_000_000.0))
        assertEquals("1.20B", Fmt.compact(1_200_000_000.0))
    }

    @Test
    fun compactMovesUpAUnitAtTheEdges() {
        assertEquals("1.0K", Fmt.compact(999.6))
        assertEquals("100K", Fmt.compact(99_960.0))
        assertEquals("1.00M", Fmt.compact(999_600.0))
        assertEquals("10.0M", Fmt.compact(9_999_000.0))
        assertEquals("100M", Fmt.compact(99_990_000.0))
        assertEquals("1.00B", Fmt.compact(999_600_000.0))
    }

    @Test
    fun dollars() {
        assertEquals("$14.9K", Fmt.usd(14_900.0))
        assertEquals("${Fmt.MINUS}$34", Fmt.usd(-34.0))
        assertEquals(Fmt.DASH, Fmt.usd(null))
        assertEquals("$4.20", Fmt.usdFull(4.2))
        assertEquals("$1,059", Fmt.usdFull(1_059.4))
        assertEquals("+$734", Fmt.usdSigned(734.0))
        assertEquals("${Fmt.MINUS}$8.00", Fmt.usdSigned(-8.0))
        assertEquals("+$9,361", Fmt.usdSigned(9_361.0))
        assertEquals("+$16.4K", Fmt.usdSigned(16_361.0))
        assertEquals("+$41.3K", Fmt.usdSigned(41_300.0))
    }

    @Test
    fun percents() {
        assertEquals("+86%", Fmt.pctChange(86.0))
        assertEquals("${Fmt.MINUS}8%", Fmt.pctChange(-8.0))
        assertEquals("+0.3%", Fmt.pctChange(0.3))
        assertEquals("0%", Fmt.pctChange(0.01))
        assertEquals("0%", Fmt.share(0.0001))
        assertEquals("6.2%", Fmt.share(0.062))
        assertEquals("34%", Fmt.share(0.34))
        assertEquals("3%", Fmt.taxPct(0.03))
        assertEquals("0.5%", Fmt.taxPct(0.005))
        assertEquals("0.71×", Fmt.times(0.71))
        assertEquals("3.2×", Fmt.times(3.2))
        assertEquals("12×", Fmt.times(12.4))
    }

    @Test
    fun agesAreRelativeNeverClockTimes() {
        assertEquals("41s", Fmt.age(41_000))
        assertEquals("3m", Fmt.age(3 * 60_000L + 5_000))
        assertEquals("2h", Fmt.age(2 * 3_600_000L + 1))
        assertEquals("1d", Fmt.age(86_400_000L))
        assertEquals("3w", Fmt.age(21 * 86_400_000L))
        assertEquals("0s", Fmt.age(-5_000))
        assertEquals("6 minutes", Fmt.duration(6 * 60_000.0))
        assertEquals("1 hour", Fmt.duration(3_600_000.0))
        assertEquals("13 November", Fmt.day("2026-11-13"))
    }

    @Test
    fun countsTakeASingular() {
        assertEquals("1 holder", Fmt.counted(1, "holder"))
        assertEquals("0 holders", Fmt.counted(0, "holder"))
        assertEquals("1,421 holders", Fmt.counted(1_421, "holder"))
        assertEquals("12.4K trades", Fmt.counted(12_400, "trade"))
    }

    @Test
    fun addresses() {
        assertEquals("0x9a…1f07", Fmt.address("0x9a00000000000000000000000000000000001f07"))
        assertEquals("9xQe…Fh7k", Fmt.address("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusFh7k"))
    }
}
