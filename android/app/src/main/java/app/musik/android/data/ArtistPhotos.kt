package app.musik.android.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Artist photos the server found online (`musik artist-photos`): normalized artist
 * name → image path. Artists without a photo keep showing a track cover.
 */
class ArtistPhotos(private val settings: Settings) {
    var paths by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var loadedAt = 0L

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    init {
        // Last known list, so photos show up at once on launch (and offline, from the image cache).
        settings.getString("artistPhotos")?.let { saved ->
            runCatching { paths = JsonFormat.decodeFromString(mapSerializer, saved) }
        }
    }

    companion object {
        /** Same rule as the server and the web UI: spaces collapsed, lower case. */
        fun key(name: String): String = name.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
    }

    fun url(api: Api, artist: String?, width: Int): String? {
        val path = artist?.let { paths[key(it)] } ?: return null
        return runCatching { api.url(path, listOf("w" to width.toString())).toString() }.getOrNull()
    }

    /** The photo as found online (Deezer gives 1000 px), for a full-width header. */
    fun fullUrl(api: Api, artist: String?): String? {
        val path = artist?.let { paths[key(it)] } ?: return null
        return runCatching { api.url(path, listOf("full" to "1")).toString() }.getOrNull()
    }

    /** Fetches the list; at most once in ten minutes unless forced. */
    suspend fun refresh(api: Api, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - loadedAt < 600_000) return
        val fresh = runCatching { api.artistPhotos() }.getOrNull() ?: return
        loadedAt = now
        if (fresh == paths) return
        paths = fresh
        settings.putString("artistPhotos", JsonFormat.encodeToString(mapSerializer, fresh))
    }

    fun clear() {
        paths = emptyMap()
        loadedAt = 0
        settings.putString("artistPhotos", null)
    }
}
