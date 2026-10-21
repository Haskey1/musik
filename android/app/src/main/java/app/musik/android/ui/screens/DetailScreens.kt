package app.musik.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musik.android.data.AlbumItem
import app.musik.android.data.ArtistItem
import app.musik.android.data.Track
import app.musik.android.data.albumKey
import app.musik.android.data.jsonBody
import app.musik.android.data.nonEmpty
import app.musik.android.data.tracksWord
import app.musik.android.ui.AlbumCard
import app.musik.android.ui.App
import app.musik.android.ui.ArtistAvatar
import app.musik.android.ui.ArtistCard
import app.musik.android.ui.Artwork
import app.musik.android.ui.ChipButton
import app.musik.android.ui.CoverColor
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.GlassChip
import app.musik.android.ui.LocalNavigator
import app.musik.android.ui.MusikColors
import app.musik.android.ui.PrimaryButton
import app.musik.android.ui.Shelf
import app.musik.android.ui.TrackRow
import app.musik.android.ui.withBrightness
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private val HERO_HEIGHT = 470.dp

/**
 * Artist page in the Yandex Music manner: the artist's photo across the whole width,
 * under the status bar, melting into the page colour taken from the photo. Scrolling
 * slides it away a little slower than the page; the name fades out as it goes.
 */
@Composable
fun ArtistScreen(name: String) {
    val nav = LocalNavigator.current
    val state = App.state
    val player = App.player
    val context = LocalContext.current
    val photo = state.photos.fullUrl(App.api, name)
    var tracks by remember(name) { mutableStateOf<List<Track>>(emptyList()) }
    var similar by remember(name) { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var loaded by remember(name) { mutableStateOf(false) }
    var picked by remember(name) { mutableStateOf<Color?>(null) }
    val tint by animateColorAsState(picked ?: MusikColors.surfaceHigh, tween(500), label = "tint")
    val listState = rememberLazyListState()
    val heroPx = with(LocalDensity.current) { HERO_HEIGHT.toPx() }
    val collapseAt = with(LocalDensity.current) { (HERO_HEIGHT - 140.dp).toPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt }
    }

    LaunchedEffect(name) {
        coroutineScope {
            val t = async { runCatching { App.api.library(artist = name) }.getOrNull() }
            val s = async { runCatching { App.api.similarArtists(name) }.getOrNull() }
            tracks = t.await() ?: emptyList()
            similar = s.await() ?: emptyList()
            loaded = true
        }
    }
    LaunchedEffect(photo) {
        if (photo != null) picked = CoverColor.forModel(context, "photo:$photo", photo)?.withBrightness(0.72f)
    }

    val albums = remember(tracks) { tracks.mapNotNull { it.album.nonEmpty }.distinct() }
    val onPlay = { player.play(jsonBody("artist" to name)) }
    val onRadio = tracks.firstOrNull()?.let { first -> { player.startRadio(seed = first.id) } }
    val favorite = name in state.favoriteArtists

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (photo != null) {
                item {
                    val shift = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else heroPx
                    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT).background(tint)) {
                        AsyncImage(
                            model = photo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = shift * 0.45f },
                        )
                        // dark at the very top for the status bar, clear over the face, then into the page colour
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.45f),
                                    0.2f to Color.Transparent,
                                    0.45f to Color.Transparent,
                                    0.72f to tint.copy(alpha = 0.55f),
                                    1f to tint,
                                ),
                            ),
                        )
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 6.dp)
                                .graphicsLayer { alpha = (1f - shift / (heroPx * 0.55f)).coerceIn(0f, 1f) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 44.sp,
                            )
                            Text(tracksWord(tracks.size), color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0f))))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        PrimaryButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Играть") }
                        onRadio?.let { r -> GlassChip(onClick = r) { Icon(Icons.Filled.Radio, null, Modifier.size(18.dp)); Text("Радио") } }
                        GlassChip(onClick = { state.toggleFavoriteArtist(name) }, active = favorite) {
                            Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Любимый артист", Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                item {
                    Column(Modifier.statusBarsPadding().padding(top = 56.dp)) {
                        DetailHeader(
                            image = { m -> ArtistAvatar(name, tracks.firstOrNull()?.id, m, 640, CircleShape) },
                            title = name, subtitle = tracksWord(tracks.size), favorite = favorite,
                            onPlay = onPlay, onRadio = onRadio, onFavorite = { state.toggleFavoriteArtist(name) },
                        )
                    }
                }
            }

            if (albums.size > 1) item {
                Shelf("Альбомы") {
                    items(albums) { album ->
                        AlbumCard(
                            AlbumItem(artist = name, album = album, tracks = tracks.count { it.album == album },
                                coverTrackId = tracks.firstOrNull { it.album == album }?.id),
                        ) { a, al -> nav.openAlbum(a, al) }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            if (!loaded) item { Loading() }
            else if (tracks.isEmpty()) item { EmptyHint("Треков не найдено") }
            itemsIndexed(tracks) { _, t ->
                TrackRow(t, Modifier.padding(horizontal = 16.dp)) { player.play(jsonBody("artist" to name, "start_track_id" to t.id)) }
            }
            if (similar.isNotEmpty()) item {
                Column(Modifier.padding(top = 20.dp)) {
                    Shelf("Похожие артисты") { items(similar) { ArtistCard(it) { a -> nav.openArtist(a) } } }
                }
            }
        }
        DetailTopBar(title = name, showTitle = photo == null || collapsed, solid = photo == null || collapsed)
    }
}

