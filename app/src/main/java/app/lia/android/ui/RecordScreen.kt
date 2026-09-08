package app.lia.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lia.android.backend.BackendId

/**
 * Press to talk. Tap to start, tap to stop is the default (desktop 1.4.4);
 * hold-to-record is the alternative in Settings.
 */
@Composable
fun RecordScreen(viewModel: LiaViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.record.collectAsState()
    val settings by viewModel.settings.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Lia", style = MaterialTheme.typography.headlineMedium)
        Hint(
            when {
                state.recording && settings.holdToRecord -> "Release to transcribe"
                state.recording -> "Tap again to stop"
                state.busy -> "Transcribing..."
                settings.holdToRecord -> "Hold the button and speak"
                else -> "Tap the button and speak"
            }
        )

        RecordButton(
            recording = state.recording,
            busy = state.busy,
            onClick = {
                if (state.recording) viewModel.stopRecording() else viewModel.startRecording()
            },
        )

        if (state.recording) {
            Text(
                formatDuration(state.elapsedSeconds),
                style = MaterialTheme.typography.titleLarge,
            )
            LevelMeter(state.amplitude)
            TextButton(onClick = viewModel::cancelRecording) { Text("Cancel") }
        }

        if (state.busy) {
            CircularProgressIndicator()
        }

        state.message?.let { Hint(it, Modifier.padding(top = 8.dp)) }
        state.error?.let { ErrorText(it, Modifier.padding(top = 8.dp)) }

        if (state.transcript.isNotBlank()) {
            SectionCard(title = "Transcript") {
                TranscriptText(state.transcript)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyToClipboard(context, state.transcript) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text("  Copy")
                    }
                    OutlinedButton(onClick = { shareText(context, state.transcript) }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text("  Share")
                    }
                    TextButton(onClick = viewModel::clearRecordResult) { Text("Clear") }
                }
                Hint(backendLine(state))
                if (state.lexiconFixes.isNotEmpty()) {
                    Hint("Spelling guard: " + state.lexiconFixes.joinToString(", "))
                }
            }
        }
    }
}

private fun backendLine(state: LiaViewModel.RecordState): String {
    val name = state.backend?.label ?: "unknown backend"
    val pieces = if (state.pieces > 1) ", ${state.pieces} pieces" else ""
    val fallback = state.fellBackFrom?.let { " (fallback from ${it.short})" }.orEmpty()
    return "$name$fallback - ${state.elapsedMs} ms$pieces"
}

@Composable
private fun RecordButton(recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val color =
        if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.size(160.dp).clip(CircleShape),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Icon(
            if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "Stop" else "Record",
            modifier = Modifier.size(64.dp),
        )
    }
}

@Composable
private fun LevelMeter(amplitude: Float) {
    // The gate is at 0.005 RMS; scale so ordinary speech fills most of the bar.
    val fraction = (amplitude * 8f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(10.dp),
        )
    }
}
