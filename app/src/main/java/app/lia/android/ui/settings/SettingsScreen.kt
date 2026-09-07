package app.lia.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.lia.android.BuildConfig
import app.lia.android.backend.BackendId
import app.lia.android.backend.Language
import app.lia.android.backend.OpenAiBackend
import app.lia.android.store.LexiconStore
import app.lia.android.store.Secrets
import app.lia.android.ui.ErrorText
import app.lia.android.ui.Hint
import app.lia.android.ui.LabelRow
import app.lia.android.ui.LiaViewModel
import app.lia.android.ui.SectionCard
import app.lia.android.ui.copyToClipboard
import app.lia.android.ui.shareText
import app.lia.android.vocab.VocabStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: LiaViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // ---------------------------------------------------------- server
        var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
        var token by remember { mutableStateOf("") }
        var serverResult by remember { mutableStateOf<String?>(null) }
        var serverError by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }

        SectionCard(
            title = "Transcription server",
            subtitle = "Lia on your PC, reached over Tailscale. Fastest and fully private.",
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Address") },
                placeholder = { Text("100.x.y.z:9090") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Access token") },
                placeholder = {
                    Text(
                        if (viewModel.secrets.has(Secrets.Key.SERVER_TOKEN))
                            "Saved - leave blank to keep it"
                        else "Paste the token from the PC",
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                "The token must be identical on both devices. Copy it from Lia on the PC: " +
                    "Settings > Transcription server."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !testing,
                    onClick = {
                        viewModel.settings.setServerUrl(url)
                        // Blank field keeps the saved token - never wipes it.
                        viewModel.saveSecret(Secrets.Key.SERVER_TOKEN, token)
                        token = ""
                        serverResult = "Saved."
                        serverError = null
                    },
                ) { Text("Save") }
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        viewModel.settings.setServerUrl(url)
                        viewModel.saveSecret(Secrets.Key.SERVER_TOKEN, token)
                        token = ""
                        testing = true
                        serverResult = null
                        serverError = null
                        scope.launch {
                            val outcome = viewModel.testBackend(BackendId.SERVER)
                            testing = false
                            outcome
                                .onSuccess { serverResult = it }
                                .onFailure { serverError = it.message }
                        }
                    },
                ) { Text(if (testing) "Testing..." else "Test") }
            }
            serverResult?.let { Hint(it) }
            serverError?.let { ErrorText(it) }

            SwitchRow(
                label = "Allow insecure ws:// to a public host",
                checked = settings.allowInsecure,
                onChange = viewModel.settings::setAllowInsecure,
            )
            Hint(
                "Off by default. Plain ws:// is always allowed to a private address " +
                    "(Tailscale 100.x, 10.x, 192.168.x) and never to the open internet."
            )
        }

        // ------------------------------------------------------------ keys
        KeyCard(
            viewModel = viewModel,
            title = "Groq",
            subtitle = "whisper-large-v3-turbo. The fastest cloud option.",
            key = Secrets.Key.GROQ,
            backend = BackendId.GROQ,
        )

        KeyCard(
            viewModel = viewModel,
            title = "OpenAI",
            subtitle = "gpt-transcribe and the Whisper models.",
            key = Secrets.Key.OPENAI,
            backend = BackendId.OPENAI,
        ) {
            Text("Model", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenAiBackend.MODELS.forEach { model ->
                    FilterChip(
                        selected = settings.openAiModel == model,
                        onClick = { viewModel.settings.setOpenAiModel(model) },
                        label = { Text(model) },
                    )
                }
            }
        }

        KeyCard(
            viewModel = viewModel,
            title = "Gemini",
            subtitle = "gemini-3.5-transcribe. Free tier available.",
            key = Secrets.Key.GEMINI,
            backend = BackendId.GEMINI,
        ) {
            Hint(
                "On Google's free tier your audio may be used to improve their products. " +
                    "Use the home server for anything private."
            )
        }

        // ------------------------------------------------- language / order
        SectionCard(title = "Language and backend order") {
            Text("Spoken language", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Language.HEBREW to "Hebrew",
                    Language.ENGLISH to "English",
                    Language.AUTO to "Auto (he + en)",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = settings.language == value,
                        onClick = { viewModel.settings.setLanguage(value) },
                        label = { Text(label) },
                    )
                }
            }
            Text("Use first", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendId.entries.forEach { id ->
                    FilterChip(
                        selected = settings.primary == id,
                        onClick = { viewModel.settings.setPrimary(id) },
                        label = { Text(id.short) },
                    )
                }
            }
            Text("If the server is unreachable, fall back to", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(null) + BackendId.entries.filter { it != BackendId.SERVER })
                    .forEach { id ->
                        FilterChip(
                            selected = settings.fallback == id,
                            onClick = { viewModel.settings.setFallback(id) },
                            label = { Text(id?.short ?: "Nothing") },
                        )
                    }
            }
            Hint(
                "A fallback only ever runs when the SERVER cannot be reached, and the " +
                    "result says so. A cloud backend never silently falls back to the server."
            )
        }

        // ------------------------------------------------------- recording
        SectionCard(title = "Recording") {
            SwitchRow(
                label = "Hold the button to record",
                checked = settings.holdToRecord,
                onChange = viewModel.settings::setHoldToRecord,
            )
            Hint("Off = tap to start, tap to stop (the desktop default since 1.4.4).")
        }

        VocabularyCard(viewModel)
        LexiconCard(viewModel)

        // --------------------------------------------------------- privacy
        SectionCard(title = "Privacy") {
            SwitchRow(
                label = "Write transcripts to the diagnostic log",
                checked = settings.logTranscripts,
                onChange = {
                    viewModel.settings.setLogTranscripts(it)
                    viewModel.appGraph.diagnostics.logTranscripts = it
                },
            )
            Hint("Off by default. History still keeps your transcripts on this phone.")
            OutlinedButton(onClick = {
                viewModel.clearHistory()
                viewModel.secrets.clearAll()
            }) { Text("Delete all keys and history") }
        }

        DiagnosticsCard(viewModel)

        SectionCard(title = "About") {
            LabelRow("Version", BuildConfig.VERSION_NAME)
            Hint(
                "Lia for Android is MIT licensed. It uses OkHttp (Apache-2.0). The optional " +
                    "Hebrew dictionary is hspell-derived and AGPL: it is downloaded on request " +
                    "and never bundled."
            )
            TextButton(onClick = {
                copyToClipboard(context, "https://github.com/Danaor/lia-android")
            }) { Text("Copy project link") }
        }
    }
}

