package app.lia.android

import app.lia.android.ime.FieldText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The placeholder bug: dictating into an empty WhatsApp chat produced
 * "Message" followed by what was said, because an empty field reports its
 * placeholder as its text (Naor, 2026-09-08).
 */
class FieldTextTest {

    @Test
    fun `a field showing its placeholder counts as empty`() {
        assertEquals("", FieldText.existing("Message", "Message", true))
    }

    @Test
    fun `a placeholder equal to the text counts as empty even without the flag`() {
        assertEquals("", FieldText.existing("Message", "Message", false))
    }

    @Test
    fun `real text is kept`() {
        assertEquals("שלום", FieldText.existing("שלום", "Message", false))
    }

    @Test
    fun `a null field is empty`() {
        assertEquals("", FieldText.existing(null, null, false))
    }

    @Test
    fun `dictating into an empty field inserts exactly what was said`() {
        val existing = FieldText.existing("Message", "Message", true)
        assertEquals("טוב, בוא נראה אם זה עובד.", FieldText.join(existing, "טוב, בוא נראה אם זה עובד."))
    }

    @Test
    fun `a second dictation is separated by one space`() {
        assertEquals("שלום עולם", FieldText.join("שלום", "עולם"))
        assertEquals("שלום עולם", FieldText.join("שלום ", "עולם"))
        assertEquals("שלום עולם", FieldText.join("שלום", " עולם"))
    }
}
