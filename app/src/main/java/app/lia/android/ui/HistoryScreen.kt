package app.lia.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: LiaViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.history.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val shown = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.text.contains(query.trim(), ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("History", style = MaterialTheme.typography.headlineSmall)
            TextButton(
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
            ) { Text("Delete all") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (shown.isEmpty()) {
            Hint(if (entries.isEmpty()) "Nothing here yet." else "No match.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.timestamp }) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        Hint(
                            listOfNotNull(
                                stamp(entry.timestamp),
                                entry.backend,
                                entry.fileName,
                            ).joinToString(" - ")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { copyToClipboard(context, entry.text) }) {
                                Text("Copy")
                            }
                            TextButton(onClick = { shareText(context, entry.text) }) {
                                Text("Share")
                            }
                            TextButton(
                                onClick = { viewModel.deleteHistoryEntry(entry.timestamp) }
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all history?") },
            text = { Text("Every saved transcript on this phone is removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep") }
            },
        )
    }
}

private val FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

private fun stamp(timestamp: Long): String = FORMAT.format(Date(timestamp))
