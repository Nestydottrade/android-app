package trade.nesty.app.ui.holders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import trade.nesty.app.data.AppGraph
import trade.nesty.app.data.Chain
import trade.nesty.app.data.KeyMove
import trade.nesty.app.data.KeysAnswer
import trade.nesty.app.data.Trade
import trade.nesty.app.i18n.tr
import trade.nesty.app.i18n.trKey
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Divider
import trade.nesty.app.ui.EmptyNote
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.Pill
import trade.nesty.app.ui.PillGroup
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.TagChip
import trade.nesty.app.ui.WalletName
import trade.nesty.app.ui.WalletTag
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.ui.token.Phase
import trade.nesty.app.ui.token.rememberTokenModel
import trade.nesty.app.ui.uniqueItems
import java.util.Locale

/** Which of a coin's key wallet moves the list shows. */
enum class KeyShow(private val key: String) {
    ALL(trKey("All")),
    SIDE(trKey("Creator & team")),
    TOP(trKey("Top 10")),
    BIG(trKey("Big buys"));

    /** Its name in the language the app shows. */
    val label: String get() = tr(key)
}

/**
 * The Key wallets list's rules, apart from drawing it, as the site's list has them
 * (website/src/components/token.tsx, KeyWallets): what each part and move is called, which moves
 * each choice shows, and the second line of a move.
 */
object KeyList {
    /** A balance under this share of the supply is none. */
    const val DUST_SHARE = 1e-7
    /** A buy of this share of the supply or more is a big buy, as the server marks them. */
    const val BIG_BUY = 0.01
    /** Moves the server lists at most, the newest. */
    const val MOVES_MAX = 300

    /** The creator's side: the creator, its team, the takers of its hand-offs and the wallets it sent tokens to. */
    private val SIDE = setOf("dev", "team", "handoff", "insider")

    fun roleWord(role: String): String = when (role) {
        "dev" -> tr("Creator")
        "team", "handoff" -> tr("Team")
        "insider" -> tr("Insider")
        "bundler" -> tr("Bundler")
        "sniper" -> tr("Sniper")
        "top" -> tr("Top 10")
        "big" -> tr("Big buy")
        else -> role.replaceFirstChar { it.uppercase() }
    }

    /** A role inside a sentence, after a wallet's name: "Pal, insider". */
    fun roleInLine(role: String): String = when (role) {
        "team", "handoff" -> tr("team")
        "insider" -> tr("insider")
        "bundler" -> tr("bundler")
        "sniper" -> tr("sniper")
        "top" -> tr("top 10")
        "big" -> tr("big buy")
        else -> role
    }

    fun roleTag(role: String): WalletTag = when (role) {
        "dev" -> WalletTag.CREATOR
        "team", "handoff" -> WalletTag.TEAM
        "insider" -> WalletTag.INSIDER
        "bundler" -> WalletTag.BUNDLER
        "sniper" -> WalletTag.SNIPER
        "top" -> WalletTag.TOP
        else -> WalletTag.BIG
    }

    /** A largest holder shows its place: Top 10 #3. */
    fun roleLabel(role: String, rank: Int?): String = if (role == "top" && rank != null) tr("Top 10 #{1}", rank) else roleWord(role)

    fun verbWord(verb: String): String = when (verb) {
        "bought" -> tr("Bought")
        "sold" -> tr("Sold")
        "sent" -> tr("Sent")
        "received" -> tr("Received")
        "burned" -> tr("Burned")
        else -> verb.replaceFirstChar { it.uppercase() }
    }

    fun isTrade(m: KeyMove): Boolean = m.verb == "bought" || m.verb == "sold"

    /** One key a move: a send has the same id on both its sides. */
    fun key(m: KeyMove): String = "${m.id}|${m.wallet}|${m.verb}"

    /** The moves a choice shows; `ranks` are the largest holders' places by lower-case address. */
    fun shown(moves: List<KeyMove>, show: KeyShow, ranks: Map<String, Int>): List<KeyMove> = when (show) {
        KeyShow.ALL -> moves
        KeyShow.SIDE -> moves.filter { it.role in SIDE }
        KeyShow.TOP -> moves.filter { ranks.containsKey(it.wallet.lowercase()) }
        KeyShow.BIG -> moves.filter { it.big }
    }

    /** The newest move of each wallet among `moves` (newest first), where it is a trade: it tells what the wallet holds now. */
    fun newestTrades(moves: List<KeyMove>): Set<String> {
        val seen = HashSet<String>()
        val out = HashSet<String>()
        for (m in moves) {
            if (!seen.add(m.wallet.lowercase())) continue
            if (isTrade(m)) out.add(key(m))
        }
        return out
    }

