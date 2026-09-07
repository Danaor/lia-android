package app.lia.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.lia.android.ui.FileScreen
import app.lia.android.ui.HistoryScreen
import app.lia.android.ui.LiaViewModel
import app.lia.android.ui.RecordScreen
import app.lia.android.ui.settings.SettingsScreen
import app.lia.android.ui.theme.LiaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LiaViewModel by viewModels()

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val shared = sharedAudioUri(intent)
        setContent {
            LiaTheme {
                LiaApp(viewModel, shared)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedAudioUri(intent)?.let { viewModel.transcribeFile(it, it.lastPathSegment) }
    }

    /** "Share into Lia" from a recorder or a messaging app. */
    private fun sharedAudioUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type.orEmpty()
        if (!type.startsWith("audio/") && !type.startsWith("video/")) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    RECORD("Record", Icons.Filled.Mic),
    FILE("File", Icons.Filled.AudioFile),
    HISTORY("History", Icons.Filled.History),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
private fun LiaApp(viewModel: LiaViewModel, sharedUri: Uri?) {
    var tab by remember { mutableStateOf(if (sharedUri != null) Tab.FILE else Tab.RECORD) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.RECORD -> RecordScreen(viewModel, modifier)
            Tab.FILE -> FileScreen(viewModel, modifier, sharedUri)
            Tab.HISTORY -> HistoryScreen(viewModel, modifier)
            Tab.SETTINGS -> SettingsScreen(viewModel, modifier)
        }
    }
}
