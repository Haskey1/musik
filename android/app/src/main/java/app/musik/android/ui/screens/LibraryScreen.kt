package app.musik.android.ui.screens

import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import app.musik.android.ui.Routes
import app.musik.android.ui.ScrollToTopOnReselect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.musik.android.data.AlbumItem
import app.musik.android.data.ArtistItem
import app.musik.android.data.FavoritesResponse
import app.musik.android.data.Track
import app.musik.android.data.jsonBody
import app.musik.android.data.nonEmpty
import app.musik.android.data.tracksWord
import app.musik.android.ui.App
import app.musik.android.ui.StatusBarScrim
import app.musik.android.ui.ArtistAvatar
import app.musik.android.ui.Artwork
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.LocalNavigator
import app.musik.android.ui.MusikColors
import app.musik.android.ui.TrackRow
import app.musik.android.ui.displayStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class LibraryModel {
    var tracks by mutableStateOf<List<Track>>(emptyList())
    var artists by mutableStateOf<List<ArtistItem>>(emptyList())
    var albums by mutableStateOf<List<AlbumItem>>(emptyList())
    var favorites by mutableStateOf(FavoritesResponse())
    var loaded by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var revision = -1

    suspend fun load() = coroutineScope {
        val api = App.api
        val t = async { runCatching { api.library() }.getOrNull() }
        val ar = async { runCatching { api.artists() }.getOrNull() }
        val al = async { runCatching { api.albums() }.getOrNull() }
        val f = async { runCatching { api.favorites() }.getOrNull() }
        t.await()?.let { tracks = it }
        // Artists come from the same track list as the Tracks tab (as on the web), so every
        // artist found there is found here too; the server's list only has indexed tracks.
        val grouped = groupArtists(tracks)
        artists = grouped.ifEmpty { ar.await() ?: artists }
        al.await()?.let { albums = it }
        f.await()?.let { favorites = it }
        loaded = true
    }

    companion object {
        var shared = LibraryModel()
            private set

        /** Signed out: the next account starts from an empty screen. */
        fun reset() {
            shared = LibraryModel()
        }

        /** Each credited artist, by number of tracks; the cover is the first track with artwork. */
        fun groupArtists(tracks: List<Track>): List<ArtistItem> {
            val by = LinkedHashMap<String, ArtistItem>()
            val hasArt = HashSet<String>()
            for (t in tracks) {
                val names = (t.artists ?: emptyList()).mapNotNull { it.nonEmpty }
                for (raw in names.ifEmpty { listOf(t.artist.nonEmpty ?: "Unknown") }) {
                    val name = raw.trim()
                    val key = name.lowercase()
                    var item = by[key] ?: ArtistItem(artist = name, tracks = 0, coverTrackId = t.id)
                    item = item.copy(tracks = (item.tracks ?: 0) + 1)
                    if (t.artwork != null && key !in hasArt) {
                        item = item.copy(coverTrackId = t.id)
                        hasArt.add(key)
                    }
                    by[key] = item
                }
            }
            return by.values.sortedWith(compareByDescending<ArtistItem> { it.tracks ?: 0 }.thenBy { it.artist.lowercase() })
        }
    }
}

