package trade.nesty.app.exit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import trade.nesty.app.data.Chain
import trade.nesty.app.data.CoinInfo
import trade.nesty.app.data.Fill
import trade.nesty.app.data.NestyApi
import trade.nesty.app.data.PositionStore
import trade.nesty.app.data.Settings
import trade.nesty.app.data.TradeRefused
import trade.nesty.app.data.Trading
import trade.nesty.app.data.WalletSession
import trade.nesty.app.evm.StepStatus
import trade.nesty.app.evm.TradeStatus
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * The watcher's market on the phone: holdings and sell quotes read from each chain through the
 * engines, sells through them too, from the plan's own wallet. Only the coin's address goes to
 * Nesty's server (its pools and supply, as any coin page reads them).
 */
class EngineExitMarket(
    private val api: NestyApi,
    private val trading: Trading,
    private val session: WalletSession,
    private val positions: PositionStore,
    private val settings: Settings,
) : ExitMarket {
    private val infos = ConcurrentHashMap<String, Pair<Long, CoinInfo>>()
    private val mcaps = ConcurrentHashMap<String, Pair<Long, Double>>()

    override fun canSign(plan: ExitPlan): Boolean = session.canSignForWatch()

    override suspend fun held(plan: ExitPlan): BigInteger? {
        val chain = plan.chain ?: return null
        return trading.heldOf(chain, plan.token, plan.wallet)
    }

    override suspend fun sellValue(plan: ExitPlan, amount: BigInteger): SellValue {
        val chain = plan.chain ?: return SellValue.None("This chain is not traded here.")
        if (!trading.canTrade(chain)) return SellValue.None("This version of Nesty cannot trade on ${chain.title}.")
        val info = info(plan, chain)
        // A failed read throws: the watcher says the check failed and tries again.
        val usd = trading.sellValueUsd(chain, plan.token, amount, info)
        if (usd != null) return SellValue.Usd(usd)
        val why = try {
            trading.whyNone(chain, plan.token, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return SellValue.None(why ?: "No route to sell right now.")
    }

    override suspend fun sell(plan: ExitPlan, bps: Int): SellEnd {
        val chain = plan.chain ?: return SellEnd(false, error = "This chain is not traded here.")
        val info = info(plan, chain)
        val title = if (bps >= 10_000) "Exit plan sold your ${plan.label}" else "Exit plan sold ${bps / 100}% of your ${plan.label}"
        val flow = try {
            trading.sellForWatch(plan.walletId, plan.wallet, chain, plan.token, bps, settings.presets.first(), info, title, presetIndex = 0)
        } catch (e: TradeRefused) {
            return SellEnd(false, error = e.message)
        }
        // The engine ends every trade (landed, failed or dropped); the cap only guards against a hang.
        val end = withTimeoutOrNull(SELL_CAP_MS) { flow.first { it.status != TradeStatus.WORKING } }
            ?: return SellEnd(false, error = "No answer from the chain yet. Check your activity before selling again.", retry = false)
        if (end.status == TradeStatus.DONE) return SellEnd(true, usd = trading.proceedsUsd(end, chain, trading.nativeUsd(chain)))
        // Sent and not seen to fail: it may still land, so it is not sent again.
        val unsure = end.swap.hash != null && end.swap.status != StepStatus.FAILED
        return SellEnd(false, error = end.swap.error ?: "The sell did not go through.", retry = !unsure)
    }

    override fun fills(plan: ExitPlan): List<Fill> {
        val chain = plan.chain ?: return emptyList()
        return positions.of(plan.wallet).filter { it.feed == plan.feed && Trading.sameToken(chain, it.token, plan.token) }
    }

    /** The coin as its page reads it: pools to quote and its supply. Read again every ten minutes. */
    private suspend fun info(plan: ExitPlan, chain: Chain): CoinInfo {
        val now = System.currentTimeMillis()
        infos[plan.key]?.let { (at, info) -> if (now - at < INFO_TTL_MS) return info }
        val coin = try {
            api.token(chain, plan.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val known = infos[plan.key]?.second
        val info = coin?.let { CoinInfo.of(it) } ?: known ?: CoinInfo(symbol = plan.symbol.ifBlank { null }, decimals = plan.decimals)
        infos[plan.key] = now to info
        return info
    }

    /** The coin's market cap now, for what a note says; null when it cannot be read. */
    suspend fun mcapOf(plan: ExitPlan): Double? {
        val chain = plan.chain ?: return null
        val now = System.currentTimeMillis()
        mcaps[plan.key]?.let { (at, v) -> if (now - at < MCAP_TTL_MS) return v }
        val row = try {
            api.prices(listOf(chain to plan.token)).prices.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val v = row?.mcapUsd?.takeIf { it > 0 } ?: return mcaps[plan.key]?.second
        mcaps[plan.key] = now to v
        return v
    }

    private companion object {
        const val SELL_CAP_MS = 10 * 60_000L
        const val INFO_TTL_MS = 10 * 60_000L
        const val MCAP_TTL_MS = 20_000L
    }
}