    /** Dollars: what a trade paid or got; what a send's tokens are worth now. Null without prices. */
    fun worthUsd(m: KeyMove, a: KeysAnswer): Double? {
        val usd = a.quote?.usd ?: return null
        val inQuote = m.quote ?: a.price?.let { m.tokens * it } ?: return null
        return inQuote * usd
    }

    /** A share of the supply, fine enough for small holders: 34%, 6.2%, 0.47%, <0.01%. */
    fun ofSupply(v: Double?): String = when {
        v == null || v.isNaN() -> Fmt.DASH
        v <= 0 -> "0%"
        v < 0.0001 -> "<0.01%"
        v < 0.01 -> String.format(Locale.US, "%.2f", v * 100).trimEnd('0').trimEnd('.') + "%"
        else -> Fmt.share(v)
    }

    /** What the wallet holds now, as a share of the supply; null where not read. */
    fun heldNote(held: Double?, supply: Double?): String? {
        if (held == null || supply == null || supply <= 0) return null
        val share = held / supply
        return if (share < DUST_SHARE) tr("holds none now") else tr("holds {1} now", ofSupply(share))
    }

    /**
     * A move's second line without its age: who a send went to or came from and what that wallet
     * is, else the market cap a trade came to and, on the wallet's newest trade, what it holds now.
     */
    fun detail(
        m: KeyMove,
        a: KeysAnswer,
        newest: Boolean,
        roles: Map<String, String>,
        held: Map<String, Double>,
        nameOf: (String) -> String?,
    ): String {
        fun who(w: String): String {
            val name = nameOf(w) ?: Fmt.address(w)
            val role = roles[w.lowercase()] ?: return name
            return if (role == "dev") tr("{1}, the creator", name) else tr("{1}, {2}", name, roleInLine(role))
        }
        val other = m.other
        return when {
            m.verb == "sent" && other != null -> tr("To {1}", who(other))
            m.verb == "received" && other != null -> tr("From {1}", who(other))
            m.verb == "burned" -> tr("To the burn address")
            else -> listOfNotNull(
                m.mcapUsd?.let { tr("at MC {1}", Fmt.usd(it)) },
                if (newest) heldNote(held[m.wallet.lowercase()], a.supply) else null,
            ).joinToString(" · ")
        }
    }

    /** Why a list shows no move. */
    fun emptyNote(a: KeysAnswer, show: KeyShow): String = when {
        a.moves.isNotEmpty() -> tr("None of these yet.")
        a.reading -> tr("Reading what the key wallets did…")
        a.wallets.isEmpty() && show == KeyShow.ALL -> tr("No creator, launch buyer or largest holder is known for this coin yet.")
        else -> tr("No moves from the creator, its team, the launch buyers or the largest holders yet, and no big buys.")
    }

    /**
     * On Solana the server keeps no such list: the trades read on the page make it, by their
     * launch tags, and every buy of a big share.
     */
    fun fromTrades(trades: List<Trade>, supply: Double?): List<KeyMove> = trades.mapNotNull { t ->
        val wallet = t.wallet ?: return@mapNotNull null
        val tokens = t.tokens ?: return@mapNotNull null
        val role = roleOfTags(t.tags)
        val big = t.isBuy && supply != null && supply > 0 && tokens / supply >= BIG_BUY
        if (role == null && !big) return@mapNotNull null
        KeyMove(
            id = t.id.ifEmpty { "${t.tx}:$wallet:${t.side}:$tokens" },
            tx = t.tx,
            at = t.at,
            verb = if (t.isBuy) "bought" else "sold",
            wallet = wallet,
            role = role ?: "big",
            tokens = tokens,
            quote = t.quote,
            big = big,
            mcapUsd = t.mcapUsd,
        )
    }.sortedByDescending { it.at }.take(MOVES_MAX)

    /** A trade's launch part, the closest to the creator first. */
    private fun roleOfTags(tags: List<String>): String? {
        val t = tags.map { it.lowercase() }
        return when {
            "creator" in t || "dev" in t -> "dev"
            "team" in t -> "team"
            "bundler" in t -> "bundler"
            "sniper" in t -> "sniper"
            else -> null
        }
    }
}

