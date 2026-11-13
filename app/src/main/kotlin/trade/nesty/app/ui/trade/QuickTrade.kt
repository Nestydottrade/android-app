package trade.nesty.app.ui.trade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Coin
import trade.nesty.app.data.CoinInfo
import trade.nesty.app.data.Preset
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.Toasts

/** A preset's settings in words: "15% slippage, Fast, MEV protect". */
fun presetWords(chain: Chain, preset: Preset): String = listOfNotNull(
    "${preset.slippage}% slippage",
    preset.speed,
    "MEV protect".takeIf { AppGraph.trading.mevProtect(chain) },
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

    fun buy(toasts: Toasts, chain: Chain, token: String, usd: Double, symbol: String?, coin: Coin? = null) {
        val trading = AppGraph.trading
        trading.cannotTrade(chain)?.let {
            toasts.show("Buy not sent", it, bad = true)
            return
        }
        val key = "buy:${chain.feed}:${token.lowercase()}"
        if (!starting.add(key)) return
        val preset = AppGraph.settings.presets[0]
        val label = symbol?.takeIf { it.isNotBlank() } ?: coin?.symbol?.takeIf { it.isNotBlank() } ?: "this coin"
        toasts.show("Buying ${Fmt.usdFull(usd)} of $label", "P1: ${presetWords(chain, preset)}")
        scope.launch {
            try {
                val info = coin?.let { CoinInfo.of(it) } ?: coinInfo(chain, token, symbol)
                withContext(Dispatchers.Default) { trading.buy(chain, token, usd, preset, info) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toasts.show("Buy not sent", e.message ?: "Could not start.", bad = true)
            } finally {
                starting.remove(key)
            }
        }
    }

    /** Sells `bps` of the wallet's tokens. `doing` and `done` name it: "Selling 25% of your KILN". */
    fun sell(toasts: Toasts, chain: Chain, token: String, bps: Int, coin: Coin?, doing: String, done: String) {
        val trading = AppGraph.trading
        trading.cannotTrade(chain)?.let {
            toasts.show("Sell not sent", it, bad = true)
            return
        }
        val key = "sell:${chain.feed}:${token.lowercase()}"
        if (!starting.add(key)) return
        val preset = AppGraph.settings.presets[0]
        toasts.show(doing, "P1: ${presetWords(chain, preset)}")
        scope.launch {
            try {
                val info = coin?.let { CoinInfo.of(it) } ?: coinInfo(chain, token, null)
                withContext(Dispatchers.Default) { trading.sell(chain, token, bps, preset, info, done) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toasts.show("Sell not sent", e.message ?: "Could not start.", bad = true)
            } finally {
                starting.remove(key)
            }
        }
    }

    /** What the server knows of a coin: decimals, supply, pools. Only the coin's address is asked. */
    private suspend fun coinInfo(chain: Chain, token: String, symbol: String?): CoinInfo {
        val coin = try {
            withTimeoutOrNull(3_000) { AppGraph.api.token(chain, token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return coin?.let { CoinInfo.of(it) } ?: CoinInfo(symbol = symbol)
    }
}
