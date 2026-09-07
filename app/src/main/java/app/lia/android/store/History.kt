package app.lia.android.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Transcript history as one JSON file, capped like the desktop's (1000
 * entries, newest first).
 *
 * Transcript text is stored because history IS the feature; the separate
 * `log_transcripts` switch governs the diagnostic LOG, which stays off by
 * default (plan 1.4).
 */
class History(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {

    @Serializable
    data class Entry(
        val text: String,
        val backend: String,
        val timestamp: Long,
        val elapsedMs: Long = 0,
        val source: String = "dictation",
        val fileName: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Synchronized
    fun all(): List<Entry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Entry.serializer()), file.readText())
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun add(entry: Entry): List<Entry> {
        val updated = (listOf(entry) + all()).take(maxEntries)
        write(updated)
        return updated
    }

    @Synchronized
    fun remove(timestamp: Long): List<Entry> {
        val updated = all().filterNot { it.timestamp == timestamp }
        write(updated)
        return updated
    }

    @Synchronized
    fun clear() {
        write(emptyList())
    }

    fun search(query: String): List<Entry> {
        val needle = query.trim()
        if (needle.isEmpty()) return all()
        return all().filter { it.text.contains(needle, ignoreCase = true) }
    }

    private fun write(entries: List<Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000
    }
}
