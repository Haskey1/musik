package app.musik.android.ui.screens

import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import app.musik.android.ui.Routes
import app.musik.android.ui.ScrollToTopOnReselect
import androidx.compose.foundation.background
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
import app.musik.android.data.CatalogIndex
import app.musik.android.data.CatalogResults
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.launch

private val segments = listOf("Все", "Треки", "Артисты", "Альбомы", "Любимое")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val model = LibraryModel.shared
    val state = App.state
    val scope = rememberCoroutineScope()
    var segment by rememberSaveable { mutableStateOf(1) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(model) { model.load() }
    LaunchedEffect(state.homeRevision) {
        if (model.loaded && model.revision != state.homeRevision) {
            model.revision = state.homeRevision
            model.refreshFavorites()
        }
    }

    val q = query.trim()
    val results by rememberCatalogResults(model.index, q)
    val favorites by rememberCatalogResults(model.favoriteIndex, q, state.favoriteTracks)

    Box(Modifier.fillMaxSize()) {
    PullToRefreshBox(
        isRefreshing = model.refreshing,
        onRefresh = { scope.launch { model.refreshing = true; try { model.load(force = true) } finally { model.refreshing = false } } },
        modifier = Modifier.fillMaxSize(),
    ) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        ScrollToTopOnReselect(Routes.LIBRARY) { listState.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Библиотека", style = displayStyle(32))
                    val summary = when (segment) {
                        1 -> tracksWord(results.tracks.size)
                        2 -> "${model.artists.size} артистов"
                        3 -> "${model.albums.size} альбомов"
                        4 -> "Твоя любимая музыка"
                        else -> "Треки, артисты и альбомы"
                    }
                    Text(summary, color = MusikColors.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Вся музыка с твоего сервера", color = MusikColors.muted.copy(alpha = 0.72f), fontSize = 12.sp)
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
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().background(MusikColors.surfaceHigh.copy(alpha = 0.82f), RoundedCornerShape(16.dp)).padding(6.dp)) {
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
            if (!model.loaded && model.loading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MusikColors.accent) } }
            }
            model.error?.let { message -> item("load-error") { EmptyHint(message); MoreButton("Попробовать снова") { scope.launch { model.load(force = true) } } } }
            when (segment) {
                0 -> allSection(results, q, model.loaded) { segment = it }
                1 -> tracksSection(results.tracks, q, model.loaded)
                2 -> artistsSection(results.artists)
                3 -> albumsSection(results.albums)
                else -> favoritesSection(favorites)
            }
        }
    }
        StatusBarScrim()
    }
}

private fun LazyListScope.tracksSection(list: List<Track>, query: String, loaded: Boolean) {
    itemsIndexed(list, key = { _, t -> "track:${t.id}" }, contentType = { _, _ -> "track" }) { i, t ->
        TrackRow(t, carded = true, showsMenu = true) {
            // Plays the visible list from the tapped row, so playback continues through it.
            val window = list.subList(i, minOf(list.size, i + 500))
            App.player.play(jsonBody("track_ids" to window.map { it.id }, "name" to if (query.isEmpty()) "Библиотека" else "Поиск: $query"))
        }
    }
    if (loaded && list.isEmpty()) item { EmptyHint(if (query.isEmpty()) "Треков нет" else "Ничего не найдено") }
}

private fun LazyListScope.artistsSection(list: List<ArtistItem>) {
    items(list, key = { "artist:${it.artist}" }, contentType = { "artist" }) { a -> ArtistRow(a) }
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
    results: CatalogResults,
    query: String,
    loaded: Boolean,
    openSegment: (Int) -> Unit,
) {
    val searching = query.isNotEmpty()
    val artists = results.artists
    val albums = results.albums
    val tracks = results.tracks
    val limit = if (searching) 8 else 5
    val trackLimit = if (searching) 16 else 10

    if (artists.isNotEmpty()) {
        item { BigTitle("Артисты") }
        items(artists.take(limit), key = { "artist:${it.artist}" }, contentType = { "artist" }) { ArtistRow(it) }
        if (artists.size > limit) item { MoreButton("Все артисты · ${artists.size}") { openSegment(2) } }
    }
    if (albums.isNotEmpty()) {
        item { BigTitle("Альбомы") }
        items(albums.take(limit), key = { "album:${it.key}" }, contentType = { "album" }) { AlbumRow(it) }
        if (albums.size > limit) item { MoreButton("Все альбомы · ${albums.size}") { openSegment(3) } }
    }
    if (tracks.isNotEmpty()) {
        item { BigTitle("Треки") }
        val shown = tracks.take(trackLimit)
        itemsIndexed(shown, key = { _, t -> "track:${t.id}" }, contentType = { _, _ -> "track" }) { i, t ->
            TrackRow(t) {
                // the found tracks of this block, from the tapped one
                val window = tracks.subList(i, minOf(tracks.size, i + 500))
                App.player.play(jsonBody("track_ids" to window.map { it.id }, "name" to if (searching) "Поиск: $query" else "Библиотека"))
            }
        }
        if (tracks.size > trackLimit) item { MoreButton("Все треки · ${tracks.size}") { openSegment(1) } }
    }
    if (loaded && artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) {
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
    items(list, key = { "album:${it.key}" }, contentType = { "album" }) { a -> AlbumRow(a) }
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

private fun LazyListScope.favoritesSection(results: CatalogResults) {
    item { SectionTitle("Песни") }
    items(results.tracks, key = { "track:${it.id}" }, contentType = { "track" }) { t ->
        TrackRow(t, unlikable = true) { App.player.playMix("favorites", startTrackId = t.id) }
    }
    if (results.tracks.isEmpty()) item { EmptyHint("Жми ♥ на треке") }
    item { SectionTitle("Артисты") }
    artistsSection(results.artists)
    if (results.artists.isEmpty()) item { EmptyHint("Нет любимых артистов") }
    item { SectionTitle("Альбомы") }
    albumsSection(results.albums)
    if (results.albums.isEmpty()) item { EmptyHint("Нет любимых альбомов") }
}

/** Search runs off the UI thread; rapid input cancels outdated work after a short debounce. */
@Composable
private fun rememberCatalogResults(index: CatalogIndex, query: String, favoriteIds: Set<Int>? = null) =
    produceState(if (query.isBlank()) index.all else CatalogResults(), index, query, favoriteIds) {
        if (query.isNotBlank()) delay(180)
        val found = index.search(query)
        value = if (favoriteIds == null) found else withContext(Dispatchers.Default) {
            found.copy(tracks = found.tracks.filter { it.id in favoriteIds })
        }
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
    LaunchedEffect(model) { model.load() }
    val scope = rememberCoroutineScope()
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }

    val q = query.trim()
    val results by rememberCatalogResults(model.index, q)

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
        if (!model.loaded && model.loading) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MusikColors.accent) } }
        }
        model.error?.let { message -> item("load-error") { EmptyHint(message); MoreButton("Попробовать снова") { scope.launch { model.load(force = true) } } } }
        if (q.isEmpty()) {
            item("search-hint") { EmptyHint("Ищи по трекам, артистам и альбомам сразу") }
        } else when (segment) {
            0 -> allSection(results, q, model.loaded) { segment = it }
            1 -> tracksSection(results.tracks, q, model.loaded)
            2 -> artistsSection(results.artists)
            else -> albumsSection(results.albums)
        }
    }
}



