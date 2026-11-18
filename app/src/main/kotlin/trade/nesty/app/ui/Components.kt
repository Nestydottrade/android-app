package trade.nesty.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import trade.nesty.app.R
import trade.nesty.app.data.AppGraph
import trade.nesty.app.ui.alerts.BellButton
import trade.nesty.app.data.Chain
import trade.nesty.app.data.Served
import trade.nesty.app.ui.theme.Figures
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text

/** The time now, ticking while it is drawn: for ages, never for clock times. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

private val AVATAR_COLORS = listOf(
    Color(0xFFFFB38A), Color(0xFFFFB8D9), Color(0xFF7FE7C4), Color(0xFFC9B7FF),
    Color(0xFF8FD3FF), Color(0xFFF2A6A6), Color(0xFFB8C4FF), Color(0xFF9FE2E8),
)

fun avatarColor(seed: String): Color = AVATAR_COLORS[Math.floorMod(seed.lowercase().hashCode(), AVATAR_COLORS.size)]

/** An image a coin names, made loadable: plain http goes through the server's logo route. */
fun imageUrl(image: String?): String? {
    if (image.isNullOrBlank()) return null
    return when {
        image.startsWith("https://") -> image
        image.startsWith("http://") -> AppGraph.api.logoUrl(image)
        else -> null
    }
}

/** A coin's picture, or its first letter, with its chain's dot. */
@Composable
fun CoinAvatar(image: String?, symbol: String?, seed: String, chain: Chain?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(avatarColor(seed)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (symbol ?: "?").take(1).uppercase(),
                style = figure((size.value * 0.43f).toInt(), FontWeight.Bold, Ink.ground),
            )
            val url = imageUrl(image)
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (chain != null) {
            val dot = if (size >= 40.dp) 17.dp else 16.dp
            ChainDot(chain, dot, ring = 3.dp, modifier = Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp))
        }
    }
}

/**
 * A coin's picture: `image` when the screen has a usable one, else as [CoinImages] finds it once
 * the row is drawn, null until then. `served`: what the server's answer for the coin said, when
 * the screen has one.
 */
@Composable
fun rememberCoinImage(chain: Chain, token: String, image: String? = null, served: Served? = null): String? {
    if (imageUrl(image) != null) return image
    val images = AppGraph.images
    LaunchedEffect(chain, token, served) { images.ask(chain, token, served) }
    return images.of(chain, token)
}

@Composable
fun ChainDot(chain: Chain, size: Dp = 10.dp, ring: Dp = 0.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .then(if (ring > 0.dp) Modifier.border(ring, Ink.ground, CircleShape).padding(ring - 0.5.dp) else Modifier)
            .clip(CircleShape)
            .background(Ink.chain(chain)),
    )
}

@Composable
fun ToneMark(tone: Tone, size: Dp = 18.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tone.bg), contentAlignment = Alignment.Center) {
        Icon(tone.icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(size))
    }
}

/** A 48 dp round icon button. */
@Composable
fun IconButton48(icon: ImageVector, label: String, tint: Color = Ink.text2, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** The egg in its ring, which opens the menu. */
@Composable
fun EggButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "Open menu", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open menu" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Ink.raised).border(1.dp, Ink.line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.nesty_egg), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
        }
    }
}

/** A tab screen's top bar: the egg, a title, its buttons and the bell. */
@Composable
fun TabHeader(title: String, onMenu: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EggButton(onMenu)
        Text(
            title,
            style = text(21, FontWeight.Bold),
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
        BellButton()
    }
}

