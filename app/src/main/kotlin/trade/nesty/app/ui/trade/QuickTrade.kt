package trade.nesty.app.ui.trade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import trade.nesty.app.alerts.AlertLink
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.CoinInfo
import trade.nesty.app.data.Preset
import trade.nesty.app.data.Trading
import trade.nesty.app.i18n.tr
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.Toasts

/** A preset's settings in words: "15% slippage, Fast, MEV protect". */
fun presetWords(chain: Chain, preset: Preset): String = listOfNotNull(
    tr("{1}% slippage", preset.slippage),
    tr(preset.speed),
    tr("MEV protect").takeIf { AppGraph.trading.mevProtect(chain) },
).joinToString(", ")

/**
 * Trades started with one tap: preset P1, paid from the chain's own balance. They run on past
 * the screen that started them; how each one ends comes as a note at the bottom of the screen.
 */
object QuickTrade {
    /** Outlives screens: a trade that has started is not stopped by leaving the page. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Coins with a trade still starting. A second tap on one waits for the first to go out. */
    private val starting = HashSet<String>()

    /** A trade that cannot start. A wallet without this chain's side gets the way to add it. */
    private fun refuse(toasts: Toasts, title: String, why: String) {
        if (why == Trading.NO_SOL || why == Trading.NO_EVM) {
            toasts.show(title, why, bad = true, action = tr("Add")) { AppGraph.pendingLink = AlertLink(AlertLink.WALLETS) }
        } else {
            toasts.show(title, why, bad = true)
        }
    }

    fun buy(toasts: Toasts, chain: Chain, token: String, usd: Double, symbol: String?, coin: Coin? = null) {
        val trading = AppGraph.trading
        trading.cannotTrade(chain)?.let {
            refuse(toasts, tr("Buy not sent"), it)
            return
        }
        val key = "buy:${chain.feed}:${token.lowercase()}"
        if (!starting.add(key)) return
        val preset = AppGraph.settings.presets[0]
        val label = symbol?.takeIf { it.isNotBlank() } ?: coin?.symbol?.takeIf { it.isNotBlank() } ?: tr("this coin")
        toasts.show(tr("Buying {1} of {2}", Fmt.usdFull(usd), label), "P1: ${presetWords(chain, preset)}")
        scope.launch {
            try {
                val read = coin ?: readCoin(chain, token)
                if (trading.needsUpdate(chain, read)) {
                    toasts.show(tr("Buy not sent"), NEEDS_UPDATE, bad = true, action = tr("Update")) { AppGraph.pendingLink = AlertLink(AlertLink.UPDATE) }
                    return@launch
                }
                val info = read?.let { CoinInfo.of(it) } ?: CoinInfo(symbol = symbol)
                withContext(Dispatchers.Default) { trading.buy(chain, token, usd, preset, info, presetIndex = 0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toasts.show(tr("Buy not sent"), e.message ?: tr("Could not start."), bad = true)
            } finally {
                starting.remove(key)
            }
        }
    }

    /** Sells `bps` of the wallet's tokens. `doing` and `done` name it: "Selling 25% of your KILN". */
    fun sell(toasts: Toasts, chain: Chain, token: String, bps: Int, coin: Coin?, doing: String, done: String) {
        val trading = AppGraph.trading
        trading.cannotTrade(chain)?.let {
            refuse(toasts, tr("Sell not sent"), it)
            return
        }
        val key = "sell:${chain.feed}:${token.lowercase()}"
        if (!starting.add(key)) return
        val preset = AppGraph.settings.presets[0]
        toasts.show(doing, "P1: ${presetWords(chain, preset)}")
        scope.launch {
            try {
                val info = coin?.let { CoinInfo.of(it) } ?: coinInfo(chain, token, null)
                withContext(Dispatchers.Default) { trading.sell(chain, token, bps, preset, info, done, presetIndex = 0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toasts.show(tr("Sell not sent"), e.message ?: tr("Could not start."), bad = true)
            } finally {
                starting.remove(key)
            }
        }
    }

    /** What the server knows of a coin: decimals, supply, pools, its launchpad. Only the coin's address is asked. */
    private suspend fun readCoin(chain: Chain, token: String): Coin? = try {
        withTimeoutOrNull(3_000) { AppGraph.api.token(chain, token) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** What a one-tap buy says of a coin this build cannot trade. */
    val NEEDS_UPDATE: String get() = tr("This version of Nesty cannot trade this coin yet.")

    /** What the server knows of a coin, for a trade. */
    private suspend fun coinInfo(chain: Chain, token: String, symbol: String?): CoinInfo =
        readCoin(chain, token)?.let { CoinInfo.of(it) } ?: CoinInfo(symbol = symbol)
}
