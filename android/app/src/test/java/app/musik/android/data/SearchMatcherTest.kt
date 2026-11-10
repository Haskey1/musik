package app.musik.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {
    private val track = Track(
        id = 1,
        title = "Béyoncé Élan",
        artist = "Primary Artist",
        album = "Café Del Mar",
        artists = listOf("Primary Artist", "José CCC"),
    )

    @Test
    fun allWordsMatchInAnyOrderAcrossTrackFields() {
        assertTrue(SearchMatcher.track("mar elan primary", track))
        assertTrue(SearchMatcher.track("ccc cafe", track))
    }

    @Test
    fun comparisonIgnoresDiacriticsAndCase() {
        assertTrue(SearchMatcher.track("JOSE beyonce CAFE", track))
    }

    @Test
    fun everyQueryWordIsRequired() {
        assertFalse(SearchMatcher.track("elan missing", track))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(SearchMatcher.track("  ", track))
    }
}
