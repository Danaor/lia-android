package app.lia.android.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys and the server token, at rest (plan 2.1).
 *
 * EncryptedSharedPreferences keeps them out of a plain XML file, backed by the
 * device keystore. Nothing here is ever logged, and [mask] is the only way a
 * secret reaches the UI.
 *
 * The blank-field rule is deliberate and comes straight from a desktop bug
 * (v1.4.3): a settings screen must never show the saved secret, and an empty
 * field on Save must KEEP the saved value rather than wipe it.
 */
class Secrets(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // A device with a broken keystore must still be usable; the keys then
        // live in a private prefs file, which is app-sandboxed either way.
        context.getSharedPreferences(FILE_NAME + "_plain", Context.MODE_PRIVATE)
    }

    fun get(key: Key): String = prefs.getString(key.storageName, "").orEmpty()

    fun has(key: Key): Boolean = get(key).isNotBlank()

    /** Empty input keeps whatever is stored - see the class comment. */
    fun save(key: Key, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString(key.storageName, trimmed).apply()
    }

    fun clear(key: Key) {
        prefs.edit().remove(key.storageName).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    enum class Key(val storageName: String) {
        SERVER_TOKEN("server_token"),
        GROQ("groq_api_key"),
        OPENAI("openai_api_key"),
        GEMINI("gemini_api_key"),
    }

    companion object {
        private const val FILE_NAME = "lia_secrets"

        /**
         * What a saved secret looks like on screen: first four and last two
         * characters, matching the desktop's tightened preview (v1.3.2).
         */
        fun mask(value: String): String {
            if (value.isBlank()) return ""
            if (value.length <= 8) return "*".repeat(value.length)
            return value.take(4) + "*".repeat(value.length - 6) + value.takeLast(2)
        }
    }
}
