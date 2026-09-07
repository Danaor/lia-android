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
 * OpenAI transcription (plan 3.3).
 *
 * Two families with different rules:
 *  - `gpt-transcribe` / `gpt-4o-*`: `response_format` must be `json`, the
 *    prompt always opens with the verbatim instruction, and the trailing
 *    silence is NOT trimmed (they do not hallucinate over silence, and the trim
 *    would clip a quiet ending).
 *  - `whisper-1`: verbose_json, trim the tail, no verbatim instruction.
 */
class OpenAiBackend(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = Http.client(),
) : Backend {

    override val id = BackendId.OPENAI
    override val maxClipSeconds = 390.0f

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun transcribe(
        audio: FloatArray,
        language: Language,
        hints: PromptHints,
    ): String {
        if (apiKey.isBlank()) {
            throw BackendException(BackendException.Kind.CONFIG, "No OpenAI API key saved.")
        }
        val gpt = CloudPrompts.isGptFamily(model)
        val clip = if (gpt) audio else AudioMath.trimTrailingSilence(audio)
        val prompt = CloudPrompts.build(model, hints, language)
        val format = if (gpt) "json" else "verbose_json"

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", Wav.encode(clip).toRequestBody(Http.WAV_MEDIA))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", format)
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
                throw Http.failure("OpenAI", response.code, errorMessage(payload))
            }
            // GPT-family output can start with a bidi mark; strip before anything reads it.
            return app.lia.android.text.Hallucination.stripBidi(textOf(payload))
        }
    }

    override suspend fun test(): Result<String> = runCatching {
        if (apiKey.isBlank()) {
            throw BackendException(BackendException.Kind.CONFIG, "No OpenAI API key saved.")
        }
        val request = Http.get(MODELS_URL, mapOf("Authorization" to "Bearer $apiKey"))
        Http.execute(client.newCall(request)).use { response ->
            if (!response.isSuccessful) {
                throw Http.failure("OpenAI", response.code, errorMessage(response.body?.string()))
            }
            "OpenAI key works."
        }
    }

    fun textOf(payload: String): String =
        runCatching {
            json.parseToJsonElement(payload).jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim() ?: payload.trim()

    private fun errorMessage(payload: String?): String? = runCatching {
        val error = json.parseToJsonElement(payload.orEmpty()).jsonObject["error"]?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        listOfNotNull(message, code?.let { "[$it]" }).joinToString(" ").ifBlank { null }
    }.getOrNull()

    companion object {
        const val URL = "https://api.openai.com/v1/audio/transcriptions"
        const val MODELS_URL = "https://api.openai.com/v1/models"
        const val DEFAULT_MODEL = "gpt-transcribe"

        /** Offered in Settings, default first. */
        val MODELS = listOf(
            "gpt-transcribe",
            "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe",
            "whisper-1",
        )
    }
}
