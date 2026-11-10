package app.musik.android.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val PROFILE = "profile"
    const val SEARCH = "search"
    const val ARTIST = "artist/{name}"
    const val ALBUM = "album?artist={artist}&album={album}"

    fun artist(name: String) = "artist/" + Uri.encode(name)
    fun album(artist: String?, album: String) = "album?artist=" + Uri.encode(artist ?: "") + "&album=" + Uri.encode(album)
}

/** Opens screens from anywhere, including the player (which closes itself first). */
class Navigator(private val nav: NavHostController, private val closePlayer: () -> Unit) {
    fun openArtist(name: String) {
        closePlayer()
        nav.navigate(Routes.artist(name))
    }

    fun openAlbum(artist: String?, album: String) {
        closePlayer()
        nav.navigate(Routes.album(artist, album))
    }

    fun openSearch() {
        closePlayer()
        nav.navigate(Routes.SEARCH)
    }

    fun back() = nav.popBackStack()

    /** Tapping the active tab again: back to its first screen, or to the top of it. */
    fun reselect(route: String, atRoot: Boolean) {
        if (atRoot) TabScroll.requests.tryEmit(route) else nav.popBackStack(route, false)
    }

    fun tab(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator is not provided") }

/** Scroll-to-top requests, sent when the current tab is tapped again. */
object TabScroll {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
}

@androidx.compose.runtime.Composable
fun ScrollToTopOnReselect(route: String, onTop: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(route) {
        TabScroll.requests.collect { if (it == route) onTop() }
    }
}
