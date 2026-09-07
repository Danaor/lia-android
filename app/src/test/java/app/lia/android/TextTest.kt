package app.lia.android

import app.lia.android.text.Corrections
import app.lia.android.text.Hallucination
import app.lia.android.text.Lexicon
import app.lia.android.text.PostProcess
import app.lia.android.text.Sanitize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationTest {

    @Test
    fun `a real question mark survives the cut`() {
        assertEquals("זה עובד?", Hallucination.strip("זה עובד? תודה רבה"))
    }

    @Test
    fun `a whole-clip hallucination becomes empty`() {
        assertEquals("", Hallucination.strip("תודה רבה"))
        assertEquals("", Hallucination.strip("Thank you for watching!"))
    }

    @Test
    fun `a word that merely contains a pattern is left alone`() {
        assertEquals("עברה בהצלחה", Hallucination.strip("עברה בהצלחה"))
    }

    @Test
    fun `a leading bidi mark does not hide the match`() {
        val withRlm = Sanitize.RLM + "תודה רבה"
        assertEquals("", Hallucination.strip(withRlm))
    }

    @Test
    fun `a dangling comma goes but a full stop stays`() {
        assertEquals("המשפט האמיתי", Hallucination.strip("המשפט האמיתי, תודה רבה"))
        assertEquals("המשפט האמיתי.", Hallucination.strip("המשפט האמיתי. תודה רבה"))
    }

    @Test
    fun `english sign-offs in several shapes`() {
        assertEquals("the real sentence.", Hallucination.strip("the real sentence. Bye bye!"))
        assertEquals("the real sentence", Hallucination.strip("the real sentence, thanks a lot"))
        assertEquals("", Hallucination.strip("See you next time."))
    }
}

class SanitizeTest {

    @Test
    fun `control characters are removed and newlines kept`() {
        val input = "line one\nline two\ttabbed"
        assertEquals("line one\nline two\ttabbed", Sanitize.controls(input))
    }

    @Test
    fun `display strips a leading rtl mark`() {
        assertEquals("שלום", Sanitize.forDisplay(Sanitize.RLM + "שלום "))
    }
}

class CorrectionsTest {

    @Test
    fun `whole-word replacement with hebrew boundaries`() {
        val pairs = listOf(Corrections.Pair("בגד פושע", "git push"))
        val result = Corrections.apply("אני רוצה לעשות בגד פושע עכשיו", pairs)
        assertEquals("אני רוצה לעשות git push עכשיו", result.text)
        assertEquals(1, result.applied)
    }

    @Test
    fun `a hebrew word inside a longer hebrew word is not touched`() {
        val pairs = listOf(Corrections.Pair("שלום", "hello"))
        val result = Corrections.apply("שלומי הלך", pairs)
        assertEquals("שלומי הלך", result.text)
        assertEquals(0, result.applied)
    }

    @Test
    fun `latin replacement is case-insensitive but whole-word`() {
        val pairs = listOf(Corrections.Pair("kubernets", "Kubernetes"))
        val result = Corrections.apply("we deploy Kubernets today", pairs)
        assertEquals("we deploy Kubernetes today", result.text)
    }

    @Test
    fun `a pair that only changes case is skipped, not looped`() {
        val pairs = listOf(Corrections.Pair("react", "React"))
        val result = Corrections.apply("react is fine", pairs)
        assertEquals("react is fine", result.text)
        assertEquals(0, result.applied)
    }
}

class LexiconTest {

    /** The seven cases the plan names, on a tiny hand-made dictionary. */
    private val dictionary = setOf(
        "ביטולים", "ביטול", "מעוניין", "בניין", "נישואין", "לביטולים",
    )

    private val lexicon = Lexicon(Lexicon.HashWordSet(dictionary))

    @Test
    fun `the plural mishear is fixed`() {
        assertEquals("ביטולים", lexicon.fixWord("ביטולין"))
    }

    @Test
    fun `a prefixed form is fixed too`() {
        assertEquals("לביטולים", lexicon.fixWord("לביטולין"))
    }

    @Test
    fun `the yod-guard protects a defective-spelling singular`() {
        assertNull(lexicon.fixWord("מעונין"))
        assertNull(lexicon.fixWord("הבנין"))
    }

    @Test
    fun `a real word ending in the same letters is left alone`() {
        assertNull(lexicon.fixWord("נישואין"))
    }

    @Test
    fun `the stoplist wins`() {
        assertNull(lexicon.fixWord("רבין"))
    }

    @Test
    fun `an approved vocabulary term is protected`() {
        assertNull(lexicon.fixWord("ביטולין", protected = setOf("ביטולין")))
    }

    @Test
    fun `without a dictionary nothing is rewritten`() {
        val empty = Lexicon(null)
        assertNull(empty.fixWord("ביטולין"))
        assertTrue(empty.valid("שוםמילהשלא קיימת"))
    }

    @Test
    fun `fix rewrites inside a sentence and reports the change`() {
        val result = lexicon.fix("יש הרבה ביטולין היום", applyFixes = true)
        assertEquals("יש הרבה ביטולים היום", result.text)
        assertEquals(1, result.fixes.size)
        assertEquals("ביטולין", result.fixes[0].from)
    }

    @Test
    fun `punctuation and spacing are preserved`() {
        val result = lexicon.fix("ביטולין, ביטולין.", applyFixes = true)
        assertEquals("ביטולים, ביטולים.", result.text)
    }

    @Test
    fun `suggestions are collected without rewriting when the fix is off`() {
        val result = lexicon.fix("מילהלאמוכרת כאן", applyFixes = false)
        assertEquals("מילהלאמוכרת כאן", result.text)
        assertTrue(result.oov.contains("מילהלאמוכרת"))
    }
}

class PostProcessTest {

    @Test
    fun `corrections run before the lexicon guard`() {
        val lexicon = Lexicon(Lexicon.HashWordSet(setOf("ביטולים", "ביטול")))
        val options = PostProcess.Options(
            corrections = listOf(Corrections.Pair("בגד פושע", "git push")),
            lexicon = lexicon,
            applyLexiconFix = true,
        )
        val outcome = PostProcess.run("בגד פושע ואז ביטולין. תודה רבה", options)
        assertEquals("git push ואז ביטולים.", outcome.text)
        assertEquals(1, outcome.correctionsApplied)
        assertEquals(1, outcome.lexiconFixes.size)
    }

    @Test
    fun `a clip that was only a hallucination is reported as such`() {
        val outcome = PostProcess.run("תודה רבה", PostProcess.Options())
        assertEquals("", outcome.text)
        assertTrue(outcome.wasAllHallucination)
    }
}
