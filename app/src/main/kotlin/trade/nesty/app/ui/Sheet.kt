package trade.nesty.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import trade.nesty.app.ui.theme.Ink
import trade.nesty.app.ui.theme.text

/**
 * A sheet over the screen, with its title kept in place while what is under it scrolls in its
 * own box. It closes on a swipe down, a tap outside it, or Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NestySheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val toasts = LocalToasts.current
    DisposableEffect(toasts) {
        toasts.sheets += 1
        onDispose { toasts.sheets -= 1 }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Ink.card,
        contentColor = Ink.text,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.line3),
            )
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = text(18, FontWeight.Bold, lineHeight = 24.sp),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp).semantics { heading() },
                )
                content()
            }
            // The screen's note, shown here while the sheet covers the screen's own.
            toasts.current?.let {
                ToastCard(it, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }
    }
}
