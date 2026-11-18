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
    /** `buy`, `sell`, or `send`: tokens sent to an address, counted as taken out at their value then. */
    val side: String,
    val tx: String,
    /** When it landed, in milliseconds. */
    val at: Long,
    /** Tokens in or out, in their smallest unit. */
    val raw: String,
    val decimals: Int = 18,
    /** Dollars paid or received, without the network fee; for a send, what the tokens were worth. */
    val usd: Double? = null,
    /** The network fee in dollars: part of the cost of getting in or out. */
    val feeUsd: Double? = null,
    val mcapUsd: Double? = null,
    val exact: Boolean = false,
    /** How it was made, for its page in Activity; null on entries from before 0.1.2. */
    val detail: FillDetail? = null,
    /** Sent and did not land: kept for Activity, never counted in a position. */
    val failed: Boolean = false,
) {
    val chain: Chain? get() = Chain.of(feed)
    val amount: BigInteger get() = raw.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 } ?: BigInteger.ZERO
    val isBuy: Boolean get() = side == "buy"
    val isSend: Boolean get() = side == SEND
    val tokens: Double get() = BigDecimal(amount).movePointLeft(decimals).toDouble()

    companion object {
        const val SEND = "send"
    }
}

/**
 * A trade as it was made, kept with its fill for the trade's page: its route and settings, what
 * the quote said when it was sent, its prices then, its transactions and how long they took.
 */
@Serializable
data class FillDetail(
    /** The pool, as its venue names it: "Flap curve via FXIon". */
    val route: String? = null,
    /** The launchpad whose curve it traded on, when it did. */
    val launchpad: String? = null,
    val onCurve: Boolean = false,
    /** The preset it used (0 is P1), its slippage and speed. */
    val preset: Int? = null,
    val slippageBps: Int? = null,
    val speed: String? = null,
    /** Whether it went out through a private route, away from front-runners; null where the chain needs none. */
    val mev: Boolean? = null,
    /** The least it would take, in the smallest unit of what it gets. */
    val minOut: String? = null,
    /** Shares of its value: price impact, what the venue took itself, a tax taken on top. */
    val impact: Double? = null,
    val takeBps: Int? = null,
    val taxOutside: Double? = null,
    /** The coin's price and the chain coin's when it was sent. */
    val priceUsd: Double? = null,
    val nativeUsd: Double? = null,
    /** What it paid (a buy) or got (a sell), in the smallest unit of the chain's coin or its payout token. */
    val native: String? = null,
    /** A sell paid out in a token the coin is priced in, not the chain's coin. */
    val payout: String? = null,
    val payoutDecimals: Int? = null,
    /** The network fee and a Solana MEV tip, in the chain coin's smallest unit. */
    val feeRaw: String? = null,
    val tipRaw: String? = null,
    /** Funds moved to the chain first for this buy. */
    val move: FillMove? = null,
    /** Milliseconds from the tap to the network taking it, and from there to its confirmation. */
    val sentMs: Long? = null,
    val confirmMs: Long? = null,
    /** Every transaction it made, in order. */
    val txs: List<FillTx> = emptyList(),
    /** Why it did not land. */
    val error: String? = null,
    /** A sell an exit plan made. */
    val byPlan: Boolean = false,
)

/** Funds moved from another chain for a buy: what left, what arrived, what it cost and took. */
@Serializable
data class FillMove(val from: String, val sentUsd: Double? = null, val arrivedUsd: Double? = null, val feeUsd: Double? = null, val ms: Long? = null)

/** One transaction of a trade: `move`, `arrive`, `fund`, `buy`, `sell`, `approve` or `back`. */
@Serializable
data class FillTx(val kind: String, val feed: String, val hash: String, val ok: Boolean = true)

