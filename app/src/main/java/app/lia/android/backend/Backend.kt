package app.lia.android.backend

/** Which transcription engine answers a clip. */
enum class BackendId(val label: String, val short: String) {
    SERVER("Home transcription server", "Server"),
    GROQ("Groq", "Groq"),
    OPENAI("OpenAI", "OpenAI"),
    GEMINI("Gemini", "Gemini");

    companion object {
        fun fromKey(key: String?): BackendId =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: SERVER
    }
}

/** Transcription language the user picked. `null` means auto (Hebrew + English). */
enum class Language(val code: String?) {
    HEBREW("he"),
    ENGLISH("en"),
    AUTO(null);

    companion object {
        fun fromKey(key: String?): Language =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: HEBREW
    }
}

/**
 * Why a call failed. The UI never shows a raw exception (plan Phase 6), it
 * shows the message attached here.
 */
class BackendException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        UNAUTHORIZED,   // 1008 / HTTP 401 - never retry, never fall back
        BUSY,           // 1013 - server at capacity
        UNREACHABLE,    // DNS, connect, Tailscale off, PC asleep
        TIMEOUT,
        RATE_LIMITED,   // HTTP 429
        BAD_REQUEST,
        EMPTY,          // the backend answered, with no text
        SERVER_ERROR,
        CONFIG,         // no key, no URL, insecure transport refused
    }

    val allowsFallback: Boolean
        get() = kind == Kind.UNREACHABLE || kind == Kind.BUSY ||
            kind == Kind.TIMEOUT || kind == Kind.SERVER_ERROR
}

/** One transcription result plus what it cost. */
data class TranscriptionResult(
    val text: String,
    val backend: BackendId,
    val elapsedMs: Long,
    val pieces: Int = 1,
    val fellBackFrom: BackendId? = null,
    val warning: String? = null,
)

/**
 * What the caller wants put in front of the model.
 *
 * [Router] decides both fields BEFORE auto-gain, because the plan's bias gate
 * (4.5) is measured on the original clip: on a short or quiet recording a
 * Latin-heavy prompt gets COPIED into the transcript instead of biasing it.
 */
data class PromptHints(
    val vocabulary: List<String> = emptyList(),
    val allowBias: Boolean = false,
) {
    companion object {
        val NONE = PromptHints()
    }
}

/**
 * Every backend takes 16 kHz mono float audio and returns text. Splitting,
 * gain, trimming and the post-processing chain live in [Router] so all four
 * behave identically.
 */
interface Backend {
    val id: BackendId

    /** Longest clip this backend accepts in one request. */
    val maxClipSeconds: Float

    /** Transcribe one already-split piece. */
    suspend fun transcribe(audio: FloatArray, language: Language, hints: PromptHints): String

    /** The Settings "Test" button. Returns a human message either way. */
    suspend fun test(): Result<String>
}
