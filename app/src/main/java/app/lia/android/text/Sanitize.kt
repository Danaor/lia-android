package app.lia.android.text

/**
 * Text coming back from a backend is untrusted input (plan 4.6). A
 * self-hosted server, or a model echoing something it heard, can emit control
 * characters; they never belong in a transcript.
 *
 * Character codes are written numerically on purpose: invisible literals in
 * source are exactly the kind of thing that silently breaks on a re-encode.
 */
object Sanitize {

    const val RLM = '\u200F'
    const val LRM = '\u200E'

    /** C0 and C1 controls, but never newline (0A), carriage return (0D) or tab (09). */
    fun isControl(c: Int): Boolean =
        c in 0x00..0x08 || c == 0x0B || c == 0x0C || c in 0x0E..0x1F || c in 0x7F..0x9F

    fun controls(text: String): String {
        if (text.none { isControl(it.code) }) return text
        return buildString(text.length) {
            for (ch in text) if (!isControl(ch.code)) append(ch)
        }
    }

    /**
     * Compose renders Hebrew correctly on its own, so the leading RLM the
     * desktop adds (Windows paste targets are LTR-biased) is noise here - drop
     * it for display, copy and share.
     */
    fun forDisplay(text: String): String = controls(text).trimStart(RLM, LRM).trim()
}
