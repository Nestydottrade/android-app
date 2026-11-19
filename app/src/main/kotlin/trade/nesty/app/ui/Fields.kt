package trade.nesty.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import trade.nesty.app.i18n.tr
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text

/**
 * A one-line box to type in, as the boards draw their inputs. A secret one shows dots, takes no
 * suggestions and is left out of the keyboard's learning.
 */
@Composable
fun TextBox(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    bad: String? = null,
    ime: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(Ink.ground)
            .border(1.dp, if (bad != null) Ink.pink else Ink.line3, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = text(15, color = Ink.muted), maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = text(15),
            cursorBrush = SolidColor(Ink.green),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ime,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onIme() }, onNext = { onIme() }, onGo = { onIme() }),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                if (bad != null) error(bad)
            },
        )
    }
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private var secureHolders = 0

/**
 * While this is on screen the window is kept out of screenshots, screen recordings and the
 * picture of the app among the recent ones. Put it wherever a key, a phrase or a password shows.
 */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity) {
        if (secureHolders++ == 0) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (--secureHolders == 0) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

object SecretClipboard {
    private const val CLEAR_AFTER_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Copies a key or a phrase. Android is told it is sensitive, so its preview hides it, and
     * the clipboard is emptied a minute later if it still holds it.
     */
    fun copy(context: Context, value: String) {
        val board = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(tr("Nesty"), value)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        board.setPrimaryClip(clip)
        // Only a hash of it is kept for the check, not the secret itself.
        val mark = hash(value)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val now = runCatching { board.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (now != null && hash(now).contentEquals(mark)) {
                if (Build.VERSION.SDK_INT >= 28) board.clearPrimaryClip() else board.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLEAR_AFTER_MS)
    }

    private fun hash(text: String): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    /** What the clipboard holds as text, for a Paste button. */
    fun paste(context: Context): String? =
        runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
}
