package trade.nesty.app.ui.vault

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import trade.nesty.app.R
import trade.nesty.app.data.AppGraph
import trade.nesty.app.ui.BackHeader
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.LocalNavigator
import trade.nesty.app.ui.Route
import trade.nesty.app.ui.SecureWindow
import trade.nesty.app.ui.TextBox
import trade.nesty.app.ui.Tone
import trade.nesty.app.ui.ToneMark
import trade.nesty.app.ui.rememberNow
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.figure
import trade.nesty.app.ui.theme.text
import trade.nesty.app.vault.MIN_PASSWORD_LENGTH
import trade.nesty.app.vault.PasswordNeed
import trade.nesty.app.vault.Vault
import trade.nesty.app.vault.VaultError
import trade.nesty.app.vault.VaultException
import trade.nesty.app.vault.passwordNeeds

/** What a refusal of the vault says on screen. A wait is said as a length of time, never a clock time. */
fun VaultException.say(): String = when (code) {
    VaultError.BAD_PASSWORD -> "Wrong password"
    VaultError.TOO_MANY_ATTEMPTS -> "Too many wrong tries. Try again in ${Fmt.duration((retryAfterMs ?: 0).toDouble().coerceAtLeast(1_000.0))}."
    VaultError.WEAK_PASSWORD -> "This password is too easy to guess"
    VaultError.BAD_PHRASE -> "These words do not make a recovery phrase. Check each one."
    VaultError.BAD_KEY -> "This is not an EVM or Solana key"
    VaultError.DUPLICATE -> "This wallet is already here"
    VaultError.LIMIT -> "You have ${Vault.MAX_WALLETS} wallets, the most Nesty keeps"
    VaultError.CORRUPT -> "This phone cannot open its saved wallets. Restore them from your backups."
    VaultError.LOCKED -> "Nesty is locked"
    VaultError.STALE_KEY -> Fingerprint.DROPPED
    else -> "That did not work. Try again."
}

/**
 * The lock over the whole app while its wallets are closed. No board in the redesign: the first
 * draft's Unlock screen in the new look. With fingerprint unlock on, the finger is asked for as the
 * screen opens, and the password stays beside it.
 */
@Composable
fun UnlockScreen() {
    val session = AppGraph.session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    val now = rememberNow()
    SecureWindow()

    val finger = session.fingerprintOn && remember { Fingerprint.can(context) } != Fingerprint.Can.NO
    val useFinger = {
        if (!busy) {
            busy = true
            problem = null
            scope.launch {
                try {
                    problem = Fingerprint.unlock(context)
                } finally {
                    busy = false
                }
            }
        }
    }
    // Asked once as the lock shows; Use fingerprint asks again.
    LaunchedEffect(Unit) { if (finger) useFinger() }

    val waiting = waitUntil > now
    val go = {
        if (!busy && !waiting && password.isNotEmpty()) {
            busy = true
            problem = null
            scope.launch {
                try {
                    session.unlock(password)
                } catch (e: VaultException) {
                    if (e.code == VaultError.TOO_MANY_ATTEMPTS) waitUntil = System.currentTimeMillis() + (e.retryAfterMs ?: 0) else problem = e.say()
                } catch (_: Exception) {
                    problem = "That did not work. Try again."
                } finally {
                    password = ""
                    busy = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.nesty_egg), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(72.dp))
            Text("Nesty", style = figure(38, FontWeight.Bold), modifier = Modifier.semantics { heading() })
            Text("Keys stay encrypted on this phone", style = text(15, color = Ink.muted))
        }
        Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Password", style = text(13, color = Ink.muted))
            TextBox(password, { password = it }, "Password", Modifier.fillMaxWidth(), secret = true, bad = problem, ime = ImeAction.Go, onIme = go)
            GreenButton(if (busy) "Unlocking…" else "Unlock", go, Modifier.fillMaxWidth(), solid = true)
            if (finger) {
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(shape).border(1.dp, Ink.line3, shape).clickable(role = Role.Button, onClick = useFinger),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Use fingerprint", style = text(17, FontWeight.Bold))
                }
            }
            val said = if (waiting) "Too many wrong tries. Try again in ${Fmt.duration((waitUntil - now).toDouble().coerceAtLeast(1_000.0))}." else problem
            if (said != null) {
                Text(
                    said,
                    style = text(14, FontWeight.SemiBold, Ink.pinkSoft, lineHeight = 20.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

private fun needText(need: PasswordNeed): String = when (need) {
    PasswordNeed.LENGTH -> "At least $MIN_PASSWORD_LENGTH characters"
    PasswordNeed.UPPER -> "An uppercase letter"
    PasswordNeed.LOWER -> "A lowercase letter"
    PasswordNeed.DIGIT -> "A number"
}

/**
 * The first step to a wallet: a password. No board yet, so it is kept plain. The password's
 * rule is the extension's.
 */
@Composable
fun SetupScreen() {
    val nav = LocalNavigator.current
    val session = AppGraph.session
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    SecureWindow()

    val needs = passwordNeeds(password)
    val differs = again.isNotEmpty() && again != password
    val ready = needs.isEmpty() && again == password && !busy
    val go = {
        if (ready) {
            busy = true
            problem = null
            scope.launch {
                try {
                    session.create(password)
                    // Straight on to the first wallet; Back from there does not return to this form.
                    nav.pop()
                    nav.push(Route.Wallets(adding = true))
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

    Column(Modifier.fillMaxSize().imePadding()) {
        BackHeader("Create a password", onBack = { nav.pop() })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "It encrypts your wallets on this phone and opens Nesty when it is locked. Nesty cannot reset it, so keep each wallet's backup.",
                style = text(14, color = Ink.text2, lineHeight = 20.sp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Password", style = text(13, color = Ink.muted))
                TextBox(password, { password = it }, "Password", Modifier.fillMaxWidth(), secret = true, ime = ImeAction.Next)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (need in PasswordNeed.entries) {
                    val met = need !in needs
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        ToneMark(if (met) Tone.OK else Tone.NONE, 18.dp)
                        Text(needText(need) + if (met) "" else ": not yet", style = text(13, color = if (met) Ink.text2 else Ink.muted))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Type it again", style = text(13, color = Ink.muted))
                TextBox(again, { again = it }, "Type the password again", Modifier.fillMaxWidth(), secret = true, bad = "The two do not match".takeIf { differs }, ime = ImeAction.Go, onIme = go)
                if (differs) Text("The two do not match", style = text(13, color = Ink.pinkSoft))
            }
            GreenButton(if (busy) "Encrypting…" else "Continue", go, Modifier.fillMaxWidth(), solid = ready)
            problem?.let { Text(it, style = text(14, FontWeight.SemiBold, Ink.pinkSoft), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}
