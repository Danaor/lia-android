package app.lia.android.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Transcribe an audio or video file the user picks, or one shared into Lia.
 *
 * No storage permission is needed: `ACTION_OPEN_DOCUMENT` hands back a URI the
 * app may read, and that is all the decoder wants.
 */
@Composable
fun FileScreen(viewModel: LiaViewModel, modifier: Modifier = Modifier, sharedUri: Uri? = null) {
    val state by viewModel.file.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.transcribeFile(uri, displayName(context, uri))
    }

    LaunchedEffect(sharedUri) {
        if (sharedUri != null && !state.busy && state.transcript.isEmpty()) {
            viewModel.transcribeFile(sharedUri, displayName(context, sharedUri))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Transcribe a file", style = MaterialTheme.typography.headlineSmall)
        Hint("Audio or video: m4a, mp3, wav, ogg/opus, mp4, 3gp. You can also share a file into Lia.")

        Button(
            onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "Working..." else "Choose a file")
        }

        state.fileName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (state.busy) {
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done / state.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Piece ${state.done} of ${state.total}")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Hint("Decoding...")
            }
            TextButton(onClick = viewModel::cancelFile) { Text("Cancel") }
        }

        if (state.cancelled) Hint("Cancelled. Anything already transcribed is below.")
        state.error?.let { ErrorText(it) }

        if (state.transcript.isNotBlank()) {
            SectionCard(title = "Transcript") {
                Text(state.transcript, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyToClipboard(context, state.transcript) }) {
                        Text("Copy")
                    }
                    OutlinedButton(onClick = { shareText(context, state.transcript) }) {
                        Text("Share as text")
                    }
                    TextButton(onClick = viewModel::clearFileResult) { Text("Clear") }
                }
                state.backend?.let { Hint("Transcribed by ${it.label}") }
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    cursor.use {
        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) return it.getString(index)
    }
    return null
}
