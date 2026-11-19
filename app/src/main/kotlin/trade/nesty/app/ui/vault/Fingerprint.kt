package trade.nesty.app.ui.vault

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import trade.nesty.app.data.AppGraph
import trade.nesty.app.i18n.tr
import trade.nesty.app.keys.wipe
import trade.nesty.app.ui.GreenButton
import trade.nesty.app.ui.SecureWindow
import trade.nesty.app.ui.TextBox
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text
import trade.nesty.app.vault.FingerprintOpener
import trade.nesty.app.vault.VaultException
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Fingerprint unlock on the screens: whether the phone can, the system's own prompt, and its three
 * uses (turn it on, open Nesty, confirm a send). Android 9 and later, strong biometrics only, since
 * only those can release a Keystore key. The finger releases the vault's key from the Keystore
 * ([trade.nesty.app.vault.FingerprintKey]); no password is ever stored.
 */
object Fingerprint {
    enum class Can { YES, NONE_ENROLLED, NO }

    sealed interface Outcome {
        data object Done : Outcome
        data object Cancelled : Outcome
        data class Problem(val text: String) : Outcome
    }

    private sealed interface Answer {
        class Ok(val cipher: Cipher) : Answer
        data object Cancelled : Answer
        class Failed(val text: String) : Answer
    }

    val ADD_ONE: String get() = tr("Add a fingerprint in your phone's settings first.")
    val DROPPED: String get() = tr("Your phone's fingerprints changed, so fingerprint unlock is off. Use your password, then turn it on again in the menu.")

    fun can(context: Context): Can {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.P) return Can.NO
        if (sdk >= Build.VERSION_CODES.R) {
            val m = context.getSystemService(BiometricManager::class.java) ?: return Can.NO
            return when (m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> Can.YES
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Can.NONE_ENROLLED
                else -> Can.NO
            }
        }
        if (sdk == Build.VERSION_CODES.Q) {
            val m = context.getSystemService(BiometricManager::class.java) ?: return Can.NO
            @Suppress("DEPRECATION")
            return when (m.canAuthenticate()) {
                BiometricManager.BIOMETRIC_SUCCESS -> Can.YES
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Can.NONE_ENROLLED
                else -> Can.NO
            }
        }
        // Android 9 has no way to ask; whether a finger is enrolled shows when the key is made.
        return if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) Can.YES else Can.NO
    }

    /** The password again, then a finger to seal the open vault's key under the phone's key. */
    suspend fun turnOn(context: Context, password: String): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Outcome.Problem(ADD_ONE)
        val session = AppGraph.session
        val store = AppGraph.fingerprint
        val key = try {
            session.keyForFingerprint(password)
        } catch (e: VaultException) {
            return Outcome.Problem(e.say())
        }
        try {
            val sealer = try {
                withContext(Dispatchers.Default) { store.sealer() }
            } catch (_: Exception) {
                return Outcome.Problem(ADD_ONE)
            }
            return when (val a = ask(context, sealer, tr("Turn on fingerprint unlock"), tr("Touch the sensor to confirm"), tr("Cancel"))) {
                is Answer.Ok -> try {
                    withContext(Dispatchers.Default) { store.save(a.cipher, key) }
                    Outcome.Done
                } catch (_: Exception) {
                    store.turnOff()
                    Outcome.Problem(tr("That did not work. Try again."))
                }
                Answer.Cancelled -> {
                    store.turnOff()
                    Outcome.Cancelled
                }
                is Answer.Failed -> {
                    store.turnOff()
                    Outcome.Problem(a.text)
                }
            }
        } finally {
            key.wipe()
            session.fingerprintChanged()
        }
    }

    /** Opens Nesty with a finger. Null when it opened or the password was picked; else what to say. */
    suspend fun unlock(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val session = AppGraph.session
        val store = AppGraph.fingerprint
        val opener = when (val o = withContext(Dispatchers.Default) { store.opener() }) {
            is FingerprintOpener.Ready -> o.cipher
            FingerprintOpener.Off -> return null.also { session.fingerprintChanged() }
            FingerprintOpener.Dropped -> return DROPPED.also { session.fingerprintChanged() }
        }
        return when (val a = ask(context, opener, tr("Unlock Nesty"), null, tr("Use password"))) {
            is Answer.Ok -> try {
                session.unlockWithKey(withContext(Dispatchers.Default) { store.open(a.cipher) })
                null
            } catch (_: VaultException) {
                // A key from another vault: nothing to open with it.
                session.turnOffFingerprint()
                DROPPED
            } catch (_: Exception) {
                tr("Fingerprint did not work. Use your password.")
            }
            Answer.Cancelled -> null
            is Answer.Failed -> a.text
        }
    }

    /**
     * A finger to confirm a send that cannot be undone. True only after a real fingerprint released
     * the key, and that key is this open vault's.
     */
    suspend fun confirm(context: Context, title: String, subtitle: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val store = AppGraph.fingerprint
        val opener = (withContext(Dispatchers.Default) { store.opener() } as? FingerprintOpener.Ready)?.cipher ?: run {
            AppGraph.session.fingerprintChanged()
            return false
        }
        val a = ask(context, opener, title, subtitle, tr("Cancel")) as? Answer.Ok ?: return false
        val key = withContext(Dispatchers.Default) { runCatching { store.open(a.cipher) }.getOrNull() } ?: return false
        return try {
            AppGraph.session.isKey(key)
        } finally {
            key.wipe()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun ask(context: Context, cipher: Cipher, title: String, subtitle: String?, negative: String): Answer =
        suspendCancellableCoroutine { cont ->
            val executor = context.mainExecutor
            val builder = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setNegativeButton(negative, executor) { _, _ -> if (cont.isActive) cont.resume(Answer.Cancelled) }
            if (subtitle != null) builder.setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            builder.build().authenticate(
                BiometricPrompt.CryptoObject(cipher),
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val c = result.cryptoObject?.cipher
                        if (cont.isActive) cont.resume(if (c != null) Answer.Ok(c) else Answer.Failed(tr("Fingerprint did not work. Use your password.")))
                    }

                    // A finger that does not match: the prompt says so itself and keeps listening.
                    override fun onAuthenticationFailed() = Unit

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        cont.resume(
                            when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> Answer.Cancelled
                                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> Answer.Failed(tr("Too many tries. Wait a little, or use your password."))
                                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> Answer.Failed(tr("Fingerprint is locked. Unlock the phone with its PIN, then try again."))
                                BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> Answer.Failed(ADD_ONE)
                                else -> Answer.Failed(tr("Fingerprint did not work. Use your password."))
                            },
                        )
                    }
                },
            )
        }
}

