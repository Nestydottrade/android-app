package trade.nesty.app.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import trade.nesty.app.data.AppGraph
import trade.nesty.app.keys.Bip39
import trade.nesty.app.keys.Evm
import trade.nesty.app.keys.Sol
import trade.nesty.app.keys.wipe
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.LocalToasts
import trade.nesty.app.ui.NIcons
import trade.nesty.app.ui.SecretClipboard
import trade.nesty.app.ui.SecureWindow
import trade.nesty.app.ui.TextBox
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.WhileShown
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.vault.ExportKind
import trade.nesty.app.vault.Vault
import trade.nesty.app.vault.VaultException
import trade.nesty.app.vault.WalletInfo

/** Where a wallet lives, as the boards write it: both short addresses, or the one side it has. */
fun whereOf(w: WalletInfo): String = when {
    w.evm != null && w.sol != null -> "${Fmt.address(w.evm)} · ${Fmt.address(w.sol)}"
    w.evm != null -> "${Fmt.address(w.evm)} · EVM only"
    w.sol != null -> "${Fmt.address(w.sol)} · Solana only"
    else -> ""
}

/** What a typed or pasted secret is, for the line under the import box. */
enum class SecretLooks { EMPTY, PHRASE, EVM_KEY, SOL_KEY, SHORT_PHRASE, BAD_PHRASE, BAD_KEY }

fun looksLike(secret: String, phrase: Boolean): SecretLooks {
    if (secret.isBlank()) return SecretLooks.EMPTY
    if (phrase) {
        val words = Bip39.wordCount(secret)
        return when {
            Bip39.isValid(secret) -> SecretLooks.PHRASE
            words == 12 || words == 24 -> SecretLooks.BAD_PHRASE
            else -> SecretLooks.SHORT_PHRASE
        }
    }
    Evm.parsePrivateKey(secret)?.let {
        it.wipe()
        return SecretLooks.EVM_KEY
    }
    Sol.parseSecretKey(secret)?.let {
        it.wipe()
        return SecretLooks.SOL_KEY
    }
    return SecretLooks.BAD_KEY
}

fun secretNote(looks: SecretLooks, phrase: Boolean, words: Int): String = when (looks) {
    SecretLooks.EMPTY -> if (phrase) "12 or 24 words, with a space between each" else "An EVM key or a Solana key"
    SecretLooks.PHRASE -> "$words words. It gets its EVM address and its Solana address."
    SecretLooks.EVM_KEY -> "EVM key. This wallet will trade on the EVM chains only: the key has no Solana address."
    SecretLooks.SOL_KEY -> "Solana key. This wallet will trade on Solana only: the key has no EVM address."
    SecretLooks.SHORT_PHRASE -> "${Fmt.counted(words.toLong(), "word")} so far. A phrase has 12 or 24."
    SecretLooks.BAD_PHRASE -> "These words do not make a recovery phrase. Check each one."
    SecretLooks.BAD_KEY -> "This is not an EVM or Solana key"
}

