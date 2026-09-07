package app.lia.android.text

/**
 * Everything that happens to a transcript after a backend returns it, in the
 * order the plan fixes (4.3, 4.6, then 5.2 before 5.3).
 *
 * The order matters: the user's own correction table wins, and only what it
 * did not touch reaches the automatic Hebrew spelling guard.
 */
object PostProcess {

    data class Outcome(
        val text: String,
        val correctionsApplied: Int = 0,
        val lexiconFixes: List<Lexicon.Fix> = emptyList(),
        val oov: List<String> = emptyList(),
        val wasAllHallucination: Boolean = false,
    )

    data class Options(
        val corrections: List<Corrections.Pair> = emptyList(),
        val lexicon: Lexicon? = null,
        val protectedTerms: Set<String> = emptySet(),
        val applyLexiconFix: Boolean = false,
        val collectOov: Boolean = true,
    )

    fun run(raw: String, options: Options): Outcome {
        if (raw.isBlank()) return Outcome("")

        val stripped = Hallucination.strip(raw)
        if (stripped.isBlank()) return Outcome("", wasAllHallucination = true)

        val clean = Sanitize.forDisplay(stripped)
        if (clean.isBlank()) return Outcome("", wasAllHallucination = true)

        val corrected = Corrections.apply(clean, options.corrections)

        val lexicon = options.lexicon
        if (lexicon == null || !lexicon.loaded) {
            return Outcome(corrected.text, corrected.applied)
        }
        val guarded = lexicon.fix(
            text = corrected.text,
            protectedTerms = options.protectedTerms,
            applyFixes = options.applyLexiconFix,
            collectOov = options.collectOov,
        )
        return Outcome(
            text = guarded.text,
            correctionsApplied = corrected.applied,
            lexiconFixes = guarded.fixes,
            oov = guarded.oov,
        )
    }
}