@Composable
fun KeyWalletsScreen(chain: Chain, address: String) {
    val model = rememberTokenModel(chain, address)
    val nav = LocalNavigator.current
    val solana = !chain.book
    // Only the coin goes to the server, which knows its key wallets itself.
    WhileShown { model.followKeys() }
    // Solana's list is made of the page's own trades, so they are read live here.
    if (solana) WhileShown { model.live(withChart = false) }
    var show by rememberSaveable { mutableStateOf(KeyShow.ALL) }
    val coin = model.coin
    val sub = listOfNotNull(coin?.symbol, chain.title, coin?.mcapUsd?.let { "MC ${Fmt.usd(it)}" }).joinToString(" · ")
    val fromTrades by remember(model) {
        derivedStateOf {
            val supply = model.supply ?: model.coin?.supply
            KeysAnswer(
                quote = model.coin?.quote ?: model.traders?.quote,
                supply = supply,
                moves = KeyList.fromTrades(model.trades.toList(), supply),
            )
        }
    }
    val answer = if (solana) fromTrades.takeIf { model.tradesPhase != Phase.LOADING } else model.keys
    val failed = if (solana) model.tradesPhase == Phase.FAILED else model.keysPhase == Phase.FAILED

    Column(Modifier.fillMaxSize()) {
        BackHeader(tr("Key wallets"), sub, onBack = { nav.pop() })
        // No largest holders are counted on Solana, so it has no Top 10 to show.
        val shows = if (solana) KeyShow.entries.filter { it != KeyShow.TOP } else KeyShow.entries
        PillGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            for (s in shows) Pill(s.label, s == show, minWidth = 56.dp) { show = s }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            KeyMovesList(answer, failed, show, solana) { w -> nav.push(Route.Wallet(chain, w)) }
        }
    }
}

@Composable
private fun KeyMovesList(a: KeysAnswer?, failed: Boolean, show: KeyShow, solana: Boolean, open: (String) -> Unit) {
    if (a == null) {
        EmptyNote(if (failed) tr("Could not read its key wallets. Trying again.") else tr("Reading its key wallets…"))
        return
    }
    val now = rememberNow()
    val ranks = remember(a) { a.wallets.mapNotNull { w -> w.rank?.let { w.wallet.lowercase() to it } }.toMap() }
    val roles = remember(a) { a.wallets.associate { it.wallet.lowercase() to it.role } }
    val held = remember(a) { a.wallets.mapNotNull { w -> w.held?.let { w.wallet.lowercase() to it } }.toMap() }
    val list = remember(a, show) { KeyList.shown(a.moves, show, ranks) }
    val newest = remember(list) { KeyList.newestTrades(list) }
    if (list.isEmpty()) {
        EmptyNote(KeyList.emptyNote(a, show))
        return
    }
    val settings = AppGraph.settings
    LazyColumn(Modifier.fillMaxSize()) {
        uniqueItems(list, { KeyList.key(it) }) { m ->
            val line = KeyList.detail(m, a, KeyList.key(m) in newest, roles, held) { settings.nameOf(it) }
            KeyMoveRow(m, ranks[m.wallet.lowercase()], a, line, now, open)
        }
        item(key = "end") {
            val note = when {
                solana -> tr("From the trades read on this page.")
                a.reading -> tr("Reading older moves…")
                a.partial -> tr("Some moves may be missing.")
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = text(12, color = Ink.muted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyMoveRow(m: KeyMove, rank: Int?, a: KeysAnswer, line: String, now: Long, open: (String) -> Unit) {
    val supply = a.supply?.takeIf { it > 0 }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button) { open(m.wallet) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.widthIn(min = 92.dp).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(KeyList.verbWord(m.verb), style = text(13, FontWeight.Bold, verbColor(m.verb)), maxLines = 1)
                Text(
                    supply?.let { KeyList.ofSupply(m.tokens / it) } ?: Fmt.compact(m.tokens),
                    style = figure(15, FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WalletName(m.wallet, modifier = Modifier.weight(1f, fill = false))
                    TagChip(KeyList.roleTag(m.role), KeyList.roleLabel(m.role, rank))
                }
                Text(
                    listOf(line, Fmt.age(now - m.at)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = text(12, color = Ink.muted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                KeyList.worthUsd(m, a)?.let { Fmt.usdFull(it) } ?: Fmt.DASH,
                style = figure(14, FontWeight.SemiBold, Ink.text2),
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp),
            )
        }
        Divider()
    }
}

private fun verbColor(verb: String): Color = when (verb) {
    "bought" -> Ink.green
    "sold" -> Ink.pink
    "burned" -> Ink.text2
    else -> Ink.violetSoft
}
