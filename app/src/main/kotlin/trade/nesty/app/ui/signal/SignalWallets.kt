package trade.nesty.app.ui.signal

import trade.nesty.app.i18n.trCount
import kotlin.math.abs
import kotlin.math.roundToLong
import trade.nesty.app.data.CoinSignal
import trade.nesty.app.data.Quote
import trade.nesty.app.data.SignalRow
import trade.nesty.app.data.TraderRow
import trade.nesty.app.data.TradersAnswer
import trade.nesty.app.i18n.tr
import trade.nesty.app.ui.Fmt

/** Which signal a sheet is about: how many wallets, when, and who, in the signal's order. */
data class SignalHead(val size: Int, val at: Long, val first: Long, val wallets: List<String>)

/** One of a signal's wallets, with what it did in the coin where a trade of it was summed. */
data class SignalWallet(val wallet: String, val row: TraderRow?)

/** A signal with its wallets' figures, as the sheet lists them. */
data class SignalView(
    val head: SignalHead,
    val wallets: List<SignalWallet>,
    val quote: Quote?,
    val partial: Boolean,
)

/** A figure and the small line under it. */
data class Figure(val value: String, val note: String? = null)

/**
 * A wallet's card, as it is written. A sign is -1, 0 or 1 and only picks the colour: the plus
 * or minus is in the figure, and `spoken` says profit or loss in words.
 */
data class WalletFigures(
    val bought: Figure,
    val sold: Figure,
    val realized: String,
    val realizedSign: Int,
    val unrealized: String,
    val unrealizedSign: Int,
    val holds: Figure,
    /** The whole card as one sentence, for a screen reader. */
    val spoken: String,
)

object SignalWallets {
    val NO_TRADES: String get() = tr("No trades read for this wallet")
    val PARTIAL: String get() = tr("Some of this coin's trades are not counted yet.")

    fun head(row: SignalRow) = SignalHead(row.size, row.at, row.first, row.wallets)

    fun head(signal: CoinSignal) = SignalHead(signal.size, signal.at, signal.first, signal.wallets)

    /**
     * The token page's signal: the trader answer's own where the server sends one, else the
     * largest in the chain's list. An older server has no such field, and a newer one leaves it
     * null where its list was not answered in time, so the list stays the way back.
     */
    fun coinHead(answer: TradersAnswer?, listed: SignalRow?): SignalHead? =
        answer?.signal?.let { head(it) } ?: listed?.let { head(it) }

    /**
     * Each wallet of the signal with its row: from the answer's `signal` where it has one, else
     * from the two trader tables. A wallet in neither has no trade among those summed.
     */
    fun view(head: SignalHead, answer: TradersAnswer): SignalView {
        val rows = HashMap<String, TraderRow>()
        // Later ones do not replace earlier ones: the signal's own rows first.
        for (r in answer.signal?.rows.orEmpty() + answer.top + answer.early) rows.putIfAbsent(r.wallet.lowercase(), r)
        val seen = HashSet<String>()
        val wallets = head.wallets.filter { it.isNotBlank() && seen.add(it.lowercase()) }.map { SignalWallet(it, rows[it.lowercase()]) }
        return SignalView(head, wallets, answer.quote, answer.partial)
    }

    /** "3 proven wallets bought KILN 3h ago": an age, never a clock time. */
    fun title(size: Int, symbol: String, at: Long, now: Long): String =
        tr("{1} proven wallets bought {2} {3} ago", size, symbol, Fmt.age(now - at))

