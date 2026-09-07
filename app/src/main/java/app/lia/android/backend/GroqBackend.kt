package app.lia.android.backend

import app.lia.android.audio.AudioMath
import app.lia.android.audio.Wav
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Groq's OpenAI-compatible transcription endpoint (plan 3.2).
 *
 * Fastest of the four backends by a wide margin (0.4-0.7 s on the Phase 0
 * fixtures) and the default cloud fallback.
 */
class GroqBackend(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = Http.client(),
) : Backend {

    override val id = BackendId.GROQ

    /** 25 MB per request; a 16 kHz mono WAV is ~1.9 MB/min, so ~13 min. Split earlier. */
    override val maxClipSeconds = 390.0f

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun transcribe(
        audio: FloatArray,
        language: Language,
        hints: PromptHints,
    ): String {
        if (apiKey.isBlank()) {
            throw BackendException(BackendException.Kind.CONFIG, "No Groq API key saved.")
        }
        // Whisper-family: a silent tail is what triggers "thank you for watching".
        val clip = AudioMath.trimTrailingSilence(audio)
        val prompt = CloudPrompts.build(model, hints, language)

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", Wav.encode(clip).toRequestBody(Http.WAV_MEDIA))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .apply {
                language.code?.let { addFormDataPart("language", it) }
                if (prompt.isNotEmpty()) addFormDataPart("prompt", prompt)
            }
            .build()

        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        Http.execute(client.newCall(request)).use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Http.failure("Groq", response.code, errorMessage(payload))
            }
            return textOf(payload)
        }
    }

    override suspend fun test(): Result<String> = runCatching {
        if (apiKey.isBlank()) {
            throw BackendException(BackendException.Kind.CONFIG, "No Groq API key saved.")
        }
        val request = Http.get(MODELS_URL, mapOf("Authorization" to "Bearer $apiKey"))
        Http.execute(client.newCall(request)).use { response ->
            if (!response.isSuccessful) {
                throw Http.failure("Groq", response.code, errorMessage(response.body?.string()))
            }
            "Groq key works."
        }
    }

    /** verbose_json and json both carry the transcript in `text`. */
    fun textOf(payload: String): String =
        runCatching {
            json.parseToJsonElement(payload).jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim() ?: payload.trim()

    private fun errorMessage(payload: String?): String? = runCatching {
        val error = json.parseToJsonElement(payload.orEmpty()).jsonObject["error"]?.jsonObject
        error?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val MODELS_URL = "https://api.groq.com/openai/v1/models"
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"
    }
}
