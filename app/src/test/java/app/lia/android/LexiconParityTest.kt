package app.lia.android

import app.lia.android.store.LexiconStore
import app.lia.android.text.Lexicon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the desktop's own answers (`tools/lexicon_parity.py`) through the
 * Kotlin port against the REAL hspell dictionary. Every case must agree - this
 * is what proves the -in/-im guard, the prefix stripping and the yod-guard were
 * ported, not approximated.
 *
 * The dictionary is AGPL and never committed, so the test runs only when
 * LIA_DICT_FILE points at a local copy; otherwise it is skipped.
 */
class LexiconParityTest {

    @Test
    fun `every desktop answer is reproduced`() = runBlocking {
        val dictPath = System.getenv("LIA_DICT_FILE")
        assumeTrue("set LIA_DICT_FILE to run the lexicon parity check", !dictPath.isNullOrBlank())
        val dict = File(dictPath!!)
        assumeTrue("dictionary not found at $dictPath", dict.exists())

        val store = LexiconStore(dict.parentFile)
        val lexicon: Lexicon = store.load()
        assertEquals(true, lexicon.loaded)

        val cases = (Fixtures.load("lexicon_expectations.json")["cases"] as JsonArray)
            .map { it.jsonObject }
        assertEquals(200, cases.size)

        var checked = 0
        for (case in cases) {
            val word = case["word"]!!.jsonPrimitive.content
            val expectedValid = case["valid"]!!.jsonPrimitive.boolean
            val expectedFix = case["fixed"]
                ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            assertEquals("valid($word)", expectedValid, lexicon.valid(word))
            assertEquals("fixWord($word)", expectedFix, lexicon.fixWord(word))
            checked++
        }
        assertEquals(cases.size, checked)
    }

    /**
     * The word list is the one thing in this app big enough to matter on a
     * phone. Sorted array + binary search instead of a HashSet is the reason
     * this stays modest; the ceiling below is the budget, and the measured
     * number is printed so a regression is visible in the log.
     */
    @Test
    fun `the loaded dictionary stays inside its memory budget`() = runBlocking {
        val dictPath = System.getenv("LIA_DICT_FILE")
        assumeTrue("set LIA_DICT_FILE to run the memory check", !dictPath.isNullOrBlank())
        val dict = File(dictPath!!)
        assumeTrue("dictionary not found at $dictPath", dict.exists())

        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        Thread.sleep(200)
        val before = runtime.totalMemory() - runtime.freeMemory()
        val started = System.currentTimeMillis()
        val lexicon = LexiconStore(dict.parentFile).load()
        val elapsed = System.currentTimeMillis() - started
        repeat(3) { System.gc() }
        Thread.sleep(200)
        val after = runtime.totalMemory() - runtime.freeMemory()
        val megabytes = (after - before) / (1024.0 * 1024.0)
        println("lexicon: loaded in $elapsed ms, retained %.1f MB".format(megabytes))
        assertEquals(true, lexicon.loaded)
        org.junit.Assert.assertTrue("retained %.1f MB".format(megabytes), megabytes < 60.0)
        org.junit.Assert.assertTrue("loaded in $elapsed ms", elapsed < 8_000)
    }
}