/** A pushed screen's top bar: back, a title and its buttons. */
@Composable
fun BackHeader(title: String, sub: String? = null, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton48(NIcons.arrowBack, "Back", Ink.text, onClick = onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = text(20, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (sub != null) Text(sub, style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Tabs with a violet bar under the one picked, sharing the width. */
@Composable
fun LaneTabs(labels: List<String>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(44.dp)) {
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onPick(i) }
                        .semantics { this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = text(14, FontWeight.Bold, if (on) Ink.text else Ink.muted), maxLines = 1)
                    if (on) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.64f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(Ink.violet),
                        )
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

/** A chip of a row of chips, violet when picked. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, height: Dp = 44.dp) {
    Row(
        Modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (on) Ink.violetBg else Ink.raised)
            .border(1.dp, if (on) Ink.violet else Ink.line2, RoundedCornerShape(height / 2))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        Text(label, style = text(14, FontWeight.SemiBold, if (on) Ink.text else Ink.text2), maxLines = 1)
    }
}

/** All, then each chain, as a row that scrolls sideways. */
@Composable
fun ChainChips(chains: List<Chain>, selected: Chain?, onPick: (Chain?) -> Unit, withAll: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (withAll) Chip("All", selected == null, { onPick(null) })
        for (c in chains) Chip(c.label, selected == c, { onPick(c) }, leading = { ChainDot(c, 10.dp) })
    }
}

/** A pill group with the picked one in white. */
@Composable
fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, minWidth: Dp = 46.dp) {
    PillGroup(modifier) {
        options.forEachIndexed { i, o -> Pill(o, i == selected, minWidth = minWidth) { onPick(i) } }
    }
}

/** The rounded ground of a pill group. */
@Composable
fun PillGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(Ink.raised), horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** One pill of a group, white while picked; [trailing] follows its label. */
@Composable
fun Pill(
    label: String?,
    on: Boolean,
    modifier: Modifier = Modifier,
    minWidth: Dp = 46.dp,
    role: Role = Role.Button,
    trailing: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ink = if (on) Ink.ground else Ink.text2
    Row(
        modifier
            .widthIn(min = minWidth)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) Ink.text else Color.Transparent)
            .clickable(role = role, onClick = onClick)
            .semantics { this.selected = on }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    ) {
        if (label != null) Text(label, style = text(14, FontWeight.Bold, ink), maxLines = 1)
        trailing?.invoke(ink)
    }
}

/** A rounded card with a line round it. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.card)
            .border(1.dp, Ink.line, RoundedCornerShape(18.dp)),
        content = content,
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Ink.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** A row in a card that opens something: its text, a note and a chevron. */
@Composable
fun NavRow(title: String, note: String? = null, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Text(title, style = text(15, FontWeight.Bold), modifier = Modifier.weight(1f))
        if (note != null) Text(note, style = text(13, color = Ink.muted), maxLines = 1)
        Icon(NIcons.chevronRight, contentDescription = null, tint = Ink.muted, modifier = Modifier.size(18.dp))
    }
}

/** A figure with its label above it. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Ink.text, size: Int = 17) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = text(12, color = Ink.muted), maxLines = 1)
        Text(value, style = figure(size, FontWeight.SemiBold, valueColor), maxLines = 1)
    }
}

/** A thin bar filled to a share of 0 to 1. */
@Composable
fun Meter(share: Double, modifier: Modifier = Modifier, color: Color = Ink.green, height: Dp = 6.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(Ink.track)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(share.coerceIn(0.0, 1.0).toFloat())
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

@Composable
fun LiveMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Ink.greenDeep), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.green))
        }
        Text("Live", style = text(13, FontWeight.Bold, Ink.greenSoft))
    }
}

/** A quiet line in the middle of an empty list or one that failed. */
@Composable
fun EmptyNote(message: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = text(14, color = Ink.muted, lineHeight = 20.sp), textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            GhostButton(action, onAction)
        }
    }
}

@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.raised)
            .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(15, FontWeight.Bold))
    }
}

/** The green action button, or its quiet form. */
@Composable
fun GreenButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false, height: Dp = 52.dp, fontSize: Int = 17) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(if (solid) Ink.green else Ink.greenDeep)
            .then(if (solid) Modifier else Modifier.border(1.dp, Ink.greenLine, RoundedCornerShape(14.dp)))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = text(fontSize, FontWeight.Bold, if (solid) Ink.greenInk else Ink.green), maxLines = 1)
    }
}

/**
 * A chip of a figure's tone, as the token page's checks at a glance. The compact one sits inside
 * a row that is a button itself; `does` says what a tap on it does, for a screen reader.
 */
