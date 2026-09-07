package app.lia.android.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.lia.android.App
import app.lia.android.AppGraph
import app.lia.android.audio.AudioMath
import app.lia.android.audio.MicRecorder
import app.lia.android.backend.BackendException
import app.lia.android.backend.Language
import app.lia.android.backend.Router
import app.lia.android.store.History
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lia as the system's speech recogniser.
 *
 * This is the second of the two hooks a keyboard can use for its microphone
 * key. A keyboard either switches to a voice IME (see the voice subtype in
 * `res/xml/method.xml`) or calls `SpeechRecognizer`, which lands here once the
 * user picks Lia under Settings > Voice input. Implementing both is what makes
 * "tap the mic in the keyboard I already use" work regardless of which one a
 * given keyboard chose.
 *
 * The contract is the standard one, so anything that speaks `SpeechRecognizer`
 * gets Lia: Ready, results, or an error code the caller understands.
 */
class LiaRecognitionService : RecognitionService() {

    private val graph: AppGraph by lazy {
        (application as? App)?.graph ?: AppGraph.get(this)
    }

    private val recorder = MicRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var listenJob: Job? = null
    private var active: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            safe { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        if (active != null) {
            safe { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (!recorder.start(scope)) {
            safe { listener.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        active = listener
        graph.diagnostics.log("recognizer: listening")
        safe { listener.readyForSpeech(Bundle()) }
        listenJob = scope.launch { watchForEndOfSpeech(listener) }
    }

    override fun onStopListening(listener: Callback) {
        finish(listener, language(null))
    }

    override fun onCancel(listener: Callback) {
        listenJob?.cancel()
        listenJob = null
        recorder.cancel()
        active = null
        graph.diagnostics.log("recognizer: cancelled")
    }

    /**
     * Callers expect a recogniser to notice when you stop talking. Watch the
     * live level and finish after a stretch of quiet, with a hard ceiling so a
     * forgotten session cannot hold the microphone open.
     */
    private suspend fun watchForEndOfSpeech(listener: Callback) {
        var heardSpeech = false
        var quietSince = 0L
        var announced = false
        val started = System.currentTimeMillis()

        while (recorder.recording.value) {
            delay(POLL_MS)
            val level = recorder.amplitude.value
            safe { listener.rmsChanged(rmsToDb(level)) }

            if (level >= AudioMath.SILENCE_RMS * SPEECH_FACTOR) {
                if (!heardSpeech) {
                    heardSpeech = true
                    if (!announced) {
                        announced = true
                        safe { listener.beginningOfSpeech() }
                    }
                }
                quietSince = 0L
            } else if (heardSpeech) {
                if (quietSince == 0L) quietSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - quietSince >= TRAILING_SILENCE_MS) break
            }

            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= MAX_SESSION_MS) break
            if (!heardSpeech && elapsed >= NO_SPEECH_TIMEOUT_MS) {
                recorder.cancel()
                active = null
                safe { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                return
            }
        }
        finish(listener, language(null))
    }

    private fun finish(listener: Callback, language: Language) {
        listenJob?.cancel()
        listenJob = null
        if (active !== listener && active != null) return
        val audio = recorder.stop()
        active = null
        safe { listener.endOfSpeech() }

        graph.scope.launch {
            graph.diagnostics.logTranscripts = graph.settings.state.value.logTranscripts
            val outcome = graph.router().transcribe(audio, Router.Mode.DICTATION)
            withContext(Dispatchers.Main) { deliver(listener, outcome) }
        }
    }

    private fun deliver(listener: Callback, outcome: Router.Outcome) {
        when (outcome) {
            is Router.Outcome.Success -> {
                val text = outcome.result.text
                graph.diagnostics.logTranscript(
                    outcome.result.backend.short, outcome.result.elapsedMs, text
                )
                if (text.isBlank()) {
                    safe { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                    return
                }
                graph.history.add(
                    History.Entry(
                        text = text,
                        backend = outcome.result.backend.short,
                        timestamp = System.currentTimeMillis(),
                        elapsedMs = outcome.result.elapsedMs,
                        source = "recognizer",
                    )
                )
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)
                    )
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                }
                safe { listener.results(results) }
            }
            is Router.Outcome.Rejected -> {
                graph.diagnostics.log("recognizer rejected: ${outcome.message}")
                safe { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
            }
            is Router.Outcome.Failed -> {
                graph.diagnostics.log("recognizer failed (${outcome.kind}): ${outcome.message}")
                safe { listener.error(errorCode(outcome.kind)) }
            }
        }
    }

    /** Honour the caller's language when it asks for one Lia supports. */
    private fun language(recognizerIntent: Intent?): Language {
        val requested = recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?: return graph.settings.state.value.language
        return when {
            requested.startsWith("he", ignoreCase = true) -> Language.HEBREW
            requested.startsWith("en", ignoreCase = true) -> Language.ENGLISH
            else -> graph.settings.state.value.language
        }
    }

    private fun errorCode(kind: BackendException.Kind): Int = when (kind) {
        BackendException.Kind.UNAUTHORIZED, BackendException.Kind.CONFIG ->
            SpeechRecognizer.ERROR_CLIENT
        BackendException.Kind.UNREACHABLE -> SpeechRecognizer.ERROR_NETWORK
        BackendException.Kind.TIMEOUT -> SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        BackendException.Kind.RATE_LIMITED, BackendException.Kind.BUSY ->
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        BackendException.Kind.EMPTY -> SpeechRecognizer.ERROR_NO_MATCH
        else -> SpeechRecognizer.ERROR_SERVER
    }

    /** A caller that has gone away must not take the service down with it. */
    private inline fun safe(block: () -> Unit) {
        runCatching(block)
    }

    private fun rmsToDb(level: Float): Float =
        if (level <= 0f) -2f else (20 * kotlin.math.log10(level.toDouble())).toFloat().coerceIn(-2f, 10f)

    private companion object {
        const val POLL_MS = 100L
        const val TRAILING_SILENCE_MS = 1_500L
        const val NO_SPEECH_TIMEOUT_MS = 8_000L
        const val MAX_SESSION_MS = 120_000L

        /** Comfortably above the silence floor, so breathing does not count. */
        const val SPEECH_FACTOR = 3f
    }
}
