package app.lia.android

import app.lia.android.audio.AudioMath
import app.lia.android.backend.BackendException
import app.lia.android.backend.Language
import app.lia.android.backend.PromptHints
import app.lia.android.backend.ServerBackend
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives the real [ServerBackend] against a MockWebServer that replays the
 * Phase 0 frames, so the whole path - hello, SERVER_READY, float32 frames,
 * END_OF_AUDIO, segments, DISCONNECT, close codes - is exercised offline.
 */
class ServerBackendTest {

    private lateinit var server: MockWebServer

    private val speech = FloatArray(AudioMath.RATE * 3) {
        (0.3 * sin(2 * PI * 300 * it / AudioMath.RATE)).toFloat()
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun backendFor(url: String, token: String = "") =
        ServerBackend(ServerBackend.Config(url, token))

    private fun wsUrl() = "ws://127.0.0.1:${server.port}"

    /**
     * A listener that completes the closing handshake. Without this
     * MockWebServer's shutdown blocks for 20 s waiting for the peer, and the
     * test fails on teardown rather than on its assertion.
     */
    private open class Polite : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    /** Answers like the real server: READY on open, segments after END_OF_AUDIO. */
    private class FakeServer(
        private val segments: List<String>,
        private val duplicateSegments: Boolean = false,
    ) : Polite() {
        var uid: String? = null

        override fun onMessage(webSocket: WebSocket, text: String) {
            uid = Regex("\"uid\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            webSocket.send("""{"uid":"$uid","message":"SERVER_READY","backend":"faster_whisper"}""")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.utf8() != "END_OF_AUDIO") return
            val body = segments.mapIndexed { index, text ->
                """{"start":${index * 2}.0,"end":${index * 2 + 2}.0,"text":"$text","completed":true}"""
            }
            webSocket.send("""{"uid":"$uid","segments":[${body.joinToString(",")}]}""")
            if (duplicateSegments) {
                // The real server re-sends what it already sent as it re-transcribes.
                webSocket.send("""{"uid":"$uid","segments":[${body.joinToString(",")}]}""")
            }
            webSocket.send("""{"uid":"$uid","message":"DISCONNECT"}""")
            webSocket.close(1000, null)
        }
    }

    @Test
    fun `a clip comes back as joined segment text`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(FakeServer(listOf(" שלום", "בדיקה.")))
        )
        val text = backendFor(wsUrl()).transcribe(speech, Language.HEBREW, PromptHints.NONE)
        assertEquals("שלום בדיקה.", text)
    }

    @Test
    fun `repeated segments are deduped on their rounded times`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                FakeServer(listOf("אחת", "שתיים"), duplicateSegments = true)
            )
        )
        val text = backendFor(wsUrl()).transcribe(speech, Language.HEBREW, PromptHints.NONE)
        assertEquals("אחת שתיים", text)
    }

    @Test
    fun `the hello frame carries uid and language and nothing the server needs`() = runBlocking {
        val fake = FakeServer(listOf("טקסט"))
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        backendFor(wsUrl()).transcribe(speech, Language.ENGLISH, PromptHints.NONE)
        val request = server.takeRequest()
        // No Origin header: the server closes 1008 on a foreign one.
        assertEquals(null, request.getHeader("Origin"))
        assertTrue(fake.uid!!.isNotEmpty())
    }

    @Test
    fun `the bearer token is sent on the upgrade`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeServer(listOf("טקסט"))))
        backendFor(wsUrl(), "TEST-TOKEN").transcribe(speech, Language.HEBREW, PromptHints.NONE)
        assertEquals("Bearer TEST-TOKEN", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 1008 close is reported as a token mismatch, not a timeout`() = runBlocking {
        val rejecting = object : Polite() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(1008, "unauthorized")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(rejecting))
        val error = runCatching {
            backendFor(wsUrl(), "wrong").transcribe(speech, Language.HEBREW, PromptHints.NONE)
        }.exceptionOrNull()
        assertTrue(error is BackendException)
        assertEquals(BackendException.Kind.UNAUTHORIZED, (error as BackendException).kind)
        assertEquals(ServerBackend.UNAUTHORIZED_MESSAGE, error.message)
    }

    @Test
    fun `a 1013 close says the server is busy`() = runBlocking {
        val busy = object : Polite() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(1013, "server busy")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(busy))
        val error = runCatching {
            backendFor(wsUrl(), "t").transcribe(speech, Language.HEBREW, PromptHints.NONE)
        }.exceptionOrNull() as BackendException
        assertEquals(BackendException.Kind.BUSY, error.kind)
    }

    @Test
    fun `a whisperlive style error frame is surfaced`() = runBlocking {
        val failing = object : Polite() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"status":"ERROR","message":"model not loaded"}""")
                webSocket.close(1000, null)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(failing))
        val error = runCatching {
            backendFor(wsUrl(), "t").transcribe(speech, Language.HEBREW, PromptHints.NONE)
        }.exceptionOrNull() as BackendException
        assertEquals(BackendException.Kind.SERVER_ERROR, error.kind)
        assertTrue(error.message!!.contains("model not loaded"))
    }

    @Test
    fun `a server that never says READY is a timeout, not a hang`() = runBlocking {
        val silent = object : Polite() {}
        server.enqueue(MockResponse().withWebSocketUpgrade(silent))
        val error = runCatching {
            backendFor(wsUrl(), "t").transcribe(speech, Language.HEBREW, PromptHints.NONE)
        }.exceptionOrNull() as BackendException
        assertEquals(BackendException.Kind.TIMEOUT, error.kind)
    }

    @Test
    fun `a public host over plain ws is refused before any connection`() = runBlocking {
        val error = runCatching {
            backendFor("ws://8.8.8.8:9090", "t")
                .transcribe(speech, Language.HEBREW, PromptHints.NONE)
        }.exceptionOrNull() as BackendException
        assertEquals(BackendException.Kind.CONFIG, error.kind)
        assertEquals(0, server.requestCount)
    }
}
