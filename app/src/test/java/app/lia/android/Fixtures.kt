package app.lia.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Access to the Phase 0 captures in `src/test/resources/fixtures`.
 *
 * These are REAL responses recorded from the four backends on 2026-09-07 with
 * the host, token and keys scrubbed. Parser tests replay them so a contract
 * change shows up as a red test rather than as a mystery in the field.
 */
object Fixtures {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(name: String): JsonObject {
        val stream = Fixtures::class.java.classLoader
            ?.getResourceAsStream("fixtures/$name")
            ?: error("missing fixture $name")
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    fun results(name: String): List<JsonObject> =
        (load(name)["results"] as JsonArray).map { it.jsonObject }
}
