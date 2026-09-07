package app.lia.android.text

/**
 * The user's correction table (plan 5.2), ported from the desktop's
 * `apply_corrections`.
 *
 * Whole-word, case-insensitive replacement with Hebrew-aware boundaries: a
 * plain word boundary does not work here, because Java's \b treats a Hebrew
 * letter as a non-word character and would happily rewrite the middle of a
 * word. The lookarounds below say "not preceded/followed by a letter, digit or
 * Hebrew letter" instead.
 */
object Corrections {

    data class Pair(val wrong: String, val right: String)

    data class Result(val text: String, val applied: Int)

    private const val WORDISH = "[A-Za-z0-9\u0590-\u05FF]"

    private fun normalise(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    fun apply(text: String, pairs: List<Pair>): Result {
        if (text.isBlank() || pairs.isEmpty()) return Result(text, 0)
        var out = text
        var applied = 0
        for (pair in pairs) {
            val wrong = pair.wrong.trim()
            val right = pair.right.trim()
            if (wrong.isEmpty()) continue
            // A pair that only changes case or spacing would loop forever.
            if (normalise(wrong) == normalise(right)) continue
            val regex = Regex(
                "(?<!" + WORDISH + ")" + Regex.escape(wrong) + "(?!" + WORDISH + ")",
                RegexOption.IGNORE_CASE,
            )
            val hits = regex.findAll(out).count()
            if (hits == 0) continue
            // The lambda form inserts the result verbatim - no group syntax to escape.
            out = regex.replace(out) { right }
            applied += hits
        }
        return Result(out, applied)
    }
}
