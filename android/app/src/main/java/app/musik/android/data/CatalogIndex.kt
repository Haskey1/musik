package app.musik.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One immutable snapshot shared by search and library. Blank search returns these lists directly. */
data class CatalogResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
)

class CatalogIndex private constructor(
    val all: CatalogResults,
    private val trackText: List<String>,
    private val artistText: List<String>,
    private val albumText: List<String>,
) {
    suspend fun search(query: String): CatalogResults = withContext(Dispatchers.Default) {
        val words = SearchMatcher.words(query)
        if (words.isEmpty()) return@withContext all
        val context = currentCoroutineContext()
        fun <T> matching(items: List<T>, text: List<String>): List<T> {
            val found = ArrayList<T>()
            for (i in items.indices) {
                if (i % 256 == 0) context.ensureActive()
                if (words.all { it in text[i] }) found.add(items[i])
            }
            return found
        }
        CatalogResults(matching(all.tracks, trackText), matching(all.artists, artistText), matching(all.albums, albumText))
    }

    companion object {
        val Empty = CatalogIndex(CatalogResults(), emptyList(), emptyList(), emptyList())

        /** Run off the UI thread, once for each downloaded catalog revision. */
        suspend fun create(tracks: List<Track>, artists: List<ArtistItem>, albums: List<AlbumItem>): CatalogIndex = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            fun <T> prepare(items: List<T>, text: (T) -> String): List<String> = items.mapIndexed { i, item ->
                if (i % 256 == 0) context.ensureActive()
                SearchMatcher.fold(text(item))
            }
            CatalogIndex(CatalogResults(tracks, artists, albums), prepare(tracks, SearchMatcher::trackText),
                prepare(artists) { it.artist }, prepare(albums) { "${it.album} ${it.artist.orEmpty()}" })
        }
    }
}
