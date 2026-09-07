package app.lia.android.backend

import app.lia.android.audio.AudioMath
import app.lia.android.audio.Wav
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.round

/**
 * The home transcription server: Lia Desktop running `lia.py --serve`
 * (plan 3.1).
 *
 * It speaks a WhisperLive-shaped protocol but transcribes in BATCH, on
 * END_OF_AUDIO. One connection per piece, each piece at most ~20 s, because the
 * server re-transcribes its whole buffer after every 0.2 s of quiet and drops a
 * connection older than 180 s.
 *
 * The server reads only `uid` and `language` from the hello frame. In
 * particular `initial_prompt` is ignored: an authorized client gets the HOST
 * desktop's own vocabulary applied server side, so the phone sends none.
 */
class ServerBackend(
    private val settings: Config,
    private val client: OkHttpClient = defaultClient(),
) : Backend {

    data class Config(
        val url: String,
        val token: String,
        val allowInsecure: Boolean = false,
    )

    override val id = BackendId.SERVER
    override val maxClipSeconds = 19.0f

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun transcribe(
        audio: FloatArray,
        language: Language,
        hints: PromptHints,
    ): String {
        val text = runOnce(audio, language)
        if (text.isNotEmpty()) return text
        // An empty answer for a clip that really has speech is worth exactly one
        // retry on a fresh connection; for a quiet clip empty IS the answer.
        if (AudioMath.isSilent(audio)) return ""
        return runOnce(audio, language)
    }

    override suspend fun test(): Result<String> {
        val silence = FloatArray(AudioMath.RATE)   // 1 s of digital silence
        return runCatching {
            runOnce(silence, Language.HEBREW)
            "Connected - the server answered SERVER_READY."
        }
    }

    private sealed interface Event {
        data class Text(val payload: String) : Event
        data class Closed(val code: Int, val reason: String) : Event
        data class Failed(val error: Throwable, val response: Response?) : Event
    }

    private suspend fun runOnce(audio: FloatArray, language: Language): String {
        val policy = WsUrl.check(settings.url, settings.allowInsecure)
        val parsed = when (policy) {
            is WsUrl.Policy.Allowed -> policy.parsed
            is WsUrl.Policy.Insecure -> policy.parsed
            is WsUrl.Policy.Refused -> throw BackendException(
                BackendException.Kind.CONFIG, policy.reason
            )
        }
        if (settings.token.isBlank() && !WsUrl.isLoopback(parsed.host)) {
            throw BackendException(
                BackendException.Kind.CONFIG,
                "This server needs an access token. Copy it from Lia on the PC " +
                    "(Settings > Transcription server).",
            )
        }

        val uid = UUID.randomUUID().toString()
        val events = Channel<Event>(Channel.UNLIMITED)
        val requestBuilder = Request.Builder().url(parsed.url)
        if (settings.token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${settings.token}")
        }
        // Deliberately no Origin header: the server closes 1008 on a foreign one.

        var socket: WebSocket? = null
        try {
            socket = client.newWebSocket(
                requestBuilder.build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        events.trySend(Event.Text(text))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // The server never sends binary; ignore it if it ever does.
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        events.trySend(Event.Closed(code, reason))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        events.trySend(Event.Closed(code, reason))
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        events.trySend(Event.Failed(t, response))
                    }
                },
            )

            socket.send(helloFrame(uid, language))
            awaitReady(events, uid)

            // Whisper family (4.2): a silent tail is what triggers a polite
            // sign-off that was never spoken.
            val pcm = Wav.toFloat32Le(AudioMath.trimTrailingSilence(audio))
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(pcm.size, offset + FRAME_BYTES)
                socket.send(pcm.toByteString(offset, end - offset))
                offset = end
            }
            socket.send(END_OF_AUDIO.toByteString())

            return collect(events, uid)
        } finally {
            runCatching { socket?.close(1000, null) }
            events.close()
        }
    }

    private fun helloFrame(uid: String, language: Language): String {
        val obj = buildJsonObject {
            put("uid", uid)
            put("language", language.code ?: "he")
            put("task", "transcribe")
            put("model", "large-v3-turbo")
            put("use_vad", true)
            put("send_last_n_segments", 1000)
            // The server ignores this; the HOST's vocabulary is applied there.
            put("initial_prompt", null as String?)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private suspend fun awaitReady(events: Channel<Event>, uid: String) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw BackendException(
                    BackendException.Kind.TIMEOUT,
                    "The server did not answer in time. Is it still running?",
                )
            }
            val event = withTimeoutOrNull(remaining) { events.receive() }
                ?: throw BackendException(
                    BackendException.Kind.TIMEOUT,
                    "The server did not answer in time. Is it still running?",
                )
            when (event) {
                is Event.Failed -> throw unreachable(event)
                is Event.Closed -> throw closeToException(event.code, event.reason)
                is Event.Text -> {
                    val obj = parse(event.payload) ?: continue
                    if (!matchesUid(obj, uid)) continue
                    statusError(obj)?.let { throw it }
                    if (obj["message"]?.jsonPrimitive?.contentOrNull == "SERVER_READY") return
                }
            }
        }
    }

    private suspend fun collect(events: Channel<Event>, uid: String): String {
        val ordered = LinkedHashMap<String, String>()
        val start = System.currentTimeMillis()
        var lastSegment = start
        while (true) {
            val now = System.currentTimeMillis()
            if (now - start > TOTAL_TIMEOUT_MS) break
            if (ordered.isNotEmpty() && now - lastSegment > QUIET_TIMEOUT_MS) break
            val event = withTimeoutOrNull(POLL_MS) { events.receive() } ?: continue
            when (event) {
                is Event.Failed -> {
                    if (ordered.isNotEmpty()) break
                    throw unreachable(event)
                }
                is Event.Closed -> {
                    if (event.code != 1000 && ordered.isEmpty()) {
                        throw closeToException(event.code, event.reason)
                    }
                    break
                }
                is Event.Text -> {
                    val obj = parse(event.payload) ?: continue
                    if (!matchesUid(obj, uid)) continue
                    statusError(obj)?.let { if (ordered.isEmpty()) throw it else return join(ordered) }
                    if (obj["message"]?.jsonPrimitive?.contentOrNull == "DISCONNECT") break
                    val segments = runCatching { obj["segments"]?.jsonArray }.getOrNull() ?: continue
                    for (element in segments) {
                        val segment = runCatching { element.jsonObject }.getOrNull() ?: continue
                        val text = segment["text"]?.jsonPrimitive?.contentOrNull ?: continue
                        val key = segmentKey(segment)
                        ordered[key] = text
                        lastSegment = System.currentTimeMillis()
                    }
                }
            }
        }
        return join(ordered)
    }

    private fun join(ordered: Map<String, String>): String =
        ordered.values.joinToString(" ") { it.trim() }.trim()

    /** Segments repeat as the server re-transcribes; dedupe on rounded times. */
    private fun segmentKey(segment: JsonObject): String {
        fun at(name: String): Double =
            segment[name]?.jsonPrimitive?.doubleOrNull ?: 0.0
        return "${round(at("start") * 100) / 100}:${round(at("end") * 100) / 100}"
    }

    private fun matchesUid(obj: JsonObject, uid: String): Boolean {
        val theirs = obj["uid"]?.jsonPrimitive?.contentOrNull ?: return true
        return theirs == uid
    }

    /** WhisperLive-standard status frames; the built-in server does not send them. */
    private fun statusError(obj: JsonObject): BackendException? {
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "ERROR" -> BackendException(
                BackendException.Kind.SERVER_ERROR,
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "The server reported an error.",
            )
            "WAIT" -> BackendException(
                BackendException.Kind.BUSY,
                "Server full - try again in a moment.",
            )
            else -> null   // WARNING and anything else: nothing to do
        }
    }

    private fun parse(payload: String): JsonObject? =
        runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()

    private fun unreachable(event: Event.Failed): BackendException {
        val code = event.response?.code
        if (code == 401 || code == 403) {
            return BackendException(BackendException.Kind.UNAUTHORIZED, UNAUTHORIZED_MESSAGE)
        }
        return BackendException(
            BackendException.Kind.UNREACHABLE,
            "Can't reach the server. Is Tailscale connected on this phone and the home PC on?",
            event.error,
        )
    }

    private fun closeToException(code: Int, reason: String): BackendException = when (code) {
        1008 -> if (reason.contains("origin", ignoreCase = true)) {
            BackendException(
                BackendException.Kind.UNAUTHORIZED,
                "The server rejected this connection (forbidden origin).",
            )
        } else {
            BackendException(BackendException.Kind.UNAUTHORIZED, UNAUTHORIZED_MESSAGE)
        }
        1013 -> BackendException(BackendException.Kind.BUSY, "Server busy - try again in a moment.")
        1011 -> BackendException(
            BackendException.Kind.TIMEOUT,
            "The server closed the connection (timeout). Try a shorter recording.",
        )
        else -> BackendException(
            BackendException.Kind.SERVER_ERROR,
            if (reason.isBlank()) "The server closed the connection (code $code)."
            else "The server closed the connection: $reason (code $code).",
        )
    }

    companion object {
        const val UNAUTHORIZED_MESSAGE =
            "Access token doesn't match the server. It must be identical on both devices."

        val END_OF_AUDIO: ByteArray = "END_OF_AUDIO".toByteArray(Charsets.US_ASCII)

        /** 2 s of float32 audio per frame, far under the server's 8 MB limit. */
        private const val FRAME_BYTES = 32000 * 4

        private const val READY_TIMEOUT_MS = 10_000L
        private const val QUIET_TIMEOUT_MS = 2_500L
        private const val TOTAL_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 500L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