@Composable
private fun DiagnosticsCard(viewModel: LiaViewModel) {
    val context = LocalContext.current
    var log by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "Diagnostics",
        subtitle = "What to send when something goes wrong. No keys, no tokens.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                log = viewModel.appGraph.diagnostics.read(4000)
            }) { Text("Show log") }
            OutlinedButton(onClick = {
                shareText(context, viewModel.problemReport())
            }) { Text("Report a problem") }
            TextButton(onClick = {
                viewModel.appGraph.diagnostics.clear()
                log = null
            }) { Text("Clear log") }
        }
        log?.let {
            Text(
                it.takeLast(2000),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyCard(
    viewModel: LiaViewModel,
    title: String,
    subtitle: String,
    key: Secrets.Key,
    backend: BackendId,
    extra: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(viewModel.secrets.has(key)) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    SectionCard(title = title, subtitle = subtitle) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("API key") },
            placeholder = {
                Text(if (saved) "Saved - leave blank to keep it" else "Paste the key")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (saved) {
            AssistChip(onClick = {}, label = { Text(Secrets.mask(viewModel.secrets.get(key))) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                viewModel.saveSecret(key, value)
                value = ""
                saved = viewModel.secrets.has(key)
                result = "Saved."
                error = null
            }) { Text("Save") }
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    viewModel.saveSecret(key, value)
                    value = ""
                    saved = viewModel.secrets.has(key)
                    testing = true
                    result = null
                    error = null
                    scope.launch {
                        val outcome = viewModel.testBackend(backend)
                        testing = false
                        outcome.onSuccess { result = it }.onFailure { error = it.message }
                    }
                },
            ) { Text(if (testing) "Testing..." else "Test") }
            TextButton(onClick = {
                viewModel.clearSecret(key)
                saved = false
                result = "Cleared."
                error = null
            }) { Text("Clear") }
        }
        result?.let { Hint(it) }
        error?.let { ErrorText(it) }
        extra?.invoke()
    }
}