    /**
     * A row's figures. Amounts are in the answer's quote: dollars where the quote has a price,
     * else the amount with the quote's symbol. The market cap at the average price needs the
     * price and the coin's supply, and is left out without either.
     */
    fun figures(row: TraderRow, quote: Quote?, supply: Double?): WalletFigures {
        val usd = quote?.usd?.takeIf { it > 0 && !it.isNaN() }
        val symbol = quote?.symbol?.takeIf { it.isNotBlank() }
        fun unit(amount: String) = if (symbol != null) "$amount $symbol" else amount
        fun money(q: Double): String = if (usd != null) Fmt.usdFull(q * usd) else unit(Fmt.amount(q))
        fun isZero(q: Double) = if (usd != null) abs(q * usd) < 0.005 else q == 0.0
        fun signed(q: Double): String = when {
            isZero(q) -> if (usd != null) "$0" else unit("0")
            usd != null -> Fmt.usdSigned(q * usd)
            else -> (if (q < 0) Fmt.MINUS else "+") + unit(Fmt.amount(abs(q)))
        }
        fun sign(q: Double) = if (isZero(q)) 0 else if (q < 0) -1 else 1
        fun mcap(paid: Double, tokens: Double): Double? =
            if (usd != null && supply != null && supply > 0 && tokens > 0 && paid > 0) paid / tokens * usd * supply else null
        fun side(paid: Double, tokens: Double, count: Int): Figure {
            if (count <= 0 && paid <= 0 && tokens <= 0) return Figure(Fmt.DASH)
            val note = listOfNotNull(
                if (tokens > 0) Fmt.compact(tokens) else null,
                if (count > 0) tr("{1} tx", count) else null,
                mcap(paid, tokens)?.let { "${Fmt.usd(it)} MC" },
            ).joinToString(" · ")
            return Figure(money(paid), note.ifEmpty { null })
        }
        fun said(verb: String, paid: Double, tokens: Double, count: Int): String? {
            if (count <= 0 && paid <= 0 && tokens <= 0) return null
            val parts = listOfNotNull(
                if (count > 0) trCount(count.toLong(), "in {1} trade", "in {1} trades", count) else null,
                mcap(paid, tokens)?.let { tr("at {1} market cap", Fmt.usd(it)) },
            ).joinToString(" ")
            return "$verb ${money(paid)}" + if (parts.isEmpty()) "" else " $parts"
        }

        val left = row.remaining
        val holdsSome = left != null && left >= 0.5
        val kept = if (left != null && row.boughtTokens > 0) (left / row.boughtTokens * 100).roundToLong() else null
        val holds = when {
            left == null -> Figure(Fmt.DASH)
            kept != null -> Figure(Fmt.count(left.roundToLong()), tr("{1}% of bought", kept))
            holdsSome -> Figure(Fmt.count(left.roundToLong()), tr("not bought here"))
            else -> Figure("0")
        }
        val open = row.unrealized?.takeIf { holdsSome }

        val spoken = listOfNotNull(
            said(tr("Bought"), row.bought, row.boughtTokens, row.buys) ?: tr("Did not buy here"),
            said(tr("Sold"), row.sold, row.soldTokens, row.sells) ?: tr("Sold nothing"),
            when (sign(row.realized)) {
                0 -> tr("Nothing realized")
                1 -> tr("Realized profit {1}", money(row.realized))
                else -> tr("Realized loss {1}", money(abs(row.realized)))
            },
            open?.let {
                when (sign(it)) {
                    0 -> tr("Unrealized nothing")
                    1 -> tr("Unrealized profit {1}", money(it))
                    else -> tr("Unrealized loss {1}", money(abs(it)))
                }
            },
            when {
                left == null -> null
                !holdsSome -> tr("Holds none")
                else -> tr("Holds {1} tokens", holds.value) + (holds.note?.let { ", $it" } ?: "")
            },
        ).joinToString(". ") + "."

        return WalletFigures(
            bought = side(row.bought, row.boughtTokens, row.buys),
            sold = side(row.sold, row.soldTokens, row.sells),
            realized = signed(row.realized),
            realizedSign = sign(row.realized),
            unrealized = open?.let { signed(it) } ?: Fmt.DASH,
            unrealizedSign = open?.let { sign(it) } ?: 0,
            holds = holds,
            spoken = spoken,
        )
    }
}
