package app.lia.android.store

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rotating log plus the "report a problem" bundle (plan Phase 6).
 *
 * Two rules, both inherited from the desktop:
 *  - transcript text is written only when the user turns that on
 *    (`log_transcripts`, default off);
 *  - a secret never reaches the log or the report, not even masked, and the
 *    server address is reduced to whether one is set.
 */
class Diagnostics(private val file: File, private val maxBytes: Long = MAX_BYTES) {

    @Volatile
    var logTranscripts: Boolean = false

    @Synchronized
    fun log(message: String) {
        runCatching {
            rotateIfNeeded()
            file.parentFile?.mkdirs()
            file.appendText("${stamp()} $message\n")
        }
    }

    /** Only writes the text itself when the user asked for transcript logging. */
    fun logTranscript(backend: String, elapsedMs: Long, text: String) {
        if (logTranscripts) {
            log("transcribed by $backend in ${elapsedMs}ms: $text")
        } else {
            log("transcribed by $backend in ${elapsedMs}ms, ${text.length} chars")
        }
    }

    fun read(maxChars: Int = 40_000): String {
        if (!file.exists()) return "(no log yet)"
        val text = runCatching { file.readText() }.getOrElse { return "(log unreadable)" }
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * A shareable report: what is configured, never what the secrets are.
     */
    fun report(
        appVersion: String,
        device: String,
        androidVersion: String,
        settings: Settings.Snapshot,
        secrets: Secrets,
        historyEntries: Int,
        vocabularyTerms: Int,
        lexiconInstalled: Boolean,
    ): String = buildString {
        appendLine("Lia for Android - problem report")
        appendLine("generated: ${stamp()}")
        appendLine("app: $appVersion")
        appendLine("device: $device, Android $androidVersion")
        appendLine()
        appendLine("[configuration]")
        appendLine("server address set: ${settings.serverUrl.isNotBlank()}")
        appendLine("server token saved: ${secrets.has(Secrets.Key.SERVER_TOKEN)}")
        appendLine("allow insecure ws: ${settings.allowInsecure}")
        appendLine("groq key saved: ${secrets.has(Secrets.Key.GROQ)}")
        appendLine("openai key saved: ${secrets.has(Secrets.Key.OPENAI)}")
        appendLine("openai model: ${settings.openAiModel}")
        appendLine("gemini key saved: ${secrets.has(Secrets.Key.GEMINI)}")
        appendLine("primary: ${settings.primary.name}, fallback: ${settings.fallback?.name ?: "none"}")
        appendLine("language: ${settings.language.name}")
        appendLine("hold to record: ${settings.holdToRecord}")
        appendLine("log transcripts: ${settings.logTranscripts}")
        appendLine("lexicon installed: $lexiconInstalled, fix: ${settings.lexiconFixEnabled}")
        appendLine("history entries: $historyEntries")
        appendLine("vocabulary terms: $vocabularyTerms")
        appendLine()
        appendLine("[log]")
        append(read())
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < maxBytes) return
        val previous = File(file.parentFile, file.name + ".1")
        previous.delete()
        file.renameTo(previous)
    }

    private fun stamp(): String = FORMAT.format(Date())

    companion object {
        const val MAX_BYTES = 512L * 1024
        private val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
