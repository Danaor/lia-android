package app.lia.android.backend

import app.lia.android.audio.AudioMath
import app.lia.android.audio.Wav
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString

/**
 * Google's dedicated speech model `gemini-3.5-transcribe` (plan 3.4).
 *
 * Reached through the **Interactions API**, not `:generateContent` - the
 * classic endpoint rejects `transcription_config` and answers with an empty
 * part. The key travels in the `x-goog-api-key` header, never in the URL.
 *
 * Free-tier audio may be used by Google to improve their products; the
 * Settings screen says so next to the key.
 */
class GeminiBackend(
    private val apiKey: String,
    private val client: OkHttpClient = Http.client(),
) : Backend {

    override val id = BackendId.GEMINI

    /** Inline audio cap. Phase 0 transcribed a 369 s clip in one request. */
    override val maxClipSeconds = 390.0f

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun transcribe(
        audio: FloatArray,
        language: Language,
        hints: PromptHints,
    ): String {
        if (apiKey.isBlank()) {
            throw BackendException(BackendException.Kind.CONFIG, "No Gemini API key saved.")
        }
        val clip = AudioMath.trimTrailingSilence(audio)
        val body = requestBody(clip, language, hints)
        val text = post(body)
        if (text.isNotEmpty() || AudioMath.isSilent(audio)) return text
        // Phase 0 finding A2: Gemini can answer 200 / status "completed" with no
        // steps at all. One retry, then say so rather than returning silence.
        val retry = post(body)
        if (retry.isEmpty()) {
            throw BackendException(
                BackendException.Kind.EMPTY,
                "Gemini returned no text for this segment.",
            )
        }
        return retry
    }

    override suspend fun test(): Result<String> = runCatching {
        if (apiKey.isBlank()) {
            throw BackendException(BackendException.Kind.CONFIG, "No Gemini API key saved.")
        }
        val request = Http.get(MODELS_URL, mapOf("x-goog-api-key" to apiKey))
        Http.execute(client.newCall(request)).use { response ->
            if (!response.isSuccessful) {
                throw Http.failure("Gemini", response.code, errorMessage(response.body?.string()))
            }
            "Gemini key works."
        }
    }

    fun requestBody(clip: FloatArray, language: Language, hints: PromptHints): String {
        val codes = when (language) {
            Language.HEBREW -> listOf("he-IL")
            Language.ENGLISH -> listOf("en-US")
            Language.AUTO -> listOf("he-IL", "en-US")
        }
        val vocabulary =
            if (hints.allowBias) hints.vocabulary.take(MAX_VOCABULARY) else emptyList()
        val obj = buildJsonObject {
            put("model", MODEL)
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("type", "audio")
                        put("data", Wav.encode(clip).toByteString().base64())
                        put("mime_type", "audio/wav")
                    }
                )
            }
            putJsonObject("generation_config") {
                putJsonObject("transcription_config") {
                    putJsonArray("language_codes") { codes.forEach { add(it) } }
                    if (vocabulary.isNotEmpty()) {
                        putJsonArray("custom_vocabulary") { vocabulary.forEach { add(it) } }
                    }
                    putJsonObject("mode") { put("type", "verbatim") }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private suspend fun post(body: String): String {
        var wait = FIRST_BACKOFF_MS
        var lastError: BackendException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val request = Request.Builder()
                .url(URL)
                .header("x-goog-api-key", apiKey)
                .post(Http.jsonBody(body))
                .build()
            val (code, payload) = Http.execute(client.newCall(request)).use { response ->
                response.code to response.body?.string().orEmpty()
            }
            if (code == 200) return textOf(payload)
            val detail = errorMessage(payload)
            if (code != 429 || attempt == MAX_ATTEMPTS - 1) {
                throw Http.failure("Gemini", code, detail)
            }
            lastError = Http.failure("Gemini", code, detail)
            val serverDelay = retryDelayMs(payload)
            delay(minOf(serverDelay ?: wait, MAX_BACKOFF_MS))
            wait = minOf((wait * 16) / 10, MAX_BACKOFF_MS)
        }
        throw lastError ?: BackendException(
            BackendException.Kind.SERVER_ERROR, "Gemini did not answer."
        )
    }

    /** Concatenate every `steps[].content[]` entry whose type is "text". */
    fun textOf(payload: String): String = runCatching {
        val steps = json.parseToJsonElement(payload).jsonObject["steps"] as? JsonArray
            ?: return@runCatching ""
        buildList {
            for (step in steps) {
                val content = step.jsonObject["content"] as? JsonArray ?: continue
                for (item in content) {
                    val obj = item.jsonObject
                    if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") continue
                    obj["text"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }.joinToString(" ").trim()
    }.getOrDefault("")

    fun retryDelayMs(payload: String): Long? = runCatching {
        val details = json.parseToJsonElement(payload).jsonObject["error"]
            ?.jsonObject?.get("details") as? JsonArray ?: return@runCatching null
        for (detail in details) {
            val value = detail.jsonObject["retryDelay"]?.jsonPrimitive?.contentOrNull ?: continue
            val seconds = value.removeSuffix("s").toDoubleOrNull() ?: continue
            return@runCatching (seconds * 1000).toLong()
        }
        null
    }.getOrNull()

    /** Google hides the real reason behind a bare status line - dig it out. */
    private fun errorMessage(payload: String?): String? = runCatching {
        val error = json.parseToJsonElement(payload.orEmpty()).jsonObject["error"]?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        val status = error?.get("status")?.jsonPrimitive?.contentOrNull
        listOfNotNull(message, status?.let { "[$it]" }).joinToString(" ").ifBlank { null }
    }.getOrNull()

    companion object {
        const val URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-3.5-transcribe"
        const val MAX_VOCABULARY = 1000

        private const val MAX_ATTEMPTS = 4
        private const val FIRST_BACKOFF_MS = 20_000L
        private const val MAX_BACKOFF_MS = 90_000L
    }
}