@Composable
private fun VocabularyCard(viewModel: LiaViewModel) {
    val graph = viewModel.appGraph
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newTerm by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read that file.")
                    VocabStore.parse(text)
                }
            }
            outcome
                .onSuccess {
                    graph.replaceVocabulary(it)
                    version++
                    status = "Imported ${it.terms.size} terms and ${it.corrections.size} corrections."
                    error = null
                }
                .onFailure {
                    error = it.message ?: "That is not a Lia vocabulary file."
                    status = null
                }
        }
    }

    val store = remember(version) { graph.vocabulary }

    SectionCard(
        title = "Vocabulary",
        subtitle = "Terms and fixes from Lia on the PC. Import vocabulary.json to share them.",
    ) {
        LabelRow("Terms", store.terms.size.toString())
        LabelRow("Corrections", store.corrections.size.toString())
        LabelRow("Prompt length", store.composedPrompt().length.toString() + " chars")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) {
                Text("Import vocabulary.json")
            }
            OutlinedButton(onClick = {
                copyToClipboard(context, store.composedPrompt())
                status = "Composed prompt copied - compare it with the desktop's."
            }) { Text("Copy prompt") }
        }
        status?.let { Hint(it) }
        error?.let { ErrorText(it) }

        OutlinedTextField(
            value = newTerm,
            onValueChange = { newTerm = it },
            label = { Text("Add a term") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            if (store.addManualTerm(newTerm)) {
                graph.replaceVocabulary(store)
                newTerm = ""
                version++
                status = "Term added."
            }
        }) { Text("Add term") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = wrong,
                onValueChange = { wrong = it },
                label = { Text("Heard as") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = right,
                onValueChange = { right = it },
                label = { Text("Should be") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = {
            if (store.addCorrection(wrong, right)) {
                graph.replaceVocabulary(store)
                wrong = ""
                right = ""
                version++
                status = "Correction added."
            }
        }) { Text("Add correction") }
    }
}

@Composable
private fun LexiconCard(viewModel: LiaViewModel) {
    val graph = viewModel.appGraph
    val settings by viewModel.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (graph.lexiconStore.isInstalled) "Dictionary installed." else null
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "Hebrew spelling guard",
        subtitle = "Fixes the -in / -im plural Whisper mishears, e.g. bitulin -> bitulim.",
    ) {
        Hint(
            "Needs a Hebrew word list derived from hspell. It is licensed AGPL, so Lia " +
                "downloads it on request instead of shipping it (about 4 MB, checked " +
                "against a pinned SHA-256)."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && !graph.lexiconStore.isInstalled,
                onClick = {
                    busy = true
                    status = "Downloading..."
                    error = null
                    scope.launch {
                        when (val outcome = graph.lexiconStore.download()) {
                            is LexiconStore.DownloadResult.Installed -> {
                                graph.loadLexicon()
                                viewModel.settings.setLexiconFixEnabled(true)
                                status = "Installed ${outcome.words} word forms."
                            }
                            is LexiconStore.DownloadResult.Failed -> {
                                error = outcome.message
                                status = null
                            }
                        }
                        busy = false
                    }
                },
            ) { Text(if (graph.lexiconStore.isInstalled) "Installed" else "Download") }
            if (graph.lexiconStore.isInstalled) {
                OutlinedButton(onClick = {
                    graph.lexiconStore.remove()
                    viewModel.settings.setLexiconFixEnabled(false)
                    scope.launch { graph.loadLexicon() }
                    status = "Removed."
                }) { Text("Remove") }
            }
        }
        status?.let { Hint(it) }
        error?.let { ErrorText(it) }

        SwitchRow(
            label = "Fix -in / -im automatically",
            checked = settings.lexiconFixEnabled,
            onChange = viewModel.settings::setLexiconFixEnabled,
        )
        SwitchRow(
            label = "Suggest unknown words",
            checked = settings.lexiconSuggestEnabled,
            onChange = viewModel.settings::setLexiconSuggestEnabled,
        )
    }
}
