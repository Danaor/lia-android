package app.lia.android.text

/**
 * The Hebrew spelling guard (plan 5.3), ported 1:1 from the desktop's
 * `lia/lexicon.py`.
 *
 * Whisper mishears the very common -im plural ending as the Aramaic-looking
 * -in, so "bitulin" comes back where "bitulim" was said. This class fixes
 * exactly that one ending, and only when the dictionary proves the -in form is
 * not a real word while the -im form is. Everything else it merely reports as
 * an unknown word for the user to turn into a correction pair.
 *
 * The dictionary is an hspell-derived word list downloaded at runtime (AGPL);
 * it is never bundled - see [LexiconStore]. Until it is loaded every word is
 * treated as valid, so the fix is a no-op rather than a rewriting machine.
 */
class Lexicon(private val words: WordSet?) {

    /** A word set the loader can back with a HashSet or a sorted array. */
    interface WordSet {
        operator fun contains(word: String): Boolean
        val size: Int
    }

    class HashWordSet(private val set: Set<String>) : WordSet {
        override operator fun contains(word: String) = set.contains(word)
        override val size get() = set.size
    }

    val loaded: Boolean get() = words != null

    companion object {
        /** Hebrew letter, then Hebrew letters / geresh / gershayim / quotes. */
        val TOKEN = Regex("[\u0590-\u05FF][\u0590-\u05FF\"']*")

        /** Everything the desktop strips before a dictionary lookup. */
        val PREFIXES = listOf(
            "", "ו", "ה", "ב", "ל", "כ", "מ", "ש",
            "וה", "וב", "ול", "וכ", "ומ", "וש",
            "שב", "של", "שה", "שכ", "שמ",
            "מה", "בה", "לה", "כה", "כש", "וכש", "כשה", "וכשה", "כשב", "כשל",
            "ושב", "ושה", "ושל", "מש", "ומש", "ומה", "ולה", "ובה",
            "לכש", "מכ", "שלה", "ובכ", "ממ", "וממ", "שמה", "ולכש",
        )

        /** Real words that end in -in and must never be rewritten. */
        val STOPLIST = setOf(
            "רבין", "לוין", "בגין", "עירובין", "פרקין",
            "בעלין", "סניגורין", "קטגורין", "מילין",
        )

        const val MIN_LENGTH = 4
        const val SUGGESTION_CAP = 500
    }

    /** True when w, or w minus one of the known prefixes, is in the dictionary. */
    fun valid(word: String): Boolean {
        val dict = words ?: return true          // fail safe: never rewrite blind
        for (prefix in PREFIXES) {
            if (!word.startsWith(prefix)) continue
            val stem = word.substring(prefix.length)
            if (stem.isNotEmpty() && dict.contains(stem)) return true
        }
        return false
    }

    /**
     * The one auto-fix. Returns the corrected word, or null to leave it alone.
     *
     * The yod-guard in step 3 is what keeps defective-spelling singulars safe:
     * if X + "yod-yod-nun" is a real word then X + "yod-nun" is that same word
     * written without the second yod, not a misheard plural.
     */
    fun fixWord(word: String, protected: Set<String> = emptySet()): String? {
        if (word.length < MIN_LENGTH) return null
        if (!word.endsWith("ין")) return null
        if (word in STOPLIST || word in protected) return null
        if (valid(word)) return null
        val stem = word.dropLast(2)
        if (valid(stem + "יין")) return null
        val candidate = stem + "ים"
        return if (valid(candidate)) candidate else null
    }

    data class Fix(val from: String, val to: String)

    data class Result(val text: String, val fixes: List<Fix>, val oov: List<String>)

    /**
     * Apply the guard to a transcript. Punctuation, spacing and any leading
     * bidi mark are untouched - only whole Hebrew tokens are considered.
     */
    fun fix(
        text: String,
        protectedTerms: Set<String> = emptySet(),
        applyFixes: Boolean = true,
        collectOov: Boolean = true,
    ): Result {
        if (text.isBlank()) return Result(text, emptyList(), emptyList())
        val fixes = ArrayList<Fix>()
        val oov = LinkedHashSet<String>()
        val out = TOKEN.replace(text) { match ->
            val word = match.value
            if (word.length < MIN_LENGTH) return@replace word
            val fixed = if (applyFixes) fixWord(word, protectedTerms) else null
            when {
                fixed != null -> {
                    fixes.add(Fix(word, fixed))
                    fixed
                }
                else -> {
                    if (collectOov && loaded && !valid(word) &&
                        word !in protectedTerms && oov.size < SUGGESTION_CAP
                    ) {
                        oov.add(word)
                    }
                    word
                }
            }
        }
        return Result(out, fixes, oov.toList())
    }
}