private val segments = listOf("Все", "Треки", "Артисты", "Альбомы", "Любимое")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val model = LibraryModel.shared
    val state = App.state
    val scope = rememberCoroutineScope()
    var segment by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { if (!model.loaded) model.load() }
    LaunchedEffect(state.homeRevision) {
        if (model.loaded && model.revision != state.homeRevision) {
            model.revision = state.homeRevision
            runCatching { App.api.favorites() }.getOrNull()?.let { model.favorites = it }
        }
    }

    val q = query.trim()
    fun matches(s: String?) = q.isEmpty() || (s ?: "").contains(q, ignoreCase = true)
    fun matchesTrack(t: Track) = matches(t.title) || matches(t.artist) || matches(t.album)

    Box(Modifier.fillMaxSize()) {
    PullToRefreshBox(
        isRefreshing = model.refreshing,
        onRefresh = { scope.launch { model.refreshing = true; model.load(); model.refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        ScrollToTopOnReselect(Routes.LIBRARY) { listState.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Библиотека", style = displayStyle(32))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск", color = MusikColors.muted) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = MusikColors.muted) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Очистить") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MusikColors.surface,
                            unfocusedContainerColor = MusikColors.surface,
                            focusedBorderColor = MusikColors.accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                            cursorColor = MusikColors.accent,
                        ),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        segments.forEachIndexed { i, title ->
                            SegmentedButton(
                                selected = segment == i,
                                onClick = { segment = i },
                                shape = SegmentedButtonDefaults.itemShape(i, segments.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MusikColors.accent.copy(alpha = 0.22f),
                                    activeContentColor = MusikColors.accentBright,
                                    inactiveContainerColor = Color.Transparent,
                                ),
                                icon = {},
                            ) { Text(title, maxLines = 1, fontSize = 13.sp) }
                        }
                    }
                }
            }
            if (!model.loaded) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MusikColors.accent) } }
            }
            when (segment) {
                0 -> allSection(model, q, ::matches, ::matchesTrack) { segment = it }
                1 -> tracksSection(model.tracks.filter(::matchesTrack), q, model.loaded)
                2 -> artistsSection(model.artists.filter { matches(it.artist) })
                3 -> albumsSection(model.albums.filter { matches(it.album) || matches(it.artist) })
                else -> favoritesSection(model.favorites, ::matches, ::matchesTrack)
            }
        }
    }
        StatusBarScrim()
    }
}

private fun LazyListScope.tracksSection(list: List<Track>, query: String, loaded: Boolean) {
    itemsIndexed(list) { i, t ->
        TrackRow(t) {
            // Plays the visible list from the tapped row, so playback continues through it.
            val window = list.subList(i, minOf(list.size, i + 500))
            App.player.play(jsonBody("track_ids" to window.map { it.id }, "name" to if (query.isEmpty()) "Библиотека" else "Поиск: $query"))
        }
    }
    if (loaded && list.isEmpty()) item { EmptyHint(if (query.isEmpty()) "Треков нет" else "Ничего не найдено") }
}

private fun LazyListScope.artistsSection(list: List<ArtistItem>) {
    items(list) { a -> ArtistRow(a) }
}

@Composable
private fun ArtistRow(a: ArtistItem) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().clickable { nav.openArtist(a.artist) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtistAvatar(a.artist, a.coverTrackId, Modifier.size(46.dp), 96, CircleShape)
        Column(Modifier.weight(1f)) {
            Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tracksWord(a.tracks ?: 0), color = MusikColors.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (a.artist in App.state.favoriteArtists) Icon(Icons.Filled.Favorite, null, Modifier.size(14.dp), tint = MusikColors.accent)
    }
}

/** «Все»: one search over artists, albums and tracks, a short preview of each with a way into its tab. */
private fun LazyListScope.allSection(
    model: LibraryModel,
    query: String,
    matches: (String?) -> Boolean,
    matchesTrack: (Track) -> Boolean,
    openSegment: (Int) -> Unit,
) {
    val searching = query.isNotEmpty()
    val artists = model.artists.filter { matches(it.artist) }
    val albums = model.albums.filter { matches(it.album) || matches(it.artist) }
    val tracks = model.tracks.filter(matchesTrack)
    val limit = if (searching) 8 else 5
    val trackLimit = if (searching) 16 else 10

    if (artists.isNotEmpty()) {
        item { BigTitle("Артисты") }
        items(artists.take(limit)) { ArtistRow(it) }
        if (artists.size > limit) item { MoreButton("Все артисты · ${artists.size}") { openSegment(2) } }
    }
    if (albums.isNotEmpty()) {
        item { BigTitle("Альбомы") }
        items(albums.take(limit)) { AlbumRow(it) }
        if (albums.size > limit) item { MoreButton("Все альбомы · ${albums.size}") { openSegment(3) } }
    }
    if (tracks.isNotEmpty()) {
        item { BigTitle("Треки") }
        val shown = tracks.take(trackLimit)
        itemsIndexed(shown) { i, t ->
            TrackRow(t) {
                // the found tracks of this block, from the tapped one
                val window = tracks.subList(i, minOf(tracks.size, i + 500))
                App.player.play(jsonBody("track_ids" to window.map { it.id }, "name" to if (searching) "Поиск: $query" else "Библиотека"))
            }
        }
        if (tracks.size > trackLimit) item { MoreButton("Все треки · ${tracks.size}") { openSegment(1) } }
    }
    if (model.loaded && artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) {
        item { EmptyHint(if (searching) "Ничего не найдено" else "Библиотека пуста") }
    }
}

