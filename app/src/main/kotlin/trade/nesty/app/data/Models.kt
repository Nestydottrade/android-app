package trade.nesty.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// The answers of https://nesty.trade/api/v1/ (website/api/README.md). Dollars and whole tokens
// are numbers, raw amounts strings, times Unix ms except a candle's time (seconds). Every field
// the app does not need to have is optional, so a field the server adds or drops breaks nothing.

@Serializable
data class Links(val x: String? = null, val telegram: String? = null, val website: String? = null)

@Serializable
data class Tax(val buy: Double? = null, val sell: Double? = null)

@Serializable
data class CreatorFee(val paid: Double? = null, val symbol: String? = null, val paidUsd: Double? = null)

@Serializable
data class RowSignal(val size: Int = 0, val at: Long = 0)

/** A coin in one of the Pulse lanes. */
@Serializable
data class FeedRow(
    val address: String,
    val name: String? = null,
    val symbol: String? = null,
    val image: String? = null,
    val links: Links? = null,
    val creator: String? = null,
    val createdAt: Long? = null,
    val graduatedAt: Long? = null,
    val complete: Boolean = false,
    val move: String? = null,
    val changedAt: Long? = null,
    val pool: String? = null,
    val mcapUsd: Double? = null,
    val progress: Double? = null,
    val pair: String? = null,
    val venue: String? = null,
    val tax: Tax? = null,
    val creatorFee: CreatorFee? = null,
    val volume24: Double? = null,
    val signal: RowSignal? = null,
)

@Serializable
data class Feed(
    val feed: String = "",
    val fresh: List<FeedRow> = emptyList(),
    val near: List<FeedRow> = emptyList(),
    val graduated: List<FeedRow> = emptyList(),
)

@Serializable
data class Change(val m5: Double? = null, val h1: Double? = null, val h24: Double? = null)

/** A coin as /trending and /search list it. */
@Serializable
data class ListToken(
    val feed: String,
    val address: String,
    val name: String? = null,
    val symbol: String? = null,
    val image: String? = null,
    val priceUsd: Double? = null,
    val mcapUsd: Double? = null,
    val liquidityUsd: Double? = null,
    val volume24: Double? = null,
    val change: Change? = null,
    val pair: String? = null,
    val dex: String? = null,
    val createdAt: Long? = null,
)

/**
 * /trending and /search. In a search the listed coins come first, then the launchpad coins no
 * listing names; `partial` is true when the listings could not be asked and only those are in it.
 */
@Serializable
data class Tokens(val tokens: List<ListToken> = emptyList(), val partial: Boolean = false)

@Serializable
data class PoolRef(val address: String = "", val dex: String? = null)

@Serializable
data class ChartRef(val pool: String? = null, val sole: Boolean = false)

@Serializable
data class Quote(val symbol: String? = null, val address: String? = null, val usd: Double? = null)

/** A coin (/token, and the stream's `coin` event). */
@Serializable
data class Coin(
    val feed: String = "",
    val address: String = "",
    val name: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val supply: Double? = null,
    val image: String? = null,
    val links: Links? = null,
    val creator: String? = null,
    val createdAt: Long? = null,
    /** `launchpad`, `pool` or null. */
    val venue: String? = null,
    val launchpad: String? = null,
    val progress: Double? = null,
    val pool: PoolRef? = null,
    val chart: ChartRef? = null,
    val quote: Quote? = null,
    val priceUsd: Double? = null,
    val mcapUsd: Double? = null,
    val liquidityUsd: Double? = null,
    val volume5: Double? = null,
    val buys5: Int? = null,
    val sells5: Int? = null,
    val change: Change? = null,
    val tax: Tax? = null,
    val creatorFee: CreatorFee? = null,
) {
    val onCurve: Boolean get() = venue == "launchpad"
}

