package app.lia.android.backend

import app.lia.android.audio.AudioMath
import app.lia.android.audio.SilenceSplit
import app.lia.android.text.PostProcess

/**
 * The one place that turns audio into a finished transcript, so all four
 * backends behave identically (plan Phase 2 and 3).
 *
 * Pipeline: silence gate -> auto-gain (dictation only) -> split -> backend per
 * piece -> join -> hallucination strip -> sanitize -> corrections -> lexicon.
 *
 * Fallback rule: if the SERVER cannot be reached we may fall back to the
 * configured cloud backend ONCE, and the result says so. We never fall back
 * the other way (the user who picked a cloud picked it for a reason - privacy,
 * or English), and we never retry an authorization failure.
 */
class Router(
    private val backends: Map<BackendId, Backend>,
    private val primary: BackendId,
    private val fallback: BackendId?,
    private val language: Language,
    private val hintsProvider: () -> List<String>,
    private val postProcessOptions: () -> PostProcess.Options,
) {

    sealed interface Outcome {
        data class Success(
            val result: TranscriptionResult,
            val post: PostProcess.Outcome,
        ) : Outcome

        /** Nothing was sent: the clip was too short or effectively silent. */
        data class Rejected(val message: String) : Outcome

        data class Failed(val message: String, val kind: BackendException.Kind) : Outcome
    }

    enum class Mode {
        /** A press-to-talk clip: gate, gain, and the short-clip rules apply. */
        DICTATION,

        /** A file the user picked: no gate, no gain, just split and send. */
        FILE,
    }

    suspend fun transcribe(
        audio: FloatArray,
        mode: Mode,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        if (audio.isEmpty()) return Outcome.Rejected("Nothing was recorded.")

        if (mode == Mode.DICTATION) {
            if (AudioMath.isTooShort(audio)) return Outcome.Rejected("Recording too short.")
            if (AudioMath.isSilent(audio)) return Outcome.Rejected("Mic silent - try again.")
        }

        // The bias gate is measured on the ORIGINAL clip, before any gain.
        val hints = PromptHints(
            vocabulary = hintsProvider(),
            allowBias = AudioMath.biasOk(audio),
        )
        val prepared =
            if (mode == Mode.DICTATION) AudioMath.applyAutoGain(audio).audio else audio

        val started = System.currentTimeMillis()
        var lastFailure: BackendException? = null
        val order = buildList {
            add(primary)
            if (fallback != null && fallback != primary && primary == BackendId.SERVER) {
                add(fallback)
            }
        }

        for ((index, id) in order.withIndex()) {
            val backend = backends[id] ?: continue
            try {
                val text = runBackend(backend, prepared, hints, onProgress)
                val post = PostProcess.run(text, postProcessOptions())
                if (post.text.isBlank() && post.wasAllHallucination) {
                    return Outcome.Rejected("Nothing was heard in that recording.")
                }
                return Outcome.Success(
                    TranscriptionResult(
                        text = post.text,
                        backend = id,
                        elapsedMs = System.currentTimeMillis() - started,
                        pieces = pieceCount(backend, prepared),
                        fellBackFrom = if (index > 0) order[0] else null,
                    ),
                    post,
                )
            } catch (e: BackendException) {
                lastFailure = e
                if (!e.allowsFallback) break
            }
        }

        val failure = lastFailure
            ?: BackendException(BackendException.Kind.CONFIG, "No transcription backend is set up.")
        return Outcome.Failed(failure.message ?: "Transcription failed.", failure.kind)
    }

    private suspend fun runBackend(
        backend: Backend,
        audio: FloatArray,
        hints: PromptHints,
        onProgress: (Int, Int) -> Unit,
    ): String {
        val pieces = splitFor(backend, audio)
        onProgress(0, pieces.size)
        val texts = ArrayList<String>(pieces.size)
        for ((index, piece) in pieces.withIndex()) {
            texts.add(backend.transcribe(piece, language, hints))
            onProgress(index + 1, pieces.size)
        }
        return texts.filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    private fun splitFor(backend: Backend, audio: FloatArray): List<FloatArray> =
        if (backend.id == BackendId.SERVER) SilenceSplit.forServer(audio)
        else SilenceSplit.forCloud(audio)

    private fun pieceCount(backend: Backend, audio: FloatArray): Int =
        splitFor(backend, audio).size

    companion object {
        /** Build the four backends from saved settings. Missing keys are omitted. */
        fun build(
            serverUrl: String,
            serverToken: String,
            allowInsecure: Boolean,
            groqKey: String,
            openAiKey: String,
            openAiModel: String,
            geminiKey: String,
        ): Map<BackendId, Backend> = buildMap {
            if (serverUrl.isNotBlank()) {
                put(
                    BackendId.SERVER,
                    ServerBackend(ServerBackend.Config(serverUrl, serverToken, allowInsecure)),
                )
            }
            if (groqKey.isNotBlank()) put(BackendId.GROQ, GroqBackend(groqKey))
            if (openAiKey.isNotBlank()) {
                put(BackendId.OPENAI, OpenAiBackend(openAiKey, openAiModel))
            }
            if (geminiKey.isNotBlank()) put(BackendId.GEMINI, GeminiBackend(geminiKey))
        }
    }
}
