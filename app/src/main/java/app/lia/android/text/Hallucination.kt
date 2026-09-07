package app.lia.android.text

/**
 * Whisper-family models end a clip with a polite sign-off that was never said -
 * "thank you for watching", "תודה רבה" - especially on a silent tail. This is
 * the desktop's `strip_hallucinated_tail`, ported with the same patterns and
 * the same careful rule about punctuation.
 *
 * The rule that matters: a dangling SEPARATOR left behind by the cut is
 * removed, but a sentence-terminal mark is not. A real trailing "?" survives
 * (the desktop lost it until v1.4.0).
 */
object Hallucination {

    /** Whitespace plus the marks that merely JOIN clauses - never . ! ? */
    private const val SEPARATORS = " \t\n\r,;:"

    private val PATTERNS: List<Regex> = listOf(
        "(?:^|\\s)thank\\s+you(\\s+very\\s+much|\\s+for\\s+watching|\\s+for\\s+listening|\\s+all)?\\s*[!.?,]*\\s*$",
        "(?:^|\\s)thanks(\\s+for\\s+watching|\\s+for\\s+listening|\\s+a\\s+lot)?\\s*[!.?,]*\\s*$",
        "(?:^|\\s)bye(\\s+bye)?\\s*[!.?,]*\\s*$",
        "(?:^|\\s)goodbye\\s*[!.?,]*\\s*$",
        "(?:^|\\s)see\\s+you\\s+next\\s+time\\s*[!.?,]*\\s*$",
        "(?:^|\\s)תודה(\\s+רבה(\\s+לכם)?)?\\s*[!.?,]*\\s*$",
        "(?:^|\\s)תודה\\s+שצפיתם\\s*[!.?,]*\\s*$",
        "(?:^|\\s)תודה\\s+על\\s+הצפייה\\s*[!.?,]*\\s*$",
        "(?:^|\\s)להתראות\\s*[!.?,]*\\s*$",
        "^בהצלחה\\s*[!.?,]*\\s*$",
        "^שלום\\s*[!.?,]*\\s*$",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** LRM, RLM, the embedding/override set, and the directional isolates. */
    fun isBidiMark(c: Int): Boolean =
        c == 0x200E || c == 0x200F || c in 0x202A..0x202E || c in 0x2066..0x2069

    fun stripBidi(text: String): String {
        if (text.none { isBidiMark(it.code) }) return text
        return buildString(text.length) {
            for (ch in text) if (!isBidiMark(ch.code)) append(ch)
        }
    }

    /** Returns the cleaned text, or "" when the whole clip was a hallucination. */
    fun strip(input: String): String {
        var text = stripBidi(input).trimEnd()
        repeat(3) {
            var cut = false
            for (pattern in PATTERNS) {
                val match = pattern.find(text) ?: continue
                text = text.substring(0, match.range.first).trimEnd { it in SEPARATORS }
                cut = true
                if (text.isEmpty()) return ""
            }
            if (!cut) return text
        }
        return text
    }
}