/**
 * The menu's row for fingerprint unlock. Turning it on asks for the password in a form right under
 * the row; turning it off needs nothing. Phones without a fingerprint sensor do not show it.
 */
@Composable
fun FingerprintSetting() {
    val context = LocalContext.current
    val session = AppGraph.session
    val can = remember { Fingerprint.can(context) }
    if (can == Fingerprint.Can.NO) return
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val on = session.fingerprintOn

    val turnOn = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            problem = null
            scope.launch {
                try {
                    when (val o = Fingerprint.turnOn(context, password)) {
                        Fingerprint.Outcome.Done -> form = false
                        Fingerprint.Outcome.Cancelled -> Unit
                        is Fingerprint.Outcome.Problem -> problem = o.text
                    }
                } finally {
                    password = ""
                    busy = false
                }
            }
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Unlock with fingerprint"), style = text(15, FontWeight.SemiBold))
                Text(
                    when {
                        on -> tr("On. Your password opens Nesty too.")
                        can == Fingerprint.Can.NONE_ENROLLED -> Fingerprint.ADD_ONE
                        else -> tr("Asks for your password to turn on.")
                    },
                    style = text(13, color = Ink.muted),
                )
            }
            if (on || can == Fingerprint.Can.YES) {
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(shape)
                        .background(Ink.raised)
                        .border(1.dp, Ink.line3, shape)
                        .clickable(role = Role.Button) {
                            when {
                                on -> session.turnOffFingerprint()
                                form -> {
                                    form = false
                                    password = ""
                                    problem = null
                                }
                                else -> form = true
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (on) tr("Turn off") else if (form) tr("Cancel") else tr("Turn on"), style = text(14, FontWeight.Bold))
                }
            }
        }
        // The form opens right under its row.
        if (form && !on) {
            SecureWindow()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Password"), style = text(13, color = Ink.muted))
                TextBox(password, { password = it }, tr("Password"), Modifier.fillMaxWidth(), secret = true, bad = problem, ime = ImeAction.Go, onIme = turnOn)
            }
            problem?.let { Text(it, style = text(14, FontWeight.SemiBold, Ink.pinkSoft), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            GreenButton(if (busy) tr("Checking…") else tr("Turn on"), turnOn, Modifier.fillMaxWidth(), solid = password.isNotEmpty() && !busy, height = 48.dp, fontSize = 15)
        }
    }
}