@Composable
fun WalletsScreen(startAdding: Boolean) {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val session = AppGraph.session
    val balances = AppGraph.balances
    val scope = rememberCoroutineScope()
    val wallets = session.wallets
    val active = session.active
    var adding by rememberSaveable { mutableStateOf(startAdding) }
    var backingUp by rememberSaveable { mutableStateOf<String?>(null) }
    WhileShown(wallets.size) { balances.readAll() }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackHeader("Wallets", onBack = { nav.pop() }) {
            Text(Fmt.counted(wallets.size.toLong(), "wallet"), style = text(13, color = Ink.muted), modifier = Modifier.padding(end = 16.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "One wallet trades at a time. Each has its own One Balance, and funds never move between your wallets by themselves.",
                style = text(14, color = Ink.text2, lineHeight = 20.sp),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            for (w in wallets) {
                val on = w.id == active?.id
                val balance = balances.of(w)
                val shape = RoundedCornerShape(18.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(Ink.card).border(1.dp, if (on) Ink.greenLine else Ink.line, shape)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Ink.track).border(1.dp, Ink.line3, CircleShape), contentAlignment = Alignment.Center) {
                            Text(w.name.take(1).uppercase(), style = figure(18, FontWeight.Bold, Ink.text2))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(w.name, style = text(16, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (on) {
                                    Text(
                                        "Trading",
                                        style = text(12, FontWeight.Bold, Ink.green),
                                        modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Ink.greenDeep).padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            Text(whereOf(w), style = text(13, color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(balance?.let { Fmt.usdFull(it.total) } ?: Fmt.DASH, style = figure(19, FontWeight.SemiBold))
                            val note = when {
                                balance == null -> "Reading it"
                                balance.unread.isNotEmpty() -> "Not all chains read"
                                balance.total <= 0.0 -> "Nothing in it yet"
                                else -> null
                            }
                            if (note != null) Text(note, style = text(12, color = Ink.muted))
                        }
                    }
                    if (!w.backedUp && backingUp != w.id) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(Ink.card)
                                .clickable(role = Role.Button) { backingUp = w.id }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ToneMark(Tone.WARN, 18.dp)
                            Text("Back up its key before you send funds to it", style = text(13, color = Ink.text2, lineHeight = 18.sp), modifier = Modifier.weight(1f))
                            Text("Back up", style = text(14, FontWeight.Bold, Ink.green))
                        }
                    }
                    // The form opens right under the wallet it is about.
                    if (backingUp == w.id) {
                        Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { ExportForm(w, onClose = { backingUp = null }) }
                    }
                    if (!on) {
                        Box(
                            Modifier
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ink.raised)
                                .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) {
                                    scope.launch {
                                        runCatching { session.setActive(w.id) }.onSuccess {
                                            toasts.show("Now trading with ${w.name}", "One Balance ${balance?.let { Fmt.usdFull(it.total) } ?: "is being read"}")
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Trade with this wallet", style = text(14, FontWeight.Bold))
                        }
                    }
                }
            }

            if (!adding) {
                // A New form opens in the button's place.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .drawBehind {
                            drawRoundRect(
                                Ink.line3,
                                cornerRadius = CornerRadius(16.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                            )
                        }
                        .clickable(role = Role.Button) { adding = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(NIcons.plus, contentDescription = null, tint = Ink.green, modifier = Modifier.size(18.dp))
                    Text("Add a wallet", style = text(16, FontWeight.Bold, Ink.green))
                }
            } else {
                AddWalletForm(count = wallets.size, canCancel = wallets.isNotEmpty(), onClose = { adding = false })
            }
        }
    }
}

@Composable
private fun AddWalletForm(count: Int, canCancel: Boolean, onClose: () -> Unit) {
    val session = AppGraph.session
    val toasts = LocalToasts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by rememberSaveable { mutableStateOf(false) }
    var phrase by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("Wallet ${count + 1}") }
    // A secret is never put in saved state: it lives in this form only.
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (importing) SecureWindow()

    val looks = remember(secret, phrase) { looksLike(secret, phrase) }
    val okSecret = looks == SecretLooks.PHRASE || looks == SecretLooks.EVM_KEY || looks == SecretLooks.SOL_KEY
    val wrong = looks == SecretLooks.BAD_KEY || looks == SecretLooks.BAD_PHRASE || looks == SecretLooks.SHORT_PHRASE
    val ready = Vault.cleanName(name).isNotEmpty() && (!importing || okSecret) && !busy
    val add = {
        if (ready) {
            busy = true
            problem = null
            scope.launch {
                try {
                    val still = session.active?.name
                    val made = when {
                        !importing -> session.addNew(name)
                        phrase -> session.importPhrase(name, secret)
                        else -> session.importKey(name, secret)
                    }
                    secret = ""
                    val after = if (still != null) " You are still trading with $still." else ""
                    if (importing) toasts.show("${made.name} is imported", "Its balance is being read.$after")
                    else toasts.show("${made.name} is ready", "It is empty.$after")
                    onClose()
                } catch (e: VaultException) {
                    problem = e.say()
                } catch (_: Exception) {
                    problem = "That did not work. Try again."
                } finally {
                    busy = false
                }
            }
        }
    }

    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ink.card).border(1.dp, Ink.violet, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add a wallet", style = text(17, FontWeight.Bold), modifier = Modifier.semantics { heading() })
        Pills(listOf("Create new", "Import"), if (importing) 1 else 0, boxed = true) {
            importing = it == 1
            problem = null
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Name, only you see it", style = text(13, color = Ink.muted))
            TextBox(name, { name = it.take(Vault.MAX_NAME) }, "Name, only you see it", Modifier.fillMaxWidth())
        }
        if (!importing) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (line in listOf(
                    "It gets a new EVM address and a new Solana address.",
                    "It starts empty. Deposit to it, or withdraw to it from another wallet.",
                    "Its key is made and kept on this phone, and backed up on its own.",
                )) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = text(13, color = Ink.text2))
                        Text(line, style = text(13, color = Ink.text2, lineHeight = 19.sp))
                    }
                }
            }
        } else {
            Pills(listOf("Recovery phrase", "Private key"), if (phrase) 0 else 1, boxed = false) {
                phrase = it == 0
                secret = ""
                problem = null
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val label = if (phrase) "Recovery phrase" else "Private key"
                Text(label, style = text(13, color = Ink.muted))
                val note = secretNote(looks, phrase, Bip39.wordCount(secret))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBox(secret, { secret = it }, label, Modifier.weight(1f), secret = true, bad = note.takeIf { wrong })
                    Box(
                        Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ink.raised)
                            .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { SecretClipboard.paste(context)?.let { secret = it.trim() } }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Paste", style = text(14, FontWeight.Bold))
                    }
                }
                Text(note, style = text(13, color = if (wrong) Ink.pinkSoft else Ink.muted, lineHeight = 18.sp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Danger("It stays on this phone and is never sent anywhere. Nesty asks for it only here, never in a message or on a website.")
        }
        problem?.let { Text(it, style = text(14, FontWeight.SemiBold, Ink.pinkSoft), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canCancel) {
                Box(
                    Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Ink.line3, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", style = text(15, FontWeight.Bold))
                }
            }
            val label = when {
                busy -> if (importing) "Importing…" else "Creating…"
                importing -> "Import wallet"
                else -> "Create wallet"
            }
            GreenButton(label, add, Modifier.weight(2f), solid = ready, fontSize = 16)
        }
    }
}

