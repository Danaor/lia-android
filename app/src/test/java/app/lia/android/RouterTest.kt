package app.lia.android

import app.lia.android.audio.AudioMath
import app.lia.android.backend.Backend
import app.lia.android.backend.BackendException
import app.lia.android.backend.BackendId
import app.lia.android.backend.Language
import app.lia.android.backend.PromptHints
import app.lia.android.backend.Router
import app.lia.android.text.Corrections
import app.lia.android.text.PostProcess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

private class FakeBackend(
    override val id: BackendId,
    private val answer: String? = null,
    private val failure: BackendException? = null,
) : Backend {
    override val maxClipSeconds = 390.0f
    var calls = 0
    var lastHints: PromptHints? = null

    override suspend fun transcribe(
        audio: FloatArray,
        language: Language,
        hints: PromptHints,
    ): String {
        calls++
        lastHints = hints
        failure?.let { throw it }
        return answer.orEmpty()
    }

    override suspend fun test(): Result<String> = Result.success("ok")
}

class RouterTest {

    private fun speech(seconds: Float, amplitude: Float = 0.3f) =
        FloatArray((seconds * AudioMath.RATE).toInt()) {
            (amplitude * sin(2 * PI * 300 * it / AudioMath.RATE)).toFloat()
        }

    private fun router(
        backends: Map<BackendId, Backend>,
        primary: BackendId = BackendId.SERVER,
        fallback: BackendId? = BackendId.GROQ,
        vocabulary: List<String> = emptyList(),
        options: PostProcess.Options = PostProcess.Options(),
    ) = Router(
        backends = backends,
        primary = primary,
        fallback = fallback,
        language = Language.HEBREW,
        hintsProvider = { vocabulary },
        postProcessOptions = { options },
    )

    @Test
    fun `a short clip never reaches a backend`() = runBlocking {
        val server = FakeBackend(BackendId.SERVER, "should not happen")
        val outcome = router(mapOf(BackendId.SERVER to server))
            .transcribe(speech(1.0f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Rejected)
        assertEquals("Recording too short.", (outcome as Router.Outcome.Rejected).message)
        assertEquals(0, server.calls)
    }

    @Test
    fun `a silent clip never reaches a backend`() = runBlocking {
        val server = FakeBackend(BackendId.SERVER, "should not happen")
        val outcome = router(mapOf(BackendId.SERVER to server))
            .transcribe(speech(4f, amplitude = 0.0005f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Rejected)
        assertEquals("Mic silent - try again.", (outcome as Router.Outcome.Rejected).message)
        assertEquals(0, server.calls)
    }

    @Test
    fun `a file is not gated by the dictation rules`() = runBlocking {
        val server = FakeBackend(BackendId.SERVER, "טקסט")
        val outcome = router(mapOf(BackendId.SERVER to server))
            .transcribe(speech(1.0f), Router.Mode.FILE)
        assertTrue(outcome is Router.Outcome.Success)
        assertEquals(1, server.calls)
    }

    @Test
    fun `an unreachable server falls back to the cloud and says so`() = runBlocking {
        val server = FakeBackend(
            BackendId.SERVER,
            failure = BackendException(BackendException.Kind.UNREACHABLE, "no route"),
        )
        val groq = FakeBackend(BackendId.GROQ, "מהענן")
        val outcome = router(mapOf(BackendId.SERVER to server, BackendId.GROQ to groq))
            .transcribe(speech(6f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Success)
        val result = (outcome as Router.Outcome.Success).result
        assertEquals(BackendId.GROQ, result.backend)
        assertEquals(BackendId.SERVER, result.fellBackFrom)
        assertEquals("מהענן", result.text)
    }

    @Test
    fun `a token mismatch is never retried and never falls back`() = runBlocking {
        val server = FakeBackend(
            BackendId.SERVER,
            failure = BackendException(BackendException.Kind.UNAUTHORIZED, "token"),
        )
        val groq = FakeBackend(BackendId.GROQ, "should not happen")
        val outcome = router(mapOf(BackendId.SERVER to server, BackendId.GROQ to groq))
            .transcribe(speech(6f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Failed)
        assertEquals(
            BackendException.Kind.UNAUTHORIZED,
            (outcome as Router.Outcome.Failed).kind,
        )
        assertEquals(1, server.calls)
        assertEquals(0, groq.calls)
    }

    @Test
    fun `a cloud primary never silently falls back to the server`() = runBlocking {
        val groq = FakeBackend(
            BackendId.GROQ,
            failure = BackendException(BackendException.Kind.UNREACHABLE, "offline"),
        )
        val server = FakeBackend(BackendId.SERVER, "should not happen")
        val outcome = router(
            mapOf(BackendId.GROQ to groq, BackendId.SERVER to server),
            primary = BackendId.GROQ,
            fallback = BackendId.SERVER,
        ).transcribe(speech(6f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Failed)
        assertEquals(0, server.calls)
    }

    @Test
    fun `the bias gate closes on a short clip and opens on a long loud one`() = runBlocking {
        val short = FakeBackend(BackendId.SERVER, "טקסט")
        router(mapOf(BackendId.SERVER to short), vocabulary = listOf("Kubernetes"))
            .transcribe(speech(3f), Router.Mode.DICTATION)
        assertEquals(false, short.lastHints!!.allowBias)

        val long = FakeBackend(BackendId.SERVER, "טקסט")
        router(mapOf(BackendId.SERVER to long), vocabulary = listOf("Kubernetes"))
            .transcribe(speech(8f), Router.Mode.DICTATION)
        assertEquals(true, long.lastHints!!.allowBias)
        assertEquals(listOf("Kubernetes"), long.lastHints!!.vocabulary)
    }

    @Test
    fun `post-processing runs on the joined text`() = runBlocking {
        val server = FakeBackend(BackendId.SERVER, "בגד פושע עכשיו. תודה רבה")
        val outcome = router(
            mapOf(BackendId.SERVER to server),
            options = PostProcess.Options(
                corrections = listOf(Corrections.Pair("בגד פושע", "git push"))
            ),
        ).transcribe(speech(6f), Router.Mode.DICTATION)
        assertEquals(
            "git push עכשיו.",
            (outcome as Router.Outcome.Success).result.text,
        )
    }

    @Test
    fun `a clip that was only a hallucination is reported, not saved`() = runBlocking {
        val server = FakeBackend(BackendId.SERVER, "תודה רבה")
        val outcome = router(mapOf(BackendId.SERVER to server))
            .transcribe(speech(6f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Rejected)
    }

    @Test
    fun `a missing backend is a clear configuration failure`() = runBlocking {
        val outcome = router(emptyMap(), fallback = null)
            .transcribe(speech(6f), Router.Mode.DICTATION)
        assertTrue(outcome is Router.Outcome.Failed)
        assertEquals(BackendException.Kind.CONFIG, (outcome as Router.Outcome.Failed).kind)
    }

    @Test
    fun `a long clip is split and every piece is sent to the server`() = runBlocking {
        val server = FakeBackend(BackendId.SERVER, "חלק")
        val progress = ArrayList<Pair<Int, Int>>()
        val outcome = router(mapOf(BackendId.SERVER to server))
            .transcribe(speech(45f), Router.Mode.DICTATION) { done, total ->
                progress.add(done to total)
            }
        val result = (outcome as Router.Outcome.Success).result
        assertTrue("pieces ${result.pieces}", result.pieces >= 3)
        assertEquals(result.pieces, server.calls)
        assertEquals(result.pieces to result.pieces, progress.last())
        assertNull(result.fellBackFrom)
    }
}
