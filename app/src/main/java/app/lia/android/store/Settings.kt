package app.lia.android.store

import android.content.Context
import android.content.SharedPreferences
import app.lia.android.backend.BackendId
import app.lia.android.backend.Language
import app.lia.android.backend.OpenAiBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Non-secret preferences. Defaults mirror Lia Desktop 1.4.4: Hebrew, home
 * server first with Groq behind it, tap-to-start/tap-to-stop recording.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lia_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state

    data class Snapshot(
        val serverUrl: String,
        val allowInsecure: Boolean,
        val primary: BackendId,
        val fallback: BackendId?,
        val language: Language,
        val openAiModel: String,
        val holdToRecord: Boolean,
        val logTranscripts: Boolean,
        val lexiconFixEnabled: Boolean,
        val lexiconSuggestEnabled: Boolean,
        val manualVocabulary: String,
    )

    fun read(): Snapshot = Snapshot(
        serverUrl = prefs.getString(SERVER_URL, "").orEmpty(),
        allowInsecure = prefs.getBoolean(ALLOW_INSECURE, false),
        primary = BackendId.fromKey(prefs.getString(PRIMARY, BackendId.SERVER.name)),
        fallback = prefs.getString(FALLBACK, BackendId.GROQ.name)
            ?.takeIf { it != NONE }
            ?.let { BackendId.fromKey(it) },
        language = Language.fromKey(prefs.getString(LANGUAGE, Language.HEBREW.name)),
        openAiModel = prefs.getString(OPENAI_MODEL, OpenAiBackend.DEFAULT_MODEL)
            .orEmpty().ifBlank { OpenAiBackend.DEFAULT_MODEL },
        holdToRecord = prefs.getBoolean(HOLD_TO_RECORD, false),
        logTranscripts = prefs.getBoolean(LOG_TRANSCRIPTS, false),
        lexiconFixEnabled = prefs.getBoolean(LEXICON_FIX, false),
        lexiconSuggestEnabled = prefs.getBoolean(LEXICON_SUGGEST, true),
        manualVocabulary = prefs.getString(MANUAL_VOCABULARY, "").orEmpty(),
    )

    private fun commit(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        _state.value = read()
    }

    fun setServerUrl(value: String) = commit { putString(SERVER_URL, value.trim()) }
    fun setAllowInsecure(value: Boolean) = commit { putBoolean(ALLOW_INSECURE, value) }
    fun setPrimary(value: BackendId) = commit { putString(PRIMARY, value.name) }
    fun setFallback(value: BackendId?) = commit { putString(FALLBACK, value?.name ?: NONE) }
    fun setLanguage(value: Language) = commit { putString(LANGUAGE, value.name) }
    fun setOpenAiModel(value: String) = commit { putString(OPENAI_MODEL, value) }
    fun setHoldToRecord(value: Boolean) = commit { putBoolean(HOLD_TO_RECORD, value) }
    fun setLogTranscripts(value: Boolean) = commit { putBoolean(LOG_TRANSCRIPTS, value) }
    fun setLexiconFixEnabled(value: Boolean) = commit { putBoolean(LEXICON_FIX, value) }
    fun setLexiconSuggestEnabled(value: Boolean) = commit { putBoolean(LEXICON_SUGGEST, value) }
    fun setManualVocabulary(value: String) = commit { putString(MANUAL_VOCABULARY, value) }

    companion object {
        const val NONE = "NONE"
        private const val SERVER_URL = "server_url"
        private const val ALLOW_INSECURE = "allow_insecure"
        private const val PRIMARY = "primary_backend"
        private const val FALLBACK = "fallback_backend"
        private const val LANGUAGE = "language"
        private const val OPENAI_MODEL = "openai_model"
        private const val HOLD_TO_RECORD = "hold_to_record"
        private const val LOG_TRANSCRIPTS = "log_transcripts"
        private const val LEXICON_FIX = "lexicon_fix_enabled"
        private const val LEXICON_SUGGEST = "lexicon_suggest_enabled"
        private const val MANUAL_VOCABULARY = "manual_vocabulary"
    }
}
