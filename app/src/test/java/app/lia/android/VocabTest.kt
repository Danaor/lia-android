package app.lia.android

import app.lia.android.vocab.PromptComposer
import app.lia.android.vocab.VocabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {

    private fun term(
        name: String,
        kind: String = "auto",
        starred: Boolean = false,
        used: Int = 0,
        corpus: Int = 0,
    ) = VocabStore.Term(name, kind, "approved", starred, used, corpus)

    @Test
    fun `manual terms always survive and sit at the tail`() {
        val terms = listOf(
            term("AutoA", used = 10),
            term("AutoB", used = 1),
            term("ManualLow", kind = "manual", used = 0),
            term("ManualHigh", kind = "manual", used = 5),
        )
        val picked = PromptComposer.pick(terms)
        // Autos first, ascending by score; manual last, ascending by score.
        assertEquals(listOf("AutoB", "AutoA", "ManualLow", "ManualHigh"), picked)
    }

    @Test
    fun `a starred auto term counts as manual`() {
        val terms = listOf(term("Starred", starred = true), term("Plain", used = 99))
        assertEquals(listOf("Plain", "Starred"), PromptComposer.pick(terms))
    }

    @Test
    fun `the budget is respected and a long term is skipped, not a stop`() {
        val terms = listOf(
            term("x".repeat(500), used = 100),   // fits
            term("y".repeat(200), used = 90),    // would overflow - skipped
            term("short", used = 80),            // still fits after the skip
        )
        val picked = PromptComposer.pick(terms, budgetChars = 600)
        assertTrue(picked.contains("short"))
        assertFalse(picked.any { it.startsWith("y") })
    }

    @Test
    fun `the score is usage weighted three to one`() {
        val terms = listOf(
            term("ByCorpus", corpus = 10),   // score 10
            term("ByUsage", used = 4),       // score 12
        )
        // Ascending, so the higher score is last.
        assertEquals(listOf("ByCorpus", "ByUsage"), PromptComposer.pick(terms))
    }

    @Test
    fun `pending and rejected terms never reach the prompt`() {
        val terms = listOf(
            VocabStore.Term("Pending", "manual", "pending", true, 0, 0),
            VocabStore.Term("Rejected", "auto", "rejected", false, 9, 9),
            term("Good", kind = "manual"),
        )
        assertEquals(listOf("Good"), PromptComposer.pick(terms))
    }

    @Test
    fun `a plain typed list is cleaned up`() {
        assertEquals(
            listOf("git push", "React", "Kubernetes"),
            PromptComposer.fromPlainList(" git push, React\nKubernetes , "),
        )
    }
}

class VocabStoreTest {

    private val sample = """
        {"version":1,
         "terms":[
           {"term":"Kubernetes","kind":"manual","status":"approved","starred":true,
            "count_used":12,"count_corpus":3,"added":"2026-01-01"},
           {"term":"Entra","kind":"auto","status":"approved","starred":false,
            "count_used":2,"count_corpus":1},
           {"term":"Nope","kind":"auto","status":"pending","starred":false,
            "count_used":0,"count_corpus":0}
         ],
         "corrections":[{"wrong":"בגד פושע","right":"git push","source":"llm"}],
         "oov_candidates":[{"word":"ביטולין","count":3}],
         "flags":{"auto_learn":true}}
    """.trimIndent()

    @Test
    fun `the desktop schema is read`() {
        val store = VocabStore.parse(sample)
        assertEquals(3, store.terms.size)
        assertEquals(2, store.approvedTerms.size)
        assertEquals(1, store.corrections.size)
        assertEquals("git push", store.corrections[0].right)
        assertEquals(listOf("ביטולין"), store.oovCandidates)
    }

    @Test
    fun `the composed prompt puts the starred manual term last`() {
        val store = VocabStore.parse(sample)
        assertEquals("Entra, Kubernetes", store.composedPrompt())
    }

    @Test
    fun `unknown fields survive a round trip`() {
        val store = VocabStore.parse(sample)
        store.addManualTerm("React")
        val again = VocabStore.parse(store.toJson())
        assertTrue(again.toJson().contains("auto_learn"))
        assertTrue(again.toJson().contains("2026-01-01"))
        assertTrue(again.terms.any { it.term == "React" })
    }

    @Test
    fun `a duplicate term is not added twice`() {
        val store = VocabStore.parse(sample)
        assertFalse(store.addManualTerm("kubernetes"))
        assertEquals(3, store.terms.size)
    }

    @Test
    fun `a correction pair can be added and removed`() {
        val store = VocabStore.parse(sample)
        assertTrue(store.addCorrection("ריאקט", "React"))
        assertEquals(2, store.corrections.size)
        assertTrue(store.removeCorrection("ריאקט"))
        assertEquals(1, store.corrections.size)
    }

    @Test
    fun `a file that is not a vocabulary is rejected with a clear message`() {
        val error = runCatching { VocabStore.parse("""{"hello":"world"}""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("vocabulary"))
    }
}
