package app.musik.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogIndexTest {
    @Test
    fun indexedSearchPreservesCrossFieldAliasesAndAccents() = runBlocking {
        val track = Track(1, title = "Béyoncé Élan", artist = "Primary Artist", album = "Café Del Mar", artists = listOf("José CCC"))
        val index = CatalogIndex.create(listOf(track), listOf(ArtistItem("José CCC")), listOf(AlbumItem(album = "Café Del Mar", artist = "Beyoncé")))
        assertEquals(listOf(track), index.search("ccc cafe elan").tracks)
        assertEquals(1, index.search("JOSE").artists.size)
        assertEquals(1, index.search("beyonce cafe").albums.size)
        assertTrue(index.search("elan missing").tracks.isEmpty())
    }

    @Test
    fun blankQueryReusesCatalogWithoutCopyingEveryList() = runBlocking {
        val tracks = listOf(Track(1, title = "One"), Track(2, title = "Two"))
        val index = CatalogIndex.create(tracks, emptyList(), emptyList())
        assertSame(index.all, index.search("  "))
        assertSame(tracks, index.search("").tracks)
        index.search("one")
        assertEquals(2, index.search("").tracks.size)
    }

    @Test
    fun largeCatalogResultsMatchUnindexedSearch() = runBlocking {
        val tracks = (1..10_000).map { Track(it, title = "Track $it", artist = "Artist ${it % 100}", album = "Café ${it % 20}") }
        val index = CatalogIndex.create(tracks, emptyList(), emptyList())
        for (query in listOf("track 42", "cafe 15", "ARTIST 99", "not found")) {
            assertEquals(tracks.filter { SearchMatcher.track(query, it) }, index.search(query).tracks)
        }
    }
}
