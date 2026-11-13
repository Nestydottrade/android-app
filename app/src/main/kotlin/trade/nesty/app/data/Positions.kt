package trade.nesty.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.math.BigInteger

/**
 * One landed trade of one of the user's wallets, as its position counts it. Kept on the phone
 * only. `exact` once the server read it from the receipt (/fill); until then the phone's own
 * figures from the receipt it watched.
 */
@Serializable
data class Fill(
    /** The wallet's address: lowercase on the EVM chains, as it is on Solana (case counts there). */
    val wallet: String,
    val feed: String,
    /** The coin's address, the same way. */
    val token: String,
    val symbol: String? = null,
    /** `buy` or `sell`. */
    val side: String,
    val tx: String,
    /** When it landed, in milliseconds. */
    val at: Long,
    /** Tokens in or out, in their smallest unit. */
    val raw: String,
    val decimals: Int = 18,
    /** Dollars paid or received, without the network fee. */
    val usd: Double? = null,
    /** The network fee in dollars: part of the cost of getting in or out. */
    val feeUsd: Double? = null,
    val mcapUsd: Double? = null,
    val exact: Boolean = false,
) {
    val chain: Chain? get() = Chain.of(feed)
    val amount: BigInteger get() = raw.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 } ?: BigInteger.ZERO
    val isBuy: Boolean get() = side == "buy"
    val tokens: Double get() = BigDecimal(amount).movePointLeft(decimals).toDouble()
}

/** The fills of every wallet on this phone, newest first. Nothing here is sent anywhere. */
class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    var fills by mutableStateOf(read())
        private set

    private fun read(): List<Fill> =
        prefs.getString(KEY, null)?.let { runCatching { NestyJson.decodeFromString<List<Fill>>(it) }.getOrNull() } ?: emptyList()

    /** One entry per transaction: the server's reading replaces the phone's, never the other way. */
    @Synchronized
    fun put(fill: Fill) {
        val same = fills.firstOrNull { it.tx.equals(fill.tx, true) && it.wallet == fill.wallet }
        if (same != null && same.exact && !fill.exact) return
        fills = (fills.filterNot { it === same } + fill).sortedByDescending { it.at }.take(MAX)
        prefs.edit().putString(KEY, NestyJson.encodeToString(fills)).apply()
    }

    fun of(wallet: String?): List<Fill> = if (wallet == null) emptyList() else fills.filter { it.wallet.equals(wallet, true) }

    /** The coins a wallet has traded, to name with its balance. */
    fun coinsOf(wallet: String?): List<Pair<Chain, String>> = of(wallet).mapNotNull { f -> f.chain?.let { it to f.token } }.distinct()

    companion object {
        private const val KEY = "fills"
        private const val MAX = 1_000
    }
}

/** What the fills of one coin add up to. */
data class Position(
    val chain: Chain,
    val token: String,
    val symbol: String?,
    val decimals: Int,
    val bought: BigInteger,
    val sold: BigInteger,
    /** Dollars put in: buys and their network fees. */
    val paid: Double,
    /** Dollars taken out: sells less their network fees. */
    val taken: Double,
    /** The market cap the tokens were bought at, weighted by the tokens of each buy. */
    val entryMcap: Double?,
    val firstAt: Long,
    val lastAt: Long,
) {
    /** What the fills say is still held, for when the chain's balance is not read. */
    val booked: BigInteger get() = (bought - sold).max(BigInteger.ZERO)
}

/** A position with what the wallet holds and the coin's price now. */
data class Holding(val position: Position, val held: BigInteger, val priceUsd: Double?) {
    val tokens: Double get() = BigDecimal(held).movePointLeft(position.decimals).toDouble()
    val value: Double? get() = priceUsd?.let { tokens * it }
    val pnl: Double? get() = value?.let { it + position.taken - position.paid }

    /** Still held: anything worth a cent or more (or not priced yet). */
    val open: Boolean get() = held.signum() > 0 && (value == null || value!! >= 0.01)

    /** Dollars still to take out to have what was paid back, when it is up; null otherwise. */
    val initials: Double? get() {
        val v = value ?: return null
        val back = position.paid - position.taken
        return if (open && back > 0.005 && v + position.taken > position.paid + 0.005) minOf(v, back) else null
    }
}

object Positions {
    fun of(fills: List<Fill>): List<Position> =
        fills.groupBy { it.feed to if (it.feed == Chain.SOLANA.feed) it.token else it.token.lowercase() }.mapNotNull { (key, list) ->
            val chain = Chain.of(key.first) ?: return@mapNotNull null
            val buys = list.filter { it.isBuy }
            val sells = list.filter { !it.isBuy }
            val bought = buys.fold(BigInteger.ZERO) { s, f -> s + f.amount }
            val priced = buys.filter { it.mcapUsd != null && it.amount.signum() > 0 }
            val weight = priced.sumOf { it.tokens }
            Position(
                chain = chain,
                token = key.second,
                symbol = list.firstNotNullOfOrNull { it.symbol },
                decimals = list.first().decimals,
                bought = bought,
                sold = sells.fold(BigInteger.ZERO) { s, f -> s + f.amount },
                paid = buys.sumOf { (it.usd ?: 0.0) + (it.feeUsd ?: 0.0) },
                taken = sells.sumOf { (it.usd ?: 0.0) - (it.feeUsd ?: 0.0) },
                entryMcap = if (weight > 0) priced.sumOf { it.mcapUsd!! * it.tokens } / weight else null,
                firstAt = list.minOf { it.at },
                lastAt = list.maxOf { it.at },
            )
        }.sortedByDescending { it.lastAt }

    /** Each position with the wallet's balance (or what the fills say) and its price now. */
    fun holdings(fills: List<Fill>, balance: WalletBalance?, price: (Chain, String) -> Double?): List<Holding> =
        of(fills).map { p -> Holding(p, balance?.token(p.chain, p.token) ?: p.booked, price(p.chain, p.token)) }
}
