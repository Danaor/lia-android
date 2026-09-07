package app.lia.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lia.android.App
import app.lia.android.AppGraph
import app.lia.android.audio.FileDecoder
import app.lia.android.audio.MicRecorder
import app.lia.android.audio.RecordingService
import app.lia.android.audio.KeepAliveService
import app.lia.android.audio.TranscribeService
import app.lia.android.backend.BackendId
import app.lia.android.backend.Router
import app.lia.android.store.History
import app.lia.android.store.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All screen state in one place. The work runs in the application scope
 * ([AppGraph.scope]) so a rotation, or the user leaving the app, does not
 * abandon a transcription that is already in flight.
 */
class LiaViewModel(application: Application) : AndroidViewModel(application) {

    private val graph: AppGraph = (application as App).graph
    private val recorder = MicRecorder()

    data class RecordState(
        val recording: Boolean = false,
        val elapsedSeconds: Float = 0f,
        val amplitude: Float = 0f,
        val busy: Boolean = false,
        val transcript: String = "",
        val backend: BackendId? = null,
        val elapsedMs: Long = 0,
        val pieces: Int = 0,
        val fellBackFrom: BackendId? = null,
        val message: String? = null,
        val error: String? = null,
        val lexiconFixes: List<String> = emptyList(),
    )

    data class FileState(
        val fileName: String? = null,
        val busy: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val transcript: String = "",
        val backend: BackendId? = null,
        val error: String? = null,
        val cancelled: Boolean = false,
    )

    private val _record = MutableStateFlow(RecordState())
    val record: StateFlow<RecordState> = _record.asStateFlow()

    private val _file = MutableStateFlow(FileState())
    val file: StateFlow<FileState> = _file.asStateFlow()

    private val _history = MutableStateFlow(graph.history.all())
    val history: StateFlow<List<History.Entry>> = _history.asStateFlow()

    val settings = graph.settings
    val secrets = graph.secrets
    val appGraph get() = graph

    private var fileJob: Job? = null

    init {
        viewModelScope.launch {
            recorder.amplitude.collect { level ->
                _record.value = _record.value.copy(
                    amplitude = level,
                    elapsedSeconds = recorder.seconds,
                )
            }
        }
    }

    // ---------------------------------------------------------------- record

    fun startRecording() {
        if (_record.value.recording || _record.value.busy) return
        val context = getApplication<Application>()
        val started = recorder.start(graph.scope)
        if (!started) {
            _record.value = _record.value.copy(
                error = "Could not open the microphone. Is another app using it?"
            )
            return
        }
        KeepAliveService.start(
            context, RecordingService::class.java, "Lia is recording", "Tap to open Lia"
        )
        _record.value = RecordState(recording = true)
    }