data class Candle(
    /** Unix seconds. */
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

@Serializable
data class CandlesAnswer(
    val interval: String = "",
    val supply: Double? = null,
    val candles: List<JsonArray> = emptyList(),
    val partial: Boolean = false,
    /**
     * The most it ever traded at, in dollars per token, where its whole life is known; null
     * otherwise. It can be above every candle sent, as the server keeps the high past `limit`.
     */
    val ath: Double? = null,
) {
    /** The candles that are whole: six numbers each. */
    fun parsed(): List<Candle> = candles.mapNotNull { row ->
        val n = row.map { (it as? JsonPrimitive)?.doubleOrNull }
        if (n.size < 6 || n.any { it == null }) return@mapNotNull null
        Candle(n[0]!!.toLong(), n[1]!!, n[2]!!, n[3]!!, n[4]!!, n[5]!!)
    }
}

/** A trade of a coin (/trades, and the stream's `trade` event). */
@Serializable
data class Trade(
    val id: String = "",
    val tx: String = "",
    val at: Long = 0,
    val side: String = "",
    val tokens: Double? = null,
    val quote: Double? = null,
    val price: Double? = null,
    val priceUsd: Double? = null,
    val mcapUsd: Double? = null,
    val wallet: String? = null,
    val tags: List<String> = emptyList(),
) {
    val isBuy: Boolean get() = side == "buy"
    val usd: Double? get() = if (tokens != null && priceUsd != null) tokens * priceUsd else null
}

@Serializable
data class TradesAnswer(
    val trades: List<Trade> = emptyList(),
    val reading: Boolean = false,
    /** Where the page before these starts, to ask for as it is; null once none older can be read. */
    val older: String? = null,
    /** One wallet's answer: every trade it made of the coin is among them. */
    val all: Boolean = false,
)

/** The stream's `candle` event: the newest candle of the stream's interval. */
@Serializable
data class StreamCandle(
    val interval: String = "",
    val time: Long = 0,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val close: Double = 0.0,
    val volume: Double = 0.0,
) {
    fun toCandle() = Candle(time, open, high, low, close, volume)
}

/** The stream's `market` event, from the latest trade. */
@Serializable
data class Market(val priceUsd: Double? = null, val mcapUsd: Double? = null, val at: Long? = null)

@Serializable
data class Holder(
    val wallet: String = "",
    val raw: String? = null,
    val share: Double = 0.0,
    /** `pool`, `burn` or null for a wallet. */
    val role: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class HoldersAnswer(
    val feed: String = "",
    val address: String = "",
    /** `complete`, `partial`, `reading` or `stale`. */
    val state: String = "",
    val holders: Int? = null,
    val supply: String? = null,
    val list: List<Holder> = emptyList(),
)

@Serializable
data class TraderRow(
    val wallet: String = "",
    val tags: List<String> = emptyList(),
    val buys: Int = 0,
    val sells: Int = 0,
    /** In whole units of the answer's quote. */
    val bought: Double = 0.0,
    val sold: Double = 0.0,
    val boughtTokens: Double = 0.0,
    val soldTokens: Double = 0.0,
    val firstAt: Long? = null,
    val lastAt: Long? = null,
    val remaining: Double? = null,
    val realized: Double = 0.0,
    /** Null without a price. */
    val unrealized: Double? = null,
    val pnl: Double = 0.0,
    /** The profit as a share of what it paid (1 is +100%). */
    val pnlPct: Double? = null,
)

@Serializable
data class TradersAnswer(
    val feed: String = "",
    val address: String = "",
    val quote: Quote? = null,
    val trades: Int? = null,
    val partial: Boolean = false,
    val top: List<TraderRow> = emptyList(),
    val early: List<TraderRow> = emptyList(),
    /** Null where the coin has no signal, on Solana, and from a server older than the field. */
    val signal: CoinSignal? = null,
)

/**
 * The coin's signal of the last day with the most wallets. `wallets` names them in the signal's
 * order; `rows` holds one for each of them that has a trade among those summed, in that order.
 */
@Serializable
data class CoinSignal(
    val size: Int = 0,
    val at: Long = 0,
    val first: Long = 0,
    val wallets: List<String> = emptyList(),
    val rows: List<TraderRow> = emptyList(),
)

@Serializable
data class TeamShare(val share: Double = 0.0, val count: Int = 0)

/** What a coin's holders hold, as shares of the supply; null where not known. */
@Serializable
data class Share(
    val feed: String = "",
    val address: String = "",
    val holders: Int? = null,
    val top10: Double? = null,
    val creator: Double? = null,
    val snipers: Double? = null,
    val bundlers: Double? = null,
    val team: TeamShare? = null,
)

@Serializable
data class SharesAnswer(val shares: List<Share> = emptyList())

@Serializable
data class SignalRow(
    val token: String = "",
    val symbol: String? = null,
    val pad: String? = null,
    val size: Int = 0,
    val at: Long = 0,
    val first: Long = 0,
    val launched: Long? = null,
    val wallets: List<String> = emptyList(),
    val usdThen: Double? = null,
    val usdNow: Double? = null,
    val nowAt: Long? = null,
    val supply: Double? = null,
    val early: Boolean = false,
)

@Serializable
data class ProvenWallet(
    val made: Double = 0.0,
    val sold: Int = 0,
    val wins: Int = 0,
    val coins: Int = 0,
    val holdMs: Long? = null,
)

@Serializable
data class ChainSignals(
    val chain: Long = 0,
    val at: Long = 0,
    val proven: Int = 0,
    val list: List<SignalRow> = emptyList(),
    val wallets: Map<String, ProvenWallet> = emptyMap(),
)

@Serializable
data class SignalsAnswer(val feed: String = "", val age: Long = 0, val v: Long = 0, val signals: ChainSignals = ChainSignals())

/** What a rule's signals did, each followed for a day. */
@Serializable
data class SignalFigures(
    val n: Int = 0,
    val x2: Int = 0,
    val x5: Int = 0,
    val up: Int = 0,
    val half: Int = 0,
    val median: Double? = null,
    val best: List<Int> = emptyList(),
)

@Serializable
data class RecordScope(
    val sizes: Map<String, SignalFigures> = emptyMap(),
    /** The yardstick: any 3 wallets buying a coin within 15 minutes. */
    val any: SignalFigures = SignalFigures(),
)

@Serializable
data class ChainRecord(
    val chain: Long = 0,
    val proven: Int = 0,
    val sizes: Map<String, SignalFigures> = emptyMap(),
    val any: SignalFigures = SignalFigures(),
)

@Serializable
data class SignalRecord(
    val at: Long = 0,
    val from: Long = 0,
    val to: Long = 0,
    val days: Int = 0,
    val chains: List<ChainRecord> = emptyList(),
    val all: RecordScope = RecordScope(),
)

@Serializable
data class RecordAnswer(val age: Long = 0, val record: SignalRecord = SignalRecord())

@Serializable
data class Best(val token: String = "", val symbol: String? = null, val x: Double = 0.0)

@Serializable
data class BoardRow(
    val wallet: String = "",
    val profit: Double = 0.0,
    val basis: Double = 0.0,
    val sold: Int = 0,
    val wins: Int = 0,
    val coins: Int = 0,
    val trades: Int = 0,
    val volume: Double = 0.0,
    val holdMs: Double? = null,
    val last: Long? = null,
    val best: Best? = null,
    /** `dev`, `bundler`, `sniper`, `flipper`, `holder` or null. */
    val tag: String? = null,
)

@Serializable
data class Board(
    val chain: Long = 0,
    val window: String = "",
    val at: Long = 0,
    val from: Long = 0,
    val since: Long = 0,
    val ranked: Int = 0,
    val rows: List<BoardRow> = emptyList(),
    /** Each ranking as places in `rows`, best first: profit, roi, winRate, volume. */
    val order: Map<String, List<Int>> = emptyMap(),
)

@Serializable
data class LeaderboardAnswer(val feed: String = "", val window: String = "", val age: Long = 0, val board: Board = Board())

@Serializable
data class PadStats(
    val trades: Long = 0,
    val buys: Long = 0,
    val volume: Double = 0.0,
    val bought: Double = 0.0,
    val traders: Long = 0,
    val coins: Long = 0,
    val launched: Long = 0,
)

@Serializable
data class ChainStats(
    val chain: Long = 0,
    val since: Long = 0,
    val trades: Long = 0,
    val buys: Long = 0,
    val volume: Double = 0.0,
    val bought: Double = 0.0,
    val traders: Long = 0,
    val coins: Long = 0,
    val launched: Long = 0,
    val unpriced: Long = 0,
    val checked: Long = 0,
    val devSold: Long = 0,
    val bundled: Long = 0,
    /** Oldest first: [trades, dollars] in each step. */
    val bars: List<List<Double>> = emptyList(),
    val pads: Map<String, PadStats> = emptyMap(),
)

@Serializable
data class StatsReport(
    val period: String = "",
    val at: Long = 0,
    val from: Long = 0,
    val step: Long = 0,
    val chains: List<ChainStats> = emptyList(),
)

@Serializable
data class StatsAnswer(val window: String = "", val age: Long = 0, val stats: StatsReport = StatsReport())

@Serializable
data class UpdateLink(val label: String = "", val href: String = "")

@Serializable
data class Update(
    /** YYYY-MM-DD. */
    val day: String = "",
    val tags: List<String> = emptyList(),
    /** Slugs; null when it is for every chain. */
    val chains: List<String>? = null,
    val title: String = "",
    val text: String = "",
    val links: List<UpdateLink>? = null,
)

@Serializable
data class UpdatesAnswer(val total: Int = 0, val updates: List<Update> = emptyList())

@Serializable
data class WalletTotals(
    val coins: Int = 0,
    val bought: Int = 0,
    val sold: Int = 0,
    val wins: Int = 0,
    val buys: Int = 0,
    val sells: Int = 0,
    val volume: Double = 0.0,
    val spent: Double = 0.0,
    val profit: Double = 0.0,
    val basis: Double = 0.0,
    val holdMs: Double? = null,
    val last: Long? = null,
    val tag: String? = null,
)

@Serializable
data class WalletCoin(
    val token: String = "",
    val buys: Int = 0,
    val sells: Int = 0,
    /** Dollars its buys paid, and its sales fetched. */
    val spent: Double = 0.0,
    val got: Double = 0.0,
    val profit: Double = 0.0,
    val basis: Double = 0.0,
    val holdMs: Double? = null,
    val last: Long = 0,
    val tokIn: Double = 0.0,
    val tokOut: Double = 0.0,
)

@Serializable
data class WalletHolding(
    val token: String = "",
    val held: Double = 0.0,
    /** In the coin's quote. */
    val cost: Double = 0.0,
    val opened: Long = 0,
    val first: Long = 0,
    val at: Long = 0,
)

@Serializable
data class WalletTrade(
    val at: Long = 0,
    val tx: String = "",
    val token: String = "",
    val isBuy: Boolean = false,
    val tokens: Double = 0.0,
    /** Whole units of the quote paid or got. */
    val quote: Double = 0.0,
    /** The quote's dollar price then. */
    val usd: Double? = null,
    val profit: Double? = null,
    val basis: Double? = null,
    val first: Boolean = false,
) {
    val dollars: Double? get() = usd?.let { quote * it }
}

@Serializable
data class CoinFacts(
    val symbol: String? = null,
    val pad: String? = null,
    /** The quote's address, '' for the chain's own coin. */
    val quote: String = "",
    val launched: Long? = null,
    val price: Double? = null,
    /** Dollars per whole token at its last trade. */
    val usd: Double? = null,
    val priceAt: Long? = null,
)

@Serializable
data class CreatedCoin(val token: String = "", val launched: Long? = null)

@Serializable
data class Created(val count: Int = 0, val coins: List<CreatedCoin> = emptyList())

@Serializable
data class WalletReport(
    val chain: Long = 0,
    val wallet: String = "",
    val window: String = "",
    val at: Long = 0,
    val from: Long = 0,
    val since: Long = 0,
    val totals: WalletTotals = WalletTotals(),
    val spread: List<Int> = emptyList(),
    val coins: List<WalletCoin> = emptyList(),
    val holdings: List<WalletHolding> = emptyList(),
    val trades: List<WalletTrade> = emptyList(),
    val created: Created = Created(),
    val book: Map<String, CoinFacts> = emptyMap(),
    val quotes: Map<String, Double> = emptyMap(),
)

@Serializable
data class WalletAnswer(val feed: String = "", val wallet: String = "", val book: Boolean = false, val report: WalletReport? = null)

@Serializable
data class PriceRow(val feed: String = "", val address: String = "", val priceUsd: Double? = null, val mcapUsd: Double? = null)

@Serializable
data class PricesAnswer(val prices: List<PriceRow> = emptyList())

/** One thing a release brings: a few words, and a line under them. */
@Serializable
data class ReleaseNote(val title: String = "", val text: String = "")

/** The newest APK (/app). The phone checks its size, hash and signer before Android installs it. */
@Serializable
data class AppRelease(
    val version: String = "",
    /** The APK's version code, always rising. */
    val code: Int = 0,
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val notes: List<ReleaseNote> = emptyList(),
)

/** One chain of /balances: the coin in its smallest unit, and what a whole one is worth. */
@Serializable
data class ChainBalance(
    val feed: String = "",
    /** Wei or lamports, as a decimal string. */
    val native: String = "0",
    /** Dollars for a whole coin; null when not priced. */
    val usd: Double? = null,
    /** True when this chain could not be read: its figure is then not a zero. */
    val failed: Boolean = false,
    /** The tokens the phone named, held on this chain, in their smallest unit. */
    val tokens: List<TokenRaw> = emptyList(),
)

@Serializable
data class TokenRaw(val address: String = "", val raw: String? = null)

@Serializable
data class BalancesAnswer(val address: String = "", val chains: List<ChainBalance> = emptyList(), val whole: Boolean = false)

/**
 * What a landed trade did for a wallet, from its receipt (/fill). `state` is `pending`, `failed`,
 * `other` (not a trade of this wallet) or `filled`; the rest is there once filled.
 */
@Serializable
data class FillAnswer(
    val state: String = "",
    val feed: String = "",
    val tx: String = "",
    /** When it landed, in milliseconds. */
    val at: Long? = null,
    val side: String? = null,
    val token: String? = null,
    /** Tokens in or out, in their smallest unit. */
    val raw: String? = null,
    val decimals: Int? = null,
    /** The chain's coin paid or received, raw. */
    val native: String? = null,
    val usd: Double? = null,
    val nativeUsd: Double? = null,
    val supply: Double? = null,
    val priceUsd: Double? = null,
    val mcapUsd: Double? = null,
    val late: Boolean = false,
)