/** The fills of every wallet on this phone, newest first. Nothing here is sent anywhere. */
class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    var fills by mutableStateOf(read())
        private set

    private fun read(): List<Fill> =
        prefs.getString(KEY, null)?.let { runCatching { NestyJson.decodeFromString<List<Fill>>(it) }.getOrNull() } ?: emptyList()

    /**
     * One entry per transaction: the server's reading replaces the phone's, never the other way,
     * and keeps the phone's detail when it has none of its own.
     */
    @Synchronized
    fun put(fill: Fill) {
        val same = fills.firstOrNull { it.tx.equals(fill.tx, true) && it.wallet == fill.wallet }
        if (same != null && same.exact && !fill.exact) return
        val kept = if (fill.detail == null && same?.detail != null) fill.copy(detail = same.detail) else fill
        fills = (fills.filterNot { it === same } + kept).sortedByDescending { it.at }.take(MAX)
        prefs.edit().putString(KEY, NestyJson.encodeToString(fills)).apply()
    }

    /** Changes the entry of one transaction, whichever reading it holds; nothing when there is none. */
    @Synchronized
    fun update(wallet: String, tx: String, change: (Fill) -> Fill) {
        val same = fills.firstOrNull { it.tx.equals(tx, true) && it.wallet == wallet } ?: return
        fills = (fills.filterNot { it === same } + change(same)).sortedByDescending { it.at }.take(MAX)
        prefs.edit().putString(KEY, NestyJson.encodeToString(fills)).apply()
    }

    /** A wallet's landed trades: what its positions count. */
    fun of(wallet: String?): List<Fill> = activityOf(wallet).filter { !it.failed }

    /** And the trades it sent that did not land, for Activity. */
    fun activityOf(wallet: String?): List<Fill> = if (wallet == null) emptyList() else fills.filter { it.wallet.equals(wallet, true) }

    /** One entry by its transaction, for its page. */
    fun byTx(wallet: String, tx: String): Fill? = fills.firstOrNull { it.wallet.equals(wallet, true) && it.tx.equals(tx, true) }

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
    /** Dollars taken out: sells less their network fees, and sends at what the tokens were worth. */
    val taken: Double,
    /** The market cap the tokens were bought at, weighted by the tokens of each buy. */
    val entryMcap: Double?,
    val firstAt: Long,
    val lastAt: Long,
    /** Tokens sent to an address. */
    val sent: BigInteger = BigInteger.ZERO,
    /** The part of `taken` that was sent: what those tokens were worth, less their network fees. */
    val sentUsd: Double = 0.0,
    /**
     * Added to Portfolio by hand: the wallet got some of it from outside Nesty, at a cost not on
     * record, so it has no result.
     */
    val imported: Boolean = false,
) {
    /** What the fills say is still held, for when the chain's balance is not read. */
    val booked: BigInteger get() = (bought - sold - sent).max(BigInteger.ZERO)

    /** Bought or sold from this phone. */
    val traded: Boolean get() = bought.signum() > 0 || sold.signum() > 0

    companion object {
        /** A coin added to Portfolio that this phone never traded. */
        fun of(i: Imported, chain: Chain): Position =
            Position(chain, i.token, i.symbol, i.decimals, BigInteger.ZERO, BigInteger.ZERO, 0.0, 0.0, null, i.at, i.at, imported = true)
    }
}

/** A position with what the wallet holds and the coin's price now. */
data class Holding(val position: Position, val held: BigInteger, val priceUsd: Double?) {
    val tokens: Double get() = BigDecimal(held).movePointLeft(position.decimals).toDouble()
    val value: Double? get() = priceUsd?.let { tokens * it }

    /** Its cost is on record: not added by hand, and bought or sold from this phone, not only sent. */
    val known: Boolean get() = !position.imported && position.traded

    /** Null when not priced, and when its cost is not known. */
    val pnl: Double? get() = if (!known) null else value?.let { it + position.taken - position.paid }

    /** Still held: anything worth a cent or more (or not priced yet); any of a coin added by hand. */
    val open: Boolean get() = held.signum() > 0 && (position.imported || value == null || value!! >= 0.01)

    /** Dollars still to take out to have what was paid back, when it is up; null otherwise. */
    val initials: Double? get() {
        if (!known) return null
        val v = value ?: return null
        val back = position.paid - position.taken
        return if (open && back > 0.005 && v + position.taken > position.paid + 0.005) minOf(v, back) else null
    }
}

object Positions {
    fun of(fills: List<Fill>): List<Position> =
        fills.filter { !it.failed }.groupBy { it.feed to if (it.feed == Chain.SOLANA.feed) it.token else it.token.lowercase() }.mapNotNull { (key, list) ->
            val chain = Chain.of(key.first) ?: return@mapNotNull null
            val buys = list.filter { it.isBuy }
            val sells = list.filter { !it.isBuy && !it.isSend }
            val sends = list.filter { it.isSend }
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
                // A send leaves the result where it stood: its tokens count at what they were worth.
                taken = (sells + sends).sumOf { (it.usd ?: 0.0) - (it.feeUsd ?: 0.0) },
                entryMcap = if (weight > 0) priced.sumOf { it.mcapUsd!! * it.tokens } / weight else null,
                firstAt = list.minOf { it.at },
                lastAt = list.maxOf { it.at },
                sent = sends.fold(BigInteger.ZERO) { s, f -> s + f.amount },
                sentUsd = sends.sumOf { (it.usd ?: 0.0) - (it.feeUsd ?: 0.0) },
            )
        }.sortedByDescending { it.lastAt }

    private fun sameCoin(p: Position, i: Imported) = i.feed == p.chain.feed && Trading.sameToken(p.chain, i.token, p.token)

    /**
     * Each position with the wallet's balance (or what the fills say) and its price now, with the
     * coins added by hand (`imported`). Those show only while the wallet holds some; a coin that
     * was only sent from here shows only while it is added.
     */
    fun holdings(fills: List<Fill>, balance: WalletBalance?, imported: List<Imported> = emptyList(), price: (Chain, String) -> Double?): List<Holding> {
        val traded = of(fills)
        val marked = traded.map { p -> if (imported.any { sameCoin(p, it) }) p.copy(imported = true) else p }
        val added = imported.mapNotNull { i -> i.chain?.takeIf { traded.none { p -> sameCoin(p, i) } }?.let { Position.of(i, it) } }
        return (marked + added)
            .map { p -> Holding(p, balance?.token(p.chain, p.token) ?: p.booked, price(p.chain, p.token)) }
            .filter { h -> h.position.traded || (h.position.imported && h.held.signum() > 0) }
    }
}
