package app.musik.android.ui.screens

import androidx.compose.runtime.*
import app.musik.android.data.*
import app.musik.android.ui.App
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cached across both screens. Leaving a route does not cancel its in-flight catalog download. */
class LibraryModel(
    private val apiProvider: () -> Api = { App.api },
    private val modelScope: CoroutineScope = MainScope(),
) {
    var index by mutableStateOf(CatalogIndex.Empty)
        private set
    var favoriteIndex by mutableStateOf(CatalogIndex.Empty)
        private set
    val tracks get() = index.all.tracks
    val artists get() = index.all.artists
    val albums get() = index.all.albums
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
    var revision = -1
    private var request: Deferred<Unit>? = null
    private val requestMutex = Mutex()

    suspend fun load(force: Boolean = false) {
        val job = requestMutex.withLock {
            request?.takeIf { it.isActive } ?: if (loaded && !force) null else {
                modelScope.async(start = CoroutineStart.LAZY) { download() }.also {
                    request = it
                    it.start()
                }
            }
        }
        job?.await()
    }

    private suspend fun download() {
        loading = true
        error = null
        try {
            coroutineScope {
                val api = apiProvider()
                val tracks = async { api.library() }
                val albums = async { api.albums() }
                val favorites = async { optional { api.favorites() } }
                val downloaded = tracks.await()
                val grouped = withContext(Dispatchers.Default) { groupArtists(downloaded) }
                // Normally redundant: the track catalog already supplies every credited artist.
                val artists = if (grouped.isNotEmpty()) grouped else optional { api.artists() }.orEmpty()
                index = CatalogIndex.create(downloaded, artists, albums.await())
                loaded = true
                favorites.await()?.let { installFavorites(it) }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "Не удалось загрузить библиотеку"
        } finally { loading = false }
    }

    suspend fun refreshFavorites() {
        optional { apiProvider().favorites() }?.let { installFavorites(it) }
    }

    private suspend fun installFavorites(favorites: FavoritesResponse) {
        val tracks = withContext(Dispatchers.Default) { favorites.tracks.orEmpty().map { it.asTrack() } }
        favoriteIndex = CatalogIndex.create(tracks, favorites.artists.orEmpty(), favorites.albums.orEmpty())
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block()
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) { null }

    companion object {
        var shared = LibraryModel()
            private set
        fun reset() {
            shared.modelScope.cancel()
            shared = LibraryModel()
        }

        /** Each credited artist, counted without allocating a new DTO per track. */
        fun groupArtists(tracks: List<Track>): List<ArtistItem> {
            class Artist(val name: String, var count: Int, var coverId: Int, var hasArtwork: Boolean)
            val by = LinkedHashMap<String, Artist>()
            for (track in tracks) {
                val names = track.artists.orEmpty().mapNotNull { it.nonEmpty }
                for (raw in names.ifEmpty { listOf(track.artist.nonEmpty ?: "Unknown") }) {
                    val name = raw.trim()
                    val key = name.lowercase(java.util.Locale.ROOT)
                    val artist = by.getOrPut(key) { Artist(name, 0, track.id, false) }
                    artist.count++
                    if (track.artwork != null && !artist.hasArtwork) {
                        artist.coverId = track.id
                        artist.hasArtwork = true
                    }
                }
            }
            return by.values.map { ArtistItem(artist = it.name, tracks = it.count, coverTrackId = it.coverId) }
                .sortedWith(compareByDescending<ArtistItem> { it.tracks ?: 0 }.thenBy { it.artist.lowercase(java.util.Locale.ROOT) })
        }
    }
}