/** The pink note beside anything that can cost the wallet. */
@Composable
private fun Danger(message: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Ink.pinkBg).border(1.dp, Ink.pink.copy(alpha = 0.3f), shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToneMark(Tone.BAD, 18.dp)
        Text(message, style = text(13, lineHeight = 19.sp), modifier = Modifier.weight(1f))
    }
}

/** Two or three choices side by side: in one box (a mode), or as separate pills (a kind). */
@Composable
private fun Pills(labels: List<String>, picked: Int, boxed: Boolean, onPick: (Int) -> Unit) {
    val outer = if (boxed) Modifier.clip(RoundedCornerShape(14.dp)).background(Ink.ground).border(1.dp, Ink.line, RoundedCornerShape(14.dp)).padding(3.dp) else Modifier
    Row(outer.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (boxed) 4.dp else 8.dp)) {
        for ((i, label) in labels.withIndex()) {
            val on = i == picked
            val shape = RoundedCornerShape(if (boxed) 11.dp else 12.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(if (on) Ink.violetBg else if (boxed) Ink.ground else Ink.ground)
                    .then(if (boxed) Modifier else Modifier.border(1.dp, if (on) Ink.violet else Ink.line2, shape))
                    .clickable(role = Role.Tab) { onPick(i) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = text(14, FontWeight.Bold, if (on) Ink.text else if (boxed) Ink.muted else Ink.text2), maxLines = 1)
            }
        }
    }
}

private fun exportName(kind: ExportKind): String = when (kind) {
    ExportKind.PHRASE -> "Recovery phrase"
    ExportKind.EVM_KEY -> "EVM key"
    ExportKind.SOL_KEY -> "Solana key"
}

/**
 * Shows one secret of a wallet after its password: for a backup, or to move the wallet. A key
 * shows its last four characters only and is copied; a phrase shows its words, to write down.
 */
@Composable
fun ExportForm(wallet: WalletInfo, onClose: () -> Unit) {
    val session = AppGraph.session
    val toasts = LocalToasts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kinds = remember(wallet) { session.exportKinds(wallet) }
    var kind by remember(wallet.id) { mutableStateOf(kinds.first()) }
    var password by remember { mutableStateOf("") }
    // Never in saved state, and gone as soon as the form is.
    var shown by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    SecureWindow()
    LaunchedEffect(wallet.id) { shown = null }

    val reveal = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            problem = null
            scope.launch {
                try {
                    shown = session.export(password, wallet.id, kind)
                } catch (e: VaultException) {
                    problem = e.say()
                } catch (_: Exception) {
                    problem = "That did not work. Try again."
                } finally {
                    password = ""
                    busy = false
                }
            }
        }
    }

    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.ground).border(1.dp, Ink.line2, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Danger("Anyone with this key can take everything in the wallet. Nesty never asks you for it.")
        val secret = shown
        if (secret == null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Password", style = text(13, color = Ink.muted))
                TextBox(password, { password = it }, "Password", Modifier.fillMaxWidth(), secret = true, bad = problem, ime = androidx.compose.ui.text.input.ImeAction.Go, onIme = reveal)
            }
            if (kinds.size > 1) Pills(kinds.map { exportName(it) }, kinds.indexOf(kind), boxed = false) { kind = kinds[it] }
            problem?.let { Text(it, style = text(14, FontWeight.SemiBold, Ink.pinkSoft), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ink.line3, RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", style = text(15, FontWeight.Bold))
                }
                GreenButton(if (busy) "Checking…" else "Show ${exportName(kind).lowercase().replace("evm", "EVM")}", reveal, Modifier.weight(2f), solid = password.isNotEmpty() && !busy, height = 48.dp, fontSize = 15)
            }
        } else {
            Text("${wallet.name}: ${exportName(kind).lowercase().replace("evm", "EVM")}", style = text(13, color = Ink.muted))
            if (kind == ExportKind.PHRASE) {
                // Words are for paper: three across, each with its place in the phrase.
                val words = secret.split(" ")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in words.withIndex().chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((i, word) in row) {
                                Row(
                                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Ink.raised).padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("${i + 1}", style = figure(13, FontWeight.SemiBold, Ink.muted))
                                    Text(word, style = text(14, FontWeight.SemiBold), maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Text("Write the words down in this order and keep them off any screen. They are this wallet's only backup.", style = text(13, color = Ink.text2, lineHeight = 19.sp))
            } else {
                Text("•••• •••• •••• •••• •••• •••• ${secret.takeLast(4)}", style = figure(17, FontWeight.SemiBold), maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink.raised)
                        .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            SecretClipboard.copy(context, secret)
                            toasts.show("Copied", "The clipboard is emptied in a minute")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Copy", style = text(15, FontWeight.Bold))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Ink.line3, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            shown = null
                            onClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Hide", style = text(15, FontWeight.Bold))
                }
            }
        }
    }
}
