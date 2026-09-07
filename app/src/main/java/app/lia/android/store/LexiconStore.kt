package app.lia.android.store

import app.lia.android.text.Lexicon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The Hebrew dictionary behind the spelling guard (plan 5.3).
 *
 * It is an hspell-derived Hunspell word list under the **AGPL**, so it is
 * never bundled and never committed: the user opts in, the app downloads it
 * once, verifies a pinned sha256, and stores it in app-private storage. Lia
 * itself stays MIT.
 */
class LexiconStore(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient(),
) {

    val dictFile: File get() = File(directory, "he_IL.dic")
    val licenseFile: File get() = File(directory, "LICENSE.hspell.txt")

    val isInstalled: Boolean get() = dictFile.exists() && dictFile.length() > 0

    sealed interface DownloadResult {
        data class Installed(val words: Int, val bytes: Long) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    suspend fun download(): DownloadResult = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File(directory, "he_IL.dic.part")
        try {
            val body = fetch(DICT_URL) ?: return@withContext DownloadResult.Failed(
                "Could not download the dictionary - check the connection."
            )
            val actual = sha256(body)
            if (actual != DICT_SHA256) {
                return@withContext DownloadResult.Failed(
                    "Dictionary changed upstream - not installed."
                )
            }
            temp.writeBytes(body)
            if (!temp.renameTo(dictFile)) {
                dictFile.writeBytes(body)
                temp.delete()
            }
            fetch(LICENSE_URL)?.let { licenseFile.writeBytes(it) }
            val words = countWords(dictFile)
            DownloadResult.Installed(words, dictFile.length())
        } catch (e: Exception) {
            DownloadResult.Failed(e.message ?: "Download failed.")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun remove() {
        dictFile.delete()
        licenseFile.delete()
    }

    /**
     * Load the word list off the main thread.
     *
     * The forms are kept in a SORTED ARRAY and looked up by binary search: for
     * ~342k Hebrew strings that is roughly a third of the memory a HashSet
     * costs, and a lookup is still microseconds. A phone with room to spare
     * gains nothing measurable from the hash set.
     */
    suspend fun load(): Lexicon = withContext(Dispatchers.IO) {
        if (!isInstalled) return@withContext Lexicon(null)
        val words = ArrayList<String>(350_000)
        dictFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (index == 0) return@forEachIndexed          // entry count
                val word = line.substringBefore('/').trim()
                if (word.isNotEmpty()) words.add(word)
            }
        }
        val sorted = words.toTypedArray()
        sorted.sort()
        Lexicon(SortedWordSet(sorted))
    }

    private class SortedWordSet(private val words: Array<String>) : Lexicon.WordSet {
        override operator fun contains(word: String): Boolean =
            words.binarySearch(word) >= 0

        override val size: Int get() = words.size
    }

    private fun fetch(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.bytes()
        }
    }

    private fun countWords(file: File): Int =
        file.bufferedReader(Charsets.UTF_8).useLines { it.count() - 1 }.coerceAtLeast(0)

    companion object {
        /** Commit-pinned so the content cannot move under the hash. */
        const val DICT_URL =
            "https://raw.githubusercontent.com/wooorm/dictionaries/" +
                "8cfea406b505e4d7df52d5a19bce525df98c54ab/dictionaries/he/index.dic"

        const val LICENSE_URL =
            "https://raw.githubusercontent.com/wooorm/dictionaries/" +
                "8cfea406b505e4d7df52d5a19bce525df98c54ab/dictionaries/he/license"

        const val DICT_SHA256 =
            "9bd95042a927e13ab422231e355caa8a7018456e7c49b889f2e6c62ddb5a0552"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