@Composable
private fun BigTitle(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = 6.dp), style = displayStyle(24))
}

@Composable
private fun MoreButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = MusikColors.accentBright, fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MusikColors.accentBright)
    }
}

private fun LazyListScope.albumsSection(list: List<AlbumItem>) {
    items(list) { a -> AlbumRow(a) }
}

@Composable
private fun AlbumRow(a: AlbumItem) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().clickable { nav.openAlbum(a.artist, a.album) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(a.coverTrackId, Modifier.size(46.dp), 96, a.album, RoundedCornerShape(7.dp))
        Column(Modifier.weight(1f)) {
            Text(a.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(a.artist.nonEmpty, a.tracks?.let { tracksWord(it) }, if (a.game == true) "OST" else null).joinToString(" · "),
                color = MusikColors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (a.key in App.state.favoriteAlbums) Icon(Icons.Filled.Favorite, null, Modifier.size(14.dp), tint = MusikColors.accent)
    }
}

private fun LazyListScope.favoritesSection(
    fav: FavoritesResponse,
    matches: (String?) -> Boolean,
    matchesTrack: (Track) -> Boolean,
) {
    // a heart taken back drops out of the list at once
    val tracks = (fav.tracks ?: emptyList()).map { it.asTrack() }.filter { it.id in App.state.favoriteTracks }.filter(matchesTrack)
    item { SectionTitle("Песни") }
    items(tracks, key = { it.id }) { t -> TrackRow(t, unlikable = true) { App.player.playMix("favorites", startTrackId = t.id) } }
    if (tracks.isEmpty()) item { EmptyHint("Жми ♥ на треке") }
    item { SectionTitle("Артисты") }
    val artists = (fav.artists ?: emptyList()).filter { matches(it.artist) }
    artistsSection(artists)
    if (artists.isEmpty()) item { EmptyHint("Нет любимых артистов") }
    item { SectionTitle("Альбомы") }
    val albums = (fav.albums ?: emptyList()).filter { matches(it.album) }
    items(albums) { AlbumRow(it) }
    if (albums.isEmpty()) item { EmptyHint("Нет любимых альбомов") }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

/** Search from the home screen: the same «Все» search as in the library, with a back arrow. */
@Composable
fun SearchScreen() {
    val model = LibraryModel.shared
    val nav = LocalNavigator.current
    var segment by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (!model.loaded) model.load()
    }
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }

    val q = query.trim()
    fun matches(s: String?) = q.isEmpty() || (s ?: "").contains(q, ignoreCase = true)
    fun matchesTrack(t: Track) = matches(t.title) || matches(t.artist) || matches(t.album)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { if (segment != 0) segment = 0 else nav.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; segment = 0 },
                    modifier = Modifier.weight(1f).focusRequester(requester),
                    placeholder = { Text("Артисты, альбомы, треки", color = MusikColors.muted) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MusikColors.muted) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Очистить") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MusikColors.surface,
                        unfocusedContainerColor = MusikColors.surface,
                        focusedBorderColor = MusikColors.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                        cursorColor = MusikColors.accent,
                    ),
                )
            }
        }
        if (!model.loaded) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MusikColors.accent) } }
        }
        when (segment) {
            0 -> allSection(model, q, ::matches, ::matchesTrack) { segment = it }
            1 -> tracksSection(model.tracks.filter(::matchesTrack), q, model.loaded)
            2 -> artistsSection(model.artists.filter { matches(it.artist) })
            else -> albumsSection(model.albums.filter { matches(it.album) || matches(it.artist) })
        }
    }
}