@Composable
fun AlbumScreen(artist: String?, album: String) {
    val nav = LocalNavigator.current
    val state = App.state
    val player = App.player
    var tracks by remember(artist, album) { mutableStateOf<List<Track>>(emptyList()) }
    var similar by remember(artist, album) { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var loaded by remember(artist, album) { mutableStateOf(false) }

    LaunchedEffect(artist, album) {
        coroutineScope {
            val t = async { runCatching { App.api.library(artist = artist, album = album) }.getOrNull() }
            val s = async { runCatching { App.api.similarAlbums(artist, album) }.getOrNull() }
            tracks = t.await() ?: emptyList()
            similar = s.await() ?: emptyList()
            loaded = true
        }
    }
    val artistName = artist.nonEmpty ?: tracks.firstOrNull()?.artist.nonEmpty
    val favorite = albumKey(artist, album) in state.favoriteAlbums
    val onPlay = { player.play(jsonBody("artist" to (artist ?: ""), "album" to album)) }
    val onRadio = tracks.firstOrNull()?.let { first -> { player.startRadio(seed = first.id) } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 56.dp)) {
                    DetailHeader(
                        image = { m -> Artwork(tracks.firstOrNull()?.id, m, 640, album, RoundedCornerShape(18.dp)) },
                        title = album,
                        subtitle = listOfNotNull(artistName, tracksWord(tracks.size)).joinToString(" · "),
                        favorite = favorite, onPlay = onPlay, onRadio = onRadio,
                        onFavorite = { state.toggleFavoriteAlbum(artist, album) },
                    )
                }
            }
            artistName?.let { a ->
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        ChipButton(onClick = { nav.openArtist(a) }) {
                            Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)); Text(a)
                        }
                    }
                }
            }
            if (!loaded) item { Loading() }
            else if (tracks.isEmpty()) item { EmptyHint("Треков не найдено") }
            itemsIndexed(tracks) { i, t ->
                TrackRow(t, Modifier.padding(horizontal = 16.dp), index = i, showArtwork = false) {
                    player.play(jsonBody("artist" to (artist ?: ""), "album" to album, "start_track_id" to t.id))
                }
            }
            if (similar.isNotEmpty()) item {
                Column(Modifier.padding(top = 20.dp)) {
                    Shelf("Похожие альбомы") { items(similar) { AlbumCard(it) { a, al -> nav.openAlbum(a, al) } } }
                }
            }
        }
        DetailTopBar(title = album, showTitle = true, solid = true)
    }
}

@Composable
private fun DetailHeader(
    image: @Composable (Modifier) -> Unit,
    title: String,
    subtitle: String,
    favorite: Boolean,
    onPlay: () -> Unit,
    onRadio: (() -> Unit)?,
    onFavorite: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(220.dp).shadow(20.dp, RoundedCornerShape(18.dp))) { image(Modifier.size(220.dp)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(subtitle, color = MusikColors.muted, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Играть") }
            onRadio?.let { r -> ChipButton(onClick = r) { Icon(Icons.Filled.Radio, null, Modifier.size(18.dp)); Text("Радио") } }
            ChipButton(onClick = onFavorite, active = favorite) {
                Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "В любимые", Modifier.size(18.dp))
            }
        }
    }
}

/** Back button and title over a detail page; transparent over an artist photo until it scrolls away. */
@Composable
private fun DetailTopBar(title: String, showTitle: Boolean, solid: Boolean) {
    val nav = LocalNavigator.current
    val bg by animateColorAsState(if (solid) MusikColors.background.copy(alpha = 0.96f) else Color.Transparent, tween(200), label = "bar")
    Row(
        Modifier.fillMaxWidth().background(bg).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { nav.back() },
            modifier = Modifier.background(if (solid) Color.Transparent else Color.Black.copy(alpha = 0.3f), CircleShape),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
        if (showTitle) {
            Text(
                title, Modifier.padding(horizontal = 8.dp).weight(1f), fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MusikColors.accent)
    }
}
