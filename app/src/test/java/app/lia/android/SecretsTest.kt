package app.lia.android

import app.lia.android.store.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The masking rule, and the promise that a saved secret is never shown in full.
 * The store itself needs a device keystore, so only the pure part is unit
 * tested here; the storage behaviour is a manual gate check.
 */
class SecretsTest {

    @Test
    fun `a mask shows the first four and last two characters only`() {
        val key = "gsk_abcdefghijklmnopqrstuvwxyz01"
        val masked = Secrets.mask(key)
        assertEquals(key.length, masked.length)
        assertTrue(masked.startsWith("gsk_"))
        assertTrue(masked.endsWith("01"))
        assertFalse(masked.contains("abcdefgh"))
    }

    @Test
    fun `a short secret is hidden completely`() {
        assertEquals("********", Secrets.mask("12345678"))
        assertEquals("*****", Secrets.mask("12345"))
    }

    @Test
    fun `an empty secret masks to nothing`() {
        assertEquals("", Secrets.mask(""))
    }
}
