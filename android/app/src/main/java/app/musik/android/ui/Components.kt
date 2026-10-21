package app.musik.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musik.android.MusikApp
import app.musik.android.data.AlbumItem
import app.musik.android.data.ArtistItem
import app.musik.android.data.Links
import app.musik.android.data.Mix
import app.musik.android.ui.screens.shareText
import app.musik.android.data.Track
import app.musik.android.data.formatTime
import app.musik.android.data.jsonBody
import app.musik.android.data.nonEmpty
import app.musik.android.playback.PlayerController
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

val App: MusikApp get() = MusikApp.instance

/**
 * A track cover from the server (`/api/artwork/{id}?w=`), or the saved copy on the
 * phone; `photo` replaces it (artist photos). Placeholder: a warm gradient with a letter.
 */
@Composable
fun Artwork(
    trackId: Int?,
    modifier: Modifier = Modifier,
    size: Int = 256,
    fallback: String = "♪",
    shape: Shape = RoundedCornerShape(12.dp),
    photo: String? = null,
) {
    val offline = App.offline
    @Suppress("UNUSED_VARIABLE") val rev = offline.artworkRevision
    val model: Any? = photo
        ?: trackId?.let { id -> offline.artworkFile(id) ?: App.api.artworkUrl(id, size) }
    Box(modifier.clip(shape)) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(model).size(size).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Placeholder(trackId ?: fallback.hashCode(), fallback) },
            error = { Placeholder(trackId ?: fallback.hashCode(), fallback) },
        )
    }
}

@Composable
private fun Placeholder(seed: Int, text: String) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(MusikColors.placeholder(seed))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.trim().take(1).uppercase().ifEmpty { "♪" },
            color = Color.White.copy(alpha = 0.55f),
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
        )
    }
}

/** An artist's picture: the photo when the server has one, else a track cover. */
@Composable
fun ArtistAvatar(artist: String, coverTrackId: Int?, modifier: Modifier = Modifier, size: Int = 256, shape: Shape = CircleShape) {
    val photo = App.state.photos.url(App.api, artist, size)
    Artwork(coverTrackId, modifier, size, artist, shape, photo)
}

