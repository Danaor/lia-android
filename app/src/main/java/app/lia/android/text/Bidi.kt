package app.lia.android.text

/**
 * Which way a transcript should be aligned.
 *
 * Compose picks a paragraph's alignment from the LAYOUT direction, and the app's
 * chrome is English, so a Hebrew transcript was being left-aligned: the lines
 * filled from the left and a short last line sat on the wrong side with its full
 * stop stranded there. The text itself renders correctly either way - this is
 * about which edge the paragraph hangs from.
 *
 * The rule is the Unicode one: the first strong directional character decides.
 * A transcript that opens in Hebrew is a Hebrew paragraph even when it carries
 * English words, which is exactly how Naor dictates.
 */
object Bidi {

    /** True when the first strong character is right-to-left. */
    fun isRtl(text: String): Boolean {
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return true
                else -> Unit          // digits, punctuation, spaces: keep looking
            }
        }
        return false
    }
}
