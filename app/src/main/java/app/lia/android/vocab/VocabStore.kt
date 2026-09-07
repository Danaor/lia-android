package app.lia.android.vocab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The desktop's `vocabulary.json` (plan 5.2), read and written in place.
 *
 * Terms are kept as raw [JsonObject]s so every field the desktop writes -
 * timestamps, sources, counters this app never looks at - survives a round
 * trip. Only the handful of fields the phone actually needs are typed.
 */
class VocabStore private constructor(
    private var root: JsonObject,
) {

    data class Term(
        val term: String,
        val kind: String,
        val status: String,
        val starred: Boolean,
        val countUsed: Int,
        val countCorpus: Int,
    ) {
        val score: Int get() = countUsed * 3 + countCorpus
        val isManual: Boolean get() = kind == "manual" || starred
        val isApproved: Boolean get() = status == "approved"
    }

    val terms: List<Term>
        get() = (root["terms"] as? JsonArray).orEmpty().mapNotNull { toTerm(it as? JsonObject) }

    val corrections: List<app.lia.android.text.Corrections.Pair>
        get() = (root["corrections"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val wrong = obj["wrong"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val right = obj["right"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (wrong.isEmpty() || right.isEmpty()) null
            else app.lia.android.text.Corrections.Pair(wrong, right)
        }

    val oovCandidates: List<String>
        get() = (root["oov_candidates"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.get("word")?.jsonPrimitive?.contentOrNull
        }

    /** Approved terms, used both for the prompt and as the lexicon's protected set. */
    val approvedTerms: List<Term> get() = terms.filter { it.isApproved }

    fun composedPrompt(budgetChars: Int = PromptComposer.BUDGET_CHARS): String =
        PromptComposer.compose(approvedTerms, budgetChars)

    fun promptTerms(budgetChars: Int = PromptComposer.BUDGET_CHARS): List<String> =
        PromptComposer.pick(approvedTerms, budgetChars)

    fun addManualTerm(term: String): Boolean {
        val clean = term.trim()
        if (clean.isEmpty()) return false
        if (terms.any { it.term.equals(clean, ignoreCase = true) }) return false
        val updated = buildJsonArray {
            (root["terms"] as? JsonArray)?.forEach { add(it) }
            add(
                buildJsonObject {
                    put("term", clean)
                    put("kind", "manual")
                    put("status", "approved")
                    put("starred", true)
                    put("count_used", 0)
                    put("count_corpus", 0)
                    put("source", "android")
                }
            )
        }
        root = JsonObject(root.toMutableMap().apply { put("terms", updated) })
        return true
    }

    fun removeTerm(term: String): Boolean {
        val before = (root["terms"] as? JsonArray).orEmpty()
        val after = before.filterNot {
            (it as? JsonObject)?.get("term")?.jsonPrimitive?.contentOrNull
                ?.equals(term, ignoreCase = true) == true
        }
        if (after.size == before.size) return false
        root = JsonObject(root.toMutableMap().apply { put("terms", JsonArray(after)) })
        return true
    }

    fun addCorrection(wrong: String, right: String): Boolean {
        val w = wrong.trim()
        val r = right.trim()
        if (w.isEmpty() || r.isEmpty() || w.equals(r, ignoreCase = true)) return false
        val updated = buildJsonArray {
            (root["corrections"] as? JsonArray)?.forEach { element ->
                val existing = (element as? JsonObject)
                    ?.get("wrong")?.jsonPrimitive?.contentOrNull
                if (!existing.equals(w, ignoreCase = true)) add(element)
            }
            add(
                buildJsonObject {
                    put("wrong", w)
                    put("right", r)
                    put("source", "android")
                }
            )
        }
        root = JsonObject(root.toMutableMap().apply { put("corrections", updated) })
        return true
    }

    fun removeCorrection(wrong: String): Boolean {
        val before = (root["corrections"] as? JsonArray).orEmpty()
        val after = before.filterNot {
            (it as? JsonObject)?.get("wrong")?.jsonPrimitive?.contentOrNull
                ?.equals(wrong, ignoreCase = true) == true
        }
        if (after.size == before.size) return false
        root = JsonObject(root.toMutableMap().apply { put("corrections", JsonArray(after)) })
        return true
    }

    fun toJson(): String = JSON.encodeToString(JsonObject.serializer(), root)

    fun save(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun toTerm(obj: JsonObject?): Term? {
        obj ?: return null
        val term = obj["term"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (term.isEmpty()) return null
        return Term(
            term = term,
            kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "manual",
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "approved",
            starred = obj["starred"]?.jsonPrimitive?.booleanOrNullSafe() ?: false,
            countUsed = obj["count_used"]?.intOrZero() ?: 0,
            countCorpus = obj["count_corpus"]?.intOrZero() ?: 0,
        )
    }

    companion object {
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        private val EMPTY = buildJsonObject {
            put("version", 1)
            put("terms", JsonArray(emptyList()))
            put("corrections", JsonArray(emptyList()))
            put("oov_candidates", JsonArray(emptyList()))
            put("flags", JsonObject(emptyMap()))
        }

        fun empty(): VocabStore = VocabStore(EMPTY)

        fun parse(text: String): VocabStore {
            val element = Json.parseToJsonElement(text)
            val obj = element as? JsonObject ?: throw IllegalArgumentException(
                "vocabulary.json must contain a JSON object"
            )
            if (obj["terms"] !is JsonArray && obj["corrections"] !is JsonArray) {
                throw IllegalArgumentException("not a Lia vocabulary file (no terms/corrections)")
            }
            return VocabStore(obj)
        }

        fun load(file: File): VocabStore =
            if (file.exists()) runCatching { parse(file.readText()) }.getOrElse { empty() }
            else empty()
    }
}

private fun JsonPrimitive.booleanOrNullSafe(): Boolean? =
    when (contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

private fun kotlinx.serialization.json.JsonElement.intOrZero(): Int =
    runCatching { jsonPrimitive.int }.getOrElse {
        runCatching { jsonPrimitive.contentOrNull?.toDouble()?.toInt() ?: 0 }.getOrDefault(0)
    }

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
