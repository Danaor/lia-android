package app.lia.android

import app.lia.android.backend.CloudPrompts
import app.lia.android.backend.GeminiBackend
import app.lia.android.backend.GroqBackend
import app.lia.android.backend.Language
import app.lia.android.backend.OpenAiBackend
import app.lia.android.backend.PromptHints
import app.lia.android.backend.WsUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsUrlTest {

    @Test
    fun `bare host and port get the ws scheme and the default port`() {
        assertEquals("ws://100.70.0.1:9090", WsUrl.normalize("100.70.0.1")?.url)
        assertEquals("ws://100.70.0.1:9091", WsUrl.normalize("100.70.0.1:9091")?.url)
        assertEquals("ws://pc.ts.net:9090", WsUrl.normalize("ws://pc.ts.net")?.url)
    }

    @Test
    fun `http maps to ws and https to wss`() {
        assertEquals("ws", WsUrl.normalize("http://10.0.0.5:9090")?.scheme)
        assertEquals("wss", WsUrl.normalize("https://lia.example.com")?.scheme)
        assertEquals(443, WsUrl.normalize("https://lia.example.com")?.port)
    }

    @Test
    fun `a path is dropped - the server speaks at the root`() {
        assertEquals("ws://10.0.0.5:9090", WsUrl.normalize("ws://10.0.0.5:9090/live?x=1")?.url)
    }

    @Test
    fun `tailscale and lan addresses count as private`() {
        listOf("100.64.0.1", "100.127.255.254", "10.1.2.3", "192.168.1.7", "172.16.0.1",
            "169.254.1.1", "127.0.0.1", "localhost", "pc.local", "pc.ts.net")
            .forEach { assertTrue(it, WsUrl.isPrivateHost(it)) }
    }

    @Test
    fun `public addresses do not`() {
        listOf("8.8.8.8", "1.1.1.1", "100.128.0.1", "100.63.255.255", "example.com")
            .forEach { assertFalse(it, WsUrl.isPrivateHost(it)) }
    }

    @Test
    fun `plain ws to a public host is refused unless the user insists`() {
        val refused = WsUrl.check("ws://8.8.8.8:9090")
        assertTrue(refused is WsUrl.Policy.Refused)
        assertTrue((refused as WsUrl.Policy.Refused).reason.contains("8.8.8.8"))

        val insecure = WsUrl.check("ws://8.8.8.8:9090", allowInsecure = true)
        assertTrue(insecure is WsUrl.Policy.Insecure)
    }

    @Test
    fun `plain ws over tailscale is allowed, and wss always is`() {
        assertTrue(WsUrl.check("100.70.0.1:9090") is WsUrl.Policy.Allowed)
        assertTrue(WsUrl.check("wss://lia.example.com") is WsUrl.Policy.Allowed)
    }
}

class CloudPromptsTest {

    private val vocabulary = listOf("Kubernetes", "React")

    @Test
    fun `the gpt family always gets the verbatim instruction`() {
        val prompt = CloudPrompts.build("gpt-transcribe", PromptHints.NONE, Language.HEBREW)
        assertTrue(prompt.startsWith("Transcribe the audio verbatim"))
    }

    @Test
    fun `whisper models get no instruction and no prompt when the gate is closed`() {
        assertEquals("", CloudPrompts.build("whisper-1", PromptHints.NONE, Language.HEBREW))
    }

    @Test
    fun `the bias gate governs the term list and the bilingual sentence`() {
        val open = CloudPrompts.build(
            "whisper-large-v3-turbo",
            PromptHints(vocabulary, allowBias = true),
            Language.AUTO,
        )
        assertTrue(open.contains("Common terms: Kubernetes, React."))
        assertTrue(open.contains("Bilingual transcription"))

        val closed = CloudPrompts.build(
            "whisper-large-v3-turbo",
            PromptHints(vocabulary, allowBias = false),
            Language.AUTO,
        )
        assertEquals("", closed)
    }

    @Test
    fun `a forced language never gets the bilingual sentence`() {
        val prompt = CloudPrompts.build(
            "whisper-1",
            PromptHints(vocabulary, allowBias = true),
            Language.HEBREW,
        )
        assertTrue(prompt.contains("Common terms"))
        assertFalse(prompt.contains("Bilingual transcription"))
    }
}

/** Replays the Phase 0 captures: a contract change must break a test. */
class CloudParserTest {

    @Test
    fun `groq verbose_json is parsed and matches the captured text`() {
        val backend = GroqBackend("test-key")
        val results = Fixtures.results("groq_probe.json")
        assertEquals(4, results.size)
        results.forEach { result ->
            val payload = Fixtures.json.encodeToString(
                JsonObject.serializer(), result["response"]!!.jsonObject
            )
            assertEquals(
                result["text"]!!.jsonPrimitive.content,
                backend.textOf(payload),
            )
        }
    }

    @Test
    fun `openai json and verbose_json are both parsed`() {
        val results = Fixtures.results("openai_probe.json")
        assertEquals(8, results.size)
        results.forEach { result ->
            val model = result["model"]!!.jsonPrimitive.content
            val backend = OpenAiBackend("test-key", model)
            val payload = Fixtures.json.encodeToString(
                JsonObject.serializer(), result["response"]!!.jsonObject
            )
            assertEquals(
                result["text"]!!.jsonPrimitive.content,
                backend.textOf(payload),
            )
        }
    }