@Composable
fun ToneChip(tone: Tone, label: String, compact: Boolean = false, does: String? = null, onClick: () -> Unit) {
    val (line, bg) = when (tone) {
        Tone.BAD -> Ink.pink.copy(alpha = 0.36f) to Ink.pink.copy(alpha = 0.08f)
        Tone.WARN -> Ink.amber.copy(alpha = 0.32f) to Ink.amber.copy(alpha = 0.08f)
        Tone.WALLETS -> Ink.violet.copy(alpha = 0.4f) to Ink.violetBg
        else -> Ink.greenLine to Ink.greenDeep
    }
    val height = if (compact) 28.dp else 40.dp
    Row(
        Modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(bg)
            .border(BorderStroke(1.dp, line), RoundedCornerShape(height / 2))
            .clickable(onClickLabel = does, role = Role.Button, onClick = onClick)
            .padding(start = if (compact) 6.dp else 8.dp, end = if (compact) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToneMark(tone, if (compact) 16.dp else 20.dp)
        Text(label, style = text(13, FontWeight.Bold), maxLines = 1)
    }
}

enum class Tab(val label: String, val icon: ImageVector, val route: Route) {
    DISCOVER("Discover", NIcons.compass, Route.Discover),
    SIGNALS("Signals", NIcons.pulse, Route.Smart()),
    TRACKER("Tracker", NIcons.eye, Route.Tracker),
    PORTFOLIO("Portfolio", NIcons.pie, Route.Portfolio);

    companion object {
        fun of(r: Route): Tab? = when (r) {
            Route.Discover -> DISCOVER
            is Route.Smart -> SIGNALS
            Route.Tracker -> TRACKER
            Route.Portfolio -> PORTFOLIO
            else -> null
        }
    }
}

@Composable
fun BottomNav(current: Tab?, onPick: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ink.card)) {
        Divider()
        Row(Modifier.fillMaxWidth().height(68.dp)) {
            for (t in Tab.entries) {
                val on = t == current
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onPick(t) }
                        .semantics { selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) Ink.violetBg else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(t.icon, contentDescription = null, tint = if (on) Ink.violet else Ink.muted, modifier = Modifier.size(22.dp))
                    }
                    Text(t.label, style = text(12, if (on) FontWeight.Bold else FontWeight.SemiBold, if (on) Ink.text else Ink.muted))
                }
            }
        }
    }
}

@Composable
fun ToastCard(t: ToastMessage, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(if (t.bad) Ink.pinkBg else Ink.greenDeep)
            .border(1.dp, if (t.bad) PINK_LINE else Ink.greenLine, shape)
            .padding(start = 16.dp, end = if (t.action != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (t.bad) NIcons.close else NIcons.doneCircle,
            contentDescription = null,
            tint = if (t.bad) Ink.pinkSoft else Ink.green,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.title, style = text(15, FontWeight.Bold))
            if (t.detail.isNotEmpty()) Text(t.detail, style = text(13, color = Ink.text2, lineHeight = 18.sp))
        }
        if (t.action != null && t.onAction != null) {
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = t.onAction)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.action, style = text(15, FontWeight.Bold, Ink.green))
            }
        }
    }
}

private val PINK_LINE = Color(0xFF4F2240)

/** Where a figure stands against zero: green up, pink down. */
/** A percent change that Fmt.pctChange writes as 0% is not green or pink. */
fun pctColor(p: Double?): Color = if (p != null && kotlin.math.abs(p) < 0.05) Ink.muted else signColor(p)

fun signColor(v: Double?): Color = when {
    v == null || v == 0.0 -> Ink.muted
    v < 0 -> Ink.pink
    else -> Ink.green
}

/** Trading venues by their own names; a venue may be named in the app, a data source may not. */
fun venueName(slug: String?): String? {
    if (slug.isNullOrBlank()) return null
    return when (slug.lowercase()) {
        "pancakeswap" -> "PancakeSwap"
        "uniswap" -> "Uniswap"
        "aerodrome", "aero" -> "Aerodrome"
        "raydium" -> "Raydium"
        "pumpswap", "pump-swap" -> "PumpSwap"
        "meteora" -> "Meteora"
        "orca" -> "Orca"
        "sushiswap" -> "SushiSwap"
        "baseswap" -> "BaseSwap"
        "meteoradbc" -> "Meteora DBC"
        "pumpfun", "pump.fun" -> "pump.fun"
        "fourmeme", "four.meme" -> "four.meme"
        "bags" -> "Bags"
        // The coin's page names its launchpad Flap; listings call the venue after its site, flap.sh.
        "flapsh", "flap.sh", "flap" -> "Flap"
        else -> slug.replaceFirstChar { it.uppercase() }
    }
}

/** A row that keeps its content above the system bars: tab screens leave the bottom to the nav bar. */
val ScreenPadding = PaddingValues(horizontal = 16.dp)

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun FigureText(value: String, size: Int, color: Color = Ink.text, weight: FontWeight = FontWeight.SemiBold, modifier: Modifier = Modifier) {
    Text(value, style = figure(size, weight, color).copy(fontFamily = Figures), modifier = modifier, maxLines = 1)
}

/**
 * A live list whose new rows arrive on top stays at its top while the person has not scrolled:
 * without this the list keeps the old first row in view and the new ones land above, out of sight.
 */
@Composable
fun StickToTop(state: LazyListState, firstKey: Any?) {
    remember(firstKey) {
        Snapshot.withoutReadObservation {
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) state.requestScrollToItem(0)
        }
        firstKey
    }
}