/** A titled horizontal shelf of cards. */
@Composable
fun Shelf(title: String, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            subtitle?.let { Text(it, color = MusikColors.muted, style = MaterialTheme.typography.bodySmall) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    note: String? = null,
    image: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        image(Modifier.size(width))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        subtitle?.nonEmpty?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MusikColors.muted, style = MaterialTheme.typography.bodySmall) }
        note?.nonEmpty?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MusikColors.teal, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun MixCard(mix: Mix, onClick: () -> Unit) {
    val today = mix.today == true || mix.highlight == true
    val shape = RoundedCornerShape(14.dp)
    // As on iOS: plain cover (accent frame and «сегодня» pill for today's mix), title and count below.
    Card(mix.title, mix.metaLabel, onClick = onClick) { m ->
        Box(m) {
            Artwork(mix.coverTrackId, Modifier.fillMaxSize(), 256, mix.title, shape)
            if (today) {
                Box(Modifier.fillMaxSize().border(2.dp, MusikColors.accent, shape))
                Text(
                    "сегодня",
                    Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(MusikColors.accent, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    color = Color.Black.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
fun TrackCard(track: Track, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(track.displayTitle, track.displayArtist, onClick = onClick, onLongClick = { menu = true }) { m ->
            Artwork(track.id, m, 256, track.displayTitle)
        }
        TrackMenu(track, menu) { menu = false }
    }
}

@Composable
fun ArtistCard(artist: ArtistItem, onOpen: (String) -> Unit) {
    Card(artist.artist, artist.tracks?.let { app.musik.android.data.tracksWord(it) }, width = 130.dp,
        onClick = { onOpen(artist.artist) }, note = artist.explanation) { m ->
        ArtistAvatar(artist.artist, artist.coverTrackId, m)
    }
}

@Composable
fun AlbumCard(album: AlbumItem, onOpen: (String?, String) -> Unit) {
    Card(album.album, album.artist, onClick = { onOpen(album.artist, album.album) }, note = album.explanation) { m ->
        Artwork(album.coverTrackId, m, 256, album.album, RoundedCornerShape(10.dp))
    }
}

/** A row of a track list: cover or number, title, artist, save state, length. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArtwork: Boolean = true,
    onDark: Boolean = false,
    onClick: () -> Unit,
) {
    val player = App.player
    val offline = App.offline
    val isCurrent = player.current?.id == track.id
    var menu by remember { mutableStateOf(false) }
    val sub = if (onDark) Color.White.copy(alpha = 0.7f) else MusikColors.muted
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showArtwork) {
                Artwork(track.id, Modifier.size(46.dp), 96, track.displayTitle, RoundedCornerShape(7.dp))
            } else if (index != null) {
                Text("${index + 1}", Modifier.width(26.dp), color = sub, style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCurrent) Icon(Icons.Filled.GraphicEq, null, Modifier.size(14.dp), tint = if (onDark) Color.White else MusikColors.accent)
                    Text(
                        track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent && !onDark) MusikColors.accentBright else Color.White,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Text(track.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = sub, style = MaterialTheme.typography.bodySmall)
            }
            when {
                track.id in offline.downloading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = sub)
                offline.isSaved(track.id) -> Icon(Icons.Filled.CheckCircle, "Сохранён", Modifier.size(15.dp), tint = if (onDark) Color.White else MusikColors.teal)
            }
            if (track.id in App.state.favoriteTracks) {
                Icon(Icons.Filled.Favorite, null, Modifier.size(14.dp), tint = if (onDark) Color.White else MusikColors.accent)
            }
            Text(formatTime(track.duration), color = sub, style = MaterialTheme.typography.labelMedium)
        }
        TrackMenu(track, menu) { menu = false }
    }
}

/** Long-press menu of a track, shared by every list. */
@Composable
fun TrackMenu(track: Track, expanded: Boolean, onDismiss: () -> Unit) {
    val state = App.state
    val player = App.player
    val offline = App.offline
    val nav = LocalNavigator.current
    val context = LocalContext.current
    DropdownMenu(expanded, onDismiss, containerColor = MusikColors.surfaceHigh) {
        MenuItem("Играть трек", onDismiss) { player.play(jsonBody("track_id" to track.id, "name" to track.displayTitle)) }
        MenuItem("Радио от трека", onDismiss) { player.startRadio(seed = track.id) }
        MenuItem("Потом", onDismiss) { state.addLater(track.id) }
        MenuItem(if (track.id in state.favoriteTracks) "Убрать из любимых" else "В любимые", onDismiss) {
            state.perform { state.toggleFavoriteTrack(track) }
        }
        if (offline.isSaved(track.id)) MenuItem("Удалить с телефона", onDismiss) { offline.remove(track.id) }
        else MenuItem("Сохранить на телефон", onDismiss) { offline.save(track) }
        MenuItem("Поделиться", onDismiss) { shareText(context, Links.track(App.settings.baseUrl, track)) }
        track.artist.nonEmpty?.let { a -> MenuItem("Весь артист", onDismiss) { nav.openArtist(a) } }
        track.album.nonEmpty?.let { al -> MenuItem("Весь альбом", onDismiss) { nav.openAlbum(track.artist, al) } }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        MenuItem("Скрыть этот трек", onDismiss) { player.hide(PlayerController.HideTarget.Track, track) }
        track.artist.nonEmpty?.let { a ->
            MenuItem("Скрыть артиста «$a»", onDismiss) { player.hide(PlayerController.HideTarget.Artist, track) }
        }
    }
}

@Composable
fun MenuItem(text: String, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = { onDismiss(); action() })
}

/** Save to the phone / saved / downloading, for track rows and the player. */
@Composable
fun SaveIcon(track: Track, tint: Color = Color.White) {
    val offline = App.offline
    IconButton(onClick = { if (offline.isSaved(track.id)) offline.remove(track.id) else offline.save(track) }) {
        when {
            track.id in offline.downloading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
            offline.isSaved(track.id) -> Icon(Icons.Filled.CheckCircle, "Удалить с телефона", tint = tint)
            else -> Icon(Icons.Outlined.DownloadForOffline, "Сохранить на телефон", tint = tint)
        }
    }
}

@Composable
fun FavoriteMark(on: Boolean, tint: Color = MusikColors.accent) {
    Icon(if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(16.dp), tint = tint)
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.size(h))
