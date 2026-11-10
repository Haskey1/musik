package app.musik.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
private data class MoodContext(@SerialName("context_id") val contextId: String, val name: String, val kind: String = "")
@Serializable
private data class MoodContexts(val contexts: List<MoodContext> = emptyList())

/** Same named contexts and preset keys as iOS and the web client. */
suspend fun Api.radioStartMood(name: String, seed: Int? = null): PlayPayload {
    val contexts = get<MoodContexts>("/api/contexts")
    val context = contexts.contexts.firstOrNull { it.kind == "mood" && it.name.equals(name, ignoreCase = true) }
        ?: post<MoodContext>("/api/contexts", jsonBody("name" to name, "kind" to "mood", "influence" to 1, "learning_enabled" to true))
    val key = when (name.lowercase()) {
        "бодрое" -> "energy"
        "спокойное" -> "calm"
        "весёлое", "веселое" -> "happy"
        "грустное" -> "sad"
        else -> name.lowercase()
    }
    return post("/api/radio/start", jsonBody("seed_track_id" to seed, "context_ids" to listOf(context.contextId), "mood" to key))
}