    fun stopRecording() {
        if (!_record.value.recording) return
        val context = getApplication<Application>()
        val audio = recorder.stop()
        KeepAliveService.stop(context, RecordingService::class.java)
        _record.value = _record.value.copy(recording = false, busy = true, amplitude = 0f)

        graph.scope.launch {
            graph.diagnostics.logTranscripts = settings.state.value.logTranscripts
            graph.diagnostics.log(
                "dictation: %.1fs, backend %s".format(
                    audio.size / 16000f, settings.state.value.primary.name
                )
            )
            val outcome = graph.router().transcribe(audio, Router.Mode.DICTATION)
            withContext(Dispatchers.Main) { applyDictation(outcome) }
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        KeepAliveService.stop(getApplication(), RecordingService::class.java)
        _record.value = RecordState()
    }

    private fun applyDictation(outcome: Router.Outcome) {
        when (outcome) {
            is Router.Outcome.Success -> {
                val result = outcome.result
                _record.value = RecordState(
                    transcript = result.text,
                    backend = result.backend,
                    elapsedMs = result.elapsedMs,
                    pieces = result.pieces,
                    fellBackFrom = result.fellBackFrom,
                    lexiconFixes = outcome.post.lexiconFixes.map { "${it.from} -> ${it.to}" },
                )
                graph.diagnostics.logTranscript(
                    result.backend.short, result.elapsedMs, result.text
                )
                if (result.text.isNotBlank()) {
                    _history.value = graph.history.add(
                        History.Entry(
                            text = result.text,
                            backend = result.backend.short,
                            timestamp = System.currentTimeMillis(),
                            elapsedMs = result.elapsedMs,
                        )
                    )
                }
            }
            is Router.Outcome.Rejected -> {
                graph.diagnostics.log("dictation rejected: ${outcome.message}")
                _record.value = RecordState(message = outcome.message)
            }
            is Router.Outcome.Failed -> {
                graph.diagnostics.log("dictation failed (${outcome.kind}): ${outcome.message}")
                _record.value = RecordState(error = outcome.message)
            }
        }
    }

    fun clearRecordResult() {
        _record.value = RecordState()
    }

    // ------------------------------------------------------------------ file

    fun transcribeFile(uri: Uri, displayName: String?) {
        if (_file.value.busy) return
        val context = getApplication<Application>()
        _file.value = FileState(fileName = displayName, busy = true)
        KeepAliveService.start(
            context, TranscribeService::class.java, "Lia is transcribing", displayName.orEmpty()
        )
        fileJob = graph.scope.launch {
            try {
                val audio = FileDecoder.decode(context, uri)
                val outcome = graph.router().transcribe(audio, Router.Mode.FILE) { done, total ->
                    _file.value = _file.value.copy(done = done, total = total)
                }
                withContext(Dispatchers.Main) { applyFile(outcome, displayName) }
            } catch (e: FileDecoder.UnsupportedAudio) {
                _file.value = _file.value.copy(busy = false, error = e.message)
            } catch (e: Exception) {
                _file.value = _file.value.copy(
                    busy = false,
                    error = "Could not read that file: ${e.message ?: "unknown error"}",
                )
            } finally {
                KeepAliveService.stop(context, TranscribeService::class.java)
            }
        }
    }

    private fun applyFile(outcome: Router.Outcome, displayName: String?) {
        when (outcome) {
            is Router.Outcome.Success -> {
                _file.value = _file.value.copy(
                    busy = false,
                    transcript = outcome.result.text,
                    backend = outcome.result.backend,
                )
                if (outcome.result.text.isNotBlank()) {
                    _history.value = graph.history.add(
                        History.Entry(
                            text = outcome.result.text,
                            backend = outcome.result.backend.short,
                            timestamp = System.currentTimeMillis(),
                            elapsedMs = outcome.result.elapsedMs,
                            source = "file",
                            fileName = displayName,
                        )
                    )
                }
            }
            is Router.Outcome.Rejected ->
                _file.value = _file.value.copy(busy = false, error = outcome.message)
            is Router.Outcome.Failed ->
                _file.value = _file.value.copy(busy = false, error = outcome.message)
        }
    }

    /** Stops after the piece in flight, keeping whatever is already transcribed. */
    fun cancelFile() {
        fileJob?.cancel()
        fileJob = null
        KeepAliveService.stop(getApplication(), TranscribeService::class.java)
        _file.value = _file.value.copy(busy = false, cancelled = true)
    }

    fun clearFileResult() {
        _file.value = FileState()
    }

    // --------------------------------------------------------------- history

    fun refreshHistory() {
        _history.value = graph.history.all()
    }

    fun deleteHistoryEntry(timestamp: Long) {
        _history.value = graph.history.remove(timestamp)
    }

    fun clearHistory() {
        graph.history.clear()
        _history.value = emptyList()
    }

    // -------------------------------------------------------------- settings

    suspend fun testBackend(id: BackendId): Result<String> {
        val backend = graph.backends()[id]
            ?: return Result.failure(
                IllegalStateException(
                    when (id) {
                        BackendId.SERVER -> "Enter the server address first."
                        else -> "Save an API key first."
                    }
                )
            )
        return backend.test()
    }

    /** The "report a problem" text: configuration and the log, never a secret. */
    fun problemReport(): String = graph.diagnostics.report(
        appVersion = app.lia.android.BuildConfig.VERSION_NAME,
        device = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
        androidVersion = android.os.Build.VERSION.RELEASE,
        settings = settings.state.value,
        secrets = secrets,
        historyEntries = _history.value.size,
        vocabularyTerms = graph.vocabulary.terms.size,
        lexiconInstalled = graph.lexiconStore.isInstalled,
    )

    fun saveSecret(key: Secrets.Key, value: String) = secrets.save(key, value)

    fun clearSecret(key: Secrets.Key) = secrets.clear(key)
}
