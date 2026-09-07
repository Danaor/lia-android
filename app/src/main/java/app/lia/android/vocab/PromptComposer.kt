package app.lia.android.vocab

/**
 * Ranked vocabulary string for a cloud model's prompt (plan 5.2), ported from
 * the desktop's `VocabStore.compose_prompt`.
 *
 * Selection: ALL manual/starred terms (never dropped), then approved auto
 * terms by score while the budget allows.
 * ORDER: least important first, most important LAST - Whisper keeps the prompt
 * TAIL, so the tail is the valuable real estate.
 */
object PromptComposer {

    const val BUDGET_CHARS = 600

    /** Cost of a term in the budget: the term plus its ", " separator. */
    private fun cost(term: String) = term.length + 2

    fun pick(
        approved: List<VocabStore.Term>,
        budgetChars: Int = BUDGET_CHARS,
    ): List<String> {
        val manual = approved.filter { it.isApproved && it.isManual }
            .sortedBy { it.score }                       // most-used manual last
        val autos = approved.filter { it.isApproved && it.kind == "auto" && !it.starred }
            .sortedByDescending { it.score }             // best autos picked first

        var used = manual.sumOf { cost(it.term) }
        val picked = ArrayList<VocabStore.Term>()
        for (term in autos) {
            val c = cost(term.term)
            // Skip, do not break: a shorter term further down the list may fit.
            if (used + c > budgetChars) continue
            picked.add(term)
            used += c
        }
        val ordered = picked.sortedBy { it.score } + manual
        return ordered.map { it.term }
    }

    fun compose(
        approved: List<VocabStore.Term>,
        budgetChars: Int = BUDGET_CHARS,
    ): String = pick(approved, budgetChars).joinToString(", ")

    /** Settings fallback when there is no imported store: a plain typed list. */
    fun fromPlainList(text: String): List<String> =
        text.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
