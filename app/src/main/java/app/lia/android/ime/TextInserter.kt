package app.lia.android.ime

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Puts dictated text into whatever field is focused in ANOTHER app, so the
 * floating button can work without you leaving WhatsApp or switching keyboard.
 *
 * Android offers exactly two ways to write into a foreign text field: be the
 * keyboard, or be an accessibility service. This is the second one, and it is
 * strictly optional - the bubble still works without it by putting the text on
 * the clipboard.
 *
 * It is deliberately as blind as a service of this kind can be:
 *  - it subscribes to no events beyond the minimum the system requires;
 *  - it reads the screen only in the instant [insert] is called, only to find
 *    the focused editable node;
 *  - it never logs, stores or transmits anything it sees.
 */
class TextInserter : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Nothing to do: this service acts only when asked, never on an event. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun insertInternal(text: String): Boolean {
        val node = focusedEditable() ?: return false
        try {
            val existing = node.text?.toString().orEmpty()
            val joined = when {
                existing.isEmpty() -> text
                existing.last().isWhitespace() -> existing + text
                else -> "$existing $text"
            }
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, joined
                )
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                moveCaretToEnd(node, joined.length)
                return true
            }
            // Some apps refuse SET_TEXT but honour a paste.
            copy(text)
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun moveCaretToEnd(node: AccessibilityNodeInfo, length: Int) {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION")
        focused?.recycle()
        return null
    }

    private fun copy(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Lia", text))
    }

    companion object {
        @Volatile
        private var instance: TextInserter? = null

        /** True while the service is bound and ready to insert. */
        val isConnected: Boolean get() = instance != null

        /**
         * Whether the user has switched Lia on in Accessibility settings.
         * Read from the system list rather than from [isConnected], so Settings
         * shows the right state even before Android has bound the service.
         */
        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val name = android.content.ComponentName(context, TextInserter::class.java)
            return enabled.split(':').any {
                it.equals(name.flattenToString(), ignoreCase = true) ||
                    it.equals(name.flattenToShortString(), ignoreCase = true)
            }
        }

        /**
         * Writes [text] into the focused field of the app in front.
         * Returns false when the service is off or there is nothing to write to,
         * which is the caller's cue to fall back to the clipboard.
         */
        fun insert(text: String): Boolean =
            runCatching { instance?.insertInternal(text) ?: false }.getOrDefault(false)
    }
}