    @Test
    fun `the gpt family really answered plain json`() {
        val results = Fixtures.results("openai_probe.json")
        val gpt = results.filter { it["model"]!!.jsonPrimitive.content.startsWith("gpt-") }
        assertEquals(4, gpt.size)
        gpt.forEach {
            assertEquals("json", it["response_format"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `gemini steps content annotations are concatenated`() {
        val backend = GeminiBackend("test-key")
        val results = Fixtures.results("gemini_probe.json")
        assertEquals(4, results.size)
        results.forEach { result ->
            val payload = Fixtures.json.encodeToString(
                JsonObject.serializer(), result["response"]!!.jsonObject
            )
            assertEquals(
                result["text"]!!.jsonPrimitive.content,
                backend.textOf(payload),
            )
        }
    }

    @Test
    fun `an empty gemini answer parses as empty rather than throwing`() {
        val backend = GeminiBackend("test-key")
        // Phase 0 finding A2: status completed, no steps at all.
        val payload = """{"id":"v1_x","status":"completed","object":"interaction"}"""
        assertEquals("", backend.textOf(payload))
    }

    @Test
    fun `a gemini retry delay is read from the error details`() {
        val backend = GeminiBackend("test-key")
        val payload = """
            {"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED",
             "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo",
                         "retryDelay":"27s"}]}}
        """.trimIndent()
        assertEquals(27_000L, backend.retryDelayMs(payload))
    }

    @Test
    fun `the gemini request carries the transcription config the api needs`() {
        val backend = GeminiBackend("test-key")
        val body = backend.requestBody(
            FloatArray(16000) { 0.1f },
            Language.AUTO,
            PromptHints(listOf("Kubernetes"), allowBias = true),
        )
        val obj = Fixtures.json.parseToJsonElement(body).jsonObject
        assertEquals("gemini-3.5-transcribe", obj["model"]!!.jsonPrimitive.content)
        val input = obj["input"]!!.jsonArray[0].jsonObject
        assertEquals("audio", input["type"]!!.jsonPrimitive.content)
        assertEquals("audio/wav", input["mime_type"]!!.jsonPrimitive.content)
        assertTrue(input["data"]!!.jsonPrimitive.content.isNotEmpty())
        val config = obj["generation_config"]!!.jsonObject["transcription_config"]!!.jsonObject
        assertEquals(
            listOf("he-IL", "en-US"),
            config["language_codes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("verbatim", config["mode"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNotNull(config["custom_vocabulary"])
    }

    @Test
    fun `a closed bias gate drops the custom vocabulary entirely`() {
        val backend = GeminiBackend("test-key")
        val body = backend.requestBody(
            FloatArray(16000) { 0.1f },
            Language.HEBREW,
            PromptHints(listOf("Kubernetes"), allowBias = false),
        )
        val config = Fixtures.json.parseToJsonElement(body).jsonObject["generation_config"]!!
            .jsonObject["transcription_config"]!!.jsonObject
        assertTrue(config["custom_vocabulary"] == null)
        assertEquals(
            listOf("he-IL"),
            config["language_codes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}

/** The captured server conversation is the contract; assert its shape. */
class ServerProtocolTest {

    private val capture = Fixtures.load("server_probe.json")

    @Test
    fun `the handshake follows the documented order`() {
        val frames = capture["results"]!!.jsonArray[0].jsonObject["frames_first_piece"]!!.jsonArray
            .map { it.jsonObject }
        val hello = frames.first()
        assertEquals("client->server", hello["dir"]!!.jsonPrimitive.content)
        val payload = hello["payload"]!!.jsonObject
        assertEquals("he", payload["language"]!!.jsonPrimitive.content)
        assertTrue(payload.containsKey("uid"))

        val ready = frames[1]["payload"]!!.jsonObject
        assertEquals("SERVER_READY", ready["message"]!!.jsonPrimitive.content)

        assertTrue(frames.any { it["payload"].toString().contains("END_OF_AUDIO") })
        assertTrue(
            frames.any {
                (it["payload"] as? JsonObject)
                    ?.get("message")?.jsonPrimitive?.content == "DISCONNECT"
            }
        )
    }

    @Test
    fun `every clip came back with text and the splitter respected the cap`() {
        val results = capture["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, results.size)
        results.forEach { result ->
            assertTrue(
                result["clip"]!!.jsonPrimitive.content,
                result["text"]!!.jsonPrimitive.content.isNotBlank(),
            )
            result["pieces"]!!.jsonArray.forEach {
                assertTrue(it.jsonPrimitive.content.toFloat() <= 19.05f)
            }
        }
    }

    @Test
    fun `a wrong token is a 1008 unauthorized close`() {
        val unauthorized = capture["unauthorized"]!!.jsonObject
        assertEquals(1008, unauthorized["expected_close_code"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "unauthorized",
            unauthorized["expected_close_reason"]!!.jsonPrimitive.content,
        )
    }
}
