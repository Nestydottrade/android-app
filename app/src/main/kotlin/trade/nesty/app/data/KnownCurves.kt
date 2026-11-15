package trade.nesty.app.data

import trade.nesty.app.evm.Flap
import trade.nesty.app.evm.FourMeme
import trade.nesty.app.evm.Montra
import trade.nesty.app.evm.Pons
import trade.nesty.app.evm.UniswapVenue

/**
 * The launchpad curves this build trades, by the names the server gives them (`launchpad` on a
 * coin: website/api/coin.ts PAD_NAMES, and pump.fun or Meteora on Solana). Reads follow the
 * server, so a coin on a launchpad added there later shows everywhere; trading it waits for an
 * APK whose engine knows that launchpad.
 */
object KnownCurves {
    private val EVM: Map<String, (Long) -> Boolean> = mapOf(
        "four.meme" to FourMeme::supports,
        "flap" to Flap::supports,
        "pons" to Pons::supports,
        "montra" to Montra::supports,
        "stockereum" to UniswapVenue::launchHooksOn,
    )

    /** pump.fun's curve, and Meteora's bonding curve (DBC), which the server names Meteora. */
    private val SOLANA = setOf("pump.fun", "meteora")

    /**
     * Whether this build can trade `coin` as the server describes it: off its curve it trades in
     * whatever pool this build knows; on a curve, only one this build knows. No coin read yet: yes.
     */
    fun tradable(chain: Chain, coin: Coin?): Boolean = coin == null || !coin.onCurve || knows(chain, coin.launchpad)

    /** Whether this build trades a coin on `launchpad`'s curve on `chain`. No name: the engine finds the curve itself. */
    fun knows(chain: Chain, launchpad: String?): Boolean {
        val name = launchpad?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        if (chain == Chain.SOLANA) return name in SOLANA
        val id = chain.feed.toLongOrNull() ?: return false
        return EVM[name]?.invoke(id) == true
    }
}
