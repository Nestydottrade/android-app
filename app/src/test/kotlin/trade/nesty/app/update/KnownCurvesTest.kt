package trade.nesty.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.KnownCurves

class KnownCurvesTest {
    private fun onCurve(chain: Chain, pad: String?) = Coin(feed = chain.feed, address = "0x1", venue = "launchpad", launchpad = pad)
    private fun inPool(chain: Chain, pad: String?) = Coin(feed = chain.feed, address = "0x1", venue = "pool", launchpad = pad)

    @Test
    fun theCurvesThisBuildTradesByTheServersNames() {
        assertTrue(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, "four.meme")))
        assertTrue(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, "Flap")))
        assertTrue(KnownCurves.tradable(Chain.BASE, onCurve(Chain.BASE, "Flap")))
        assertTrue(KnownCurves.tradable(Chain.ROBINHOOD, onCurve(Chain.ROBINHOOD, "Pons")))
        assertTrue(KnownCurves.tradable(Chain.ELYSIUM, onCurve(Chain.ELYSIUM, "Montra")))
        assertTrue(KnownCurves.tradable(Chain.ETHEREUM, onCurve(Chain.ETHEREUM, "Stockereum")))
        assertTrue(KnownCurves.tradable(Chain.SOLANA, onCurve(Chain.SOLANA, "pump.fun")))
        assertTrue(KnownCurves.tradable(Chain.SOLANA, onCurve(Chain.SOLANA, "Meteora")))
    }

    @Test
    fun aCurveOnAChainItsLaunchpadIsNotBuiltForWaitsForAnUpdate() {
        assertFalse(KnownCurves.tradable(Chain.BASE, onCurve(Chain.BASE, "four.meme")))
        assertTrue("Flap is on Robinhood too", KnownCurves.tradable(Chain.ROBINHOOD, onCurve(Chain.ROBINHOOD, "Flap")))
        assertFalse(KnownCurves.tradable(Chain.ETHEREUM, onCurve(Chain.ETHEREUM, "Flap")))
        assertFalse(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, "Pons")))
        assertFalse(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, "Stockereum")))
        assertFalse(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, "Montra")))
    }

    @Test
    fun aLaunchpadAddedOnTheServerLaterWaitsForAnUpdateUntilItLeavesItsCurve() {
        assertFalse(KnownCurves.tradable(Chain.BASE, onCurve(Chain.BASE, "Clanker")))
        assertFalse(KnownCurves.tradable(Chain.SOLANA, onCurve(Chain.SOLANA, "LaunchLab")))
        // Off its curve it trades in whatever pool this build knows.
        assertTrue(KnownCurves.tradable(Chain.BASE, inPool(Chain.BASE, "Clanker")))
        assertTrue(KnownCurves.tradable(Chain.SOLANA, inPool(Chain.SOLANA, "LaunchLab")))
    }

    @Test
    fun noNameOrNoCoinLeavesItToTheEngine() {
        assertTrue(KnownCurves.tradable(Chain.BSC, null))
        assertTrue(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, null)))
        assertTrue(KnownCurves.tradable(Chain.BSC, onCurve(Chain.BSC, " ")))
    }
}
