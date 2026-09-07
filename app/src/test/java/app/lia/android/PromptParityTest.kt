package app.lia.android

import app.lia.android.vocab.VocabStore
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Proves the Kotlin prompt composer produces the SAME string as Lia Desktop's
 * `VocabStore.compose_prompt(600)` for the same `vocabulary.json`.
 *
 * The real file is private, so this test only runs when it is pointed at one:
 *
 *   LIA_VOCAB_FILE     = path to a vocabulary.json
 *   LIA_EXPECTED_PROMPT = path to a UTF-8 file holding the desktop's output
 *
 * `tools/prompt_parity.py` produces both and runs this. Without the variables
 * the test is skipped, so CI stays green on a machine that has no desktop
 * install.
 */
class PromptParityTest {

    @Test
    fun `the composed prompt matches lia desktop byte for byte`() {
        val vocabPath = System.getenv("LIA_VOCAB_FILE")
        val expectedPath = System.getenv("LIA_EXPECTED_PROMPT")
        assumeTrue(
            "set LIA_VOCAB_FILE and LIA_EXPECTED_PROMPT to run the parity check",
            !vocabPath.isNullOrBlank() && !expectedPath.isNullOrBlank(),
        )
        val store = VocabStore.parse(File(vocabPath!!).readText())
        val expected = File(expectedPath!!).readText().trim()
        val actual = store.composedPrompt()
        assertEquals(expected.length, actual.length)
        assertEquals(expected, actual)
    }
}
