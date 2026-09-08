package app.lia.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import app.lia.android.text.Bidi
import androidx.compose.ui.unit.dp

/** Shared bits of chrome so the four screens look like one app. */

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: ColumnContent,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

typealias ColumnContent = @Composable () -> Unit

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start,
    )
}

@Composable
fun LabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A transcript, aligned by what it says rather than by the app's chrome.
 *
 * Compose takes a paragraph's alignment from the LAYOUT direction, which here is
 * the English UI, so Hebrew was hanging off the left edge. [Bidi] resolves the
 * direction from the first strong character instead, the way Unicode says to.
 */
@Composable
fun TranscriptText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val rtl = remember(text) { Bidi.isRtl(text) }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Text(
            text,
            modifier = modifier.fillMaxWidth(),
            style = style,
            textAlign = TextAlign.Start,
        )
    }
}

fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Lia transcript", text))
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share transcript"))
}

fun formatDuration(seconds: Float): String {
    val total = seconds.toInt()
    val minutes = total / 60
    val rest = total % 60
    return "%d:%02d".format(minutes, rest)
}
