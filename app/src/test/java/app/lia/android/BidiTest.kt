package app.lia.android

import app.lia.android.text.Bidi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTest {

    @Test
    fun `hebrew is right to left`() {
        assertTrue(Bidi.isRtl("שלום עולם"))
    }

    @Test
    fun `english is left to right`() {
        assertFalse(Bidi.isRtl("hello world"))
    }

    @Test
    fun `the first strong character decides, not the majority`() {
        // How Naor dictates: Hebrew carrying English terms. It is a Hebrew
        // paragraph and hangs off the right edge.
        assertTrue(Bidi.isRtl("אני רוצה לעשות git push עכשיו"))
        assertFalse(Bidi.isRtl("Deploy the שרת now"))
    }

    @Test
    fun `leading digits and punctuation are skipped`() {
        assertTrue(Bidi.isRtl("\"123. שלום"))
        assertFalse(Bidi.isRtl("123. hello"))
    }

    @Test
    fun `text with no strong character defaults to left to right`() {
        assertFalse(Bidi.isRtl("123 456 !?"))
        assertFalse(Bidi.isRtl(""))
    }
}
