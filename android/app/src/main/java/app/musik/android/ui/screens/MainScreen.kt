package app.musik.android.ui.screens

import app.musik.android.data.Haptics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.musik.android.AppState
import app.musik.android.DeepLink
import app.musik.android.DeepLinks
import app.musik.android.data.jsonBody
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import app.musik.android.ui.App
import app.musik.android.ui.Artwork
import app.musik.android.ui.LocalNavigator
import app.musik.android.ui.MusikColors
import app.musik.android.ui.Navigator
import app.musik.android.ui.Routes

private data class Tab(val route: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Filled.Home),
    Tab(Routes.LIBRARY, "Библиотека", Icons.Filled.LibraryMusic),
    Tab(Routes.DOWNLOADS, "Сохранённые", Icons.Filled.DownloadForOffline),
    Tab(Routes.PROFILE, "Профиль", Icons.Filled.Person),
)

@Composable
fun MainScreen(state: AppState) {
    val nav = rememberNavController()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val navigator = remember(nav) { Navigator(nav) { showPlayer = false } }
    val player = App.player
    val context = androidx.compose.ui.platform.LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // the tab the user is in, also while a page of it (artist, album, search) is open
    var activeTab by rememberSaveable { mutableStateOf(Routes.HOME) }
    LaunchedEffect(route) { if (tabs.any { it.route == route }) activeTab = route!! }

    LaunchedEffect(Unit) {
        snapshotFlow { DeepLinks.pending }.filterNotNull().collect { link ->
            DeepLinks.pending = null
            when (link) {
                is DeepLink.Track -> {
                    player.play(jsonBody("track_id" to link.id))
                    showPlayer = true
                    // if nothing started (no such track, no network), don't leave the player armed
                    launch { delay(10_000); if (player.current == null) showPlayer = false }
                }
                is DeepLink.Artist -> navigator.openArtist(link.name)
                is DeepLink.Album -> navigator.openAlbum(link.artist, link.album)
            }
        }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    Column {
                        AnimatedVisibility(
                            visible = player.current != null,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                        ) {
                            MiniPlayer { showPlayer = true }
                        }
                        NavigationBar(containerColor = MusikColors.surface.copy(alpha = 0.97f), tonalElevation = 0.dp) {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = activeTab == tab.route,
                                    onClick = {
                                        if (activeTab == tab.route) navigator.reselect(tab.route, route == tab.route)
                                        else navigator.tab(tab.route)
                                    },
                                    icon = { Icon(tab.icon, null) },
                                    label = { Text(tab.title, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MusikColors.accentBright,
                                        selectedTextColor = MusikColors.accentBright,
                                        indicatorColor = MusikColors.accent.copy(alpha = 0.18f),
                                        unselectedIconColor = MusikColors.muted,
                                        unselectedTextColor = MusikColors.muted,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                    composable(Routes.HOME) { HomeScreen() }
                    composable(Routes.LIBRARY) { LibraryScreen() }
                    composable(Routes.DOWNLOADS) { DownloadsScreen() }
                    composable(Routes.PROFILE) { ProfileScreen() }
                    composable(Routes.SEARCH) { SearchScreen() }
                    composable(Routes.ARTIST, arguments = listOf(navArgument("name") { type = NavType.StringType })) { e ->
                        ArtistScreen(e.arguments?.getString("name") ?: "")
                    }
                    composable(
                        Routes.ALBUM,
                        arguments = listOf(
                            navArgument("artist") { type = NavType.StringType; defaultValue = "" },
                            navArgument("album") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { e ->
                        AlbumScreen(e.arguments?.getString("artist")?.takeIf { it.isNotEmpty() }, e.arguments?.getString("album") ?: "")
                    }
                }
            }

            AnimatedVisibility(
                visible = showPlayer && player.current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NowPlayingScreen(onClose = { showPlayer = false })
            }
        }
        BackHandler(enabled = showPlayer) { showPlayer = false }
    }
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val player = App.player
    val track = player.current ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(MusikColors.surfaceHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Artwork(track.id, Modifier.size(42.dp), 96, track.displayTitle, RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f)) {
                Text(track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    if (player.offlineMode) "офлайн · ${track.displayArtist}" else track.displayArtist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MusikColors.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { Haptics.press(context, App.settings); player.togglePlay() }) {
                if (player.isBuffering || player.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Играть/пауза")
                }
            }
            IconButton(onClick = { Haptics.press(context, App.settings); player.skip() }, enabled = !player.busy) {
                Icon(Icons.Filled.SkipNext, "Дальше")
            }
        }
        MiniProgress()
    }
}

@Composable
private fun MiniProgress() {
    val clock = App.player.clock
    val fraction = if (clock.duration > 0) (clock.position / clock.duration).toFloat().coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MusikColors.accent))
    }
}
