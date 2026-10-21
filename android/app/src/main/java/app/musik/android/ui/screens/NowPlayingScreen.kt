package app.musik.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musik.android.data.Links
import app.musik.android.data.Labels
import app.musik.android.data.QueueItem
import app.musik.android.data.Track
import app.musik.android.data.albumKey
import app.musik.android.data.formatTime
import app.musik.android.data.nonEmpty
import app.musik.android.playback.PlayerController
import app.musik.android.ui.App
import app.musik.android.ui.Artwork
import app.musik.android.ui.CoverColor
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.GlassChip
import app.musik.android.ui.LocalNavigator
import app.musik.android.ui.MenuItem
import app.musik.android.ui.MusikColors
import app.musik.android.ui.TrackRow
import app.musik.android.ui.withBrightness
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val player = App.player
    val track = player.current ?: return
    val context = LocalContext.current
    var cover by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(track.id) { cover = CoverColor.forTrack(context, track.id) }
    val top by animateColorAsState(cover ?: MusikColors.surfaceHigh, tween(600), label = "cover")
    val bottom by animateColorAsState((cover ?: MusikColors.background).withBrightness(0.55f), tween(600), label = "cover2")
    var pane by rememberSaveable { mutableStateOf(0) }
    var showEqualizer by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MusikColors.background)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            // swallow taps so they don't reach the screen underneath
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TopBar(track, onClose) { showEqualizer = true }
            SwipeCover(track)
            TitleBlock(track)
            SeekBar()
            Transport()
            Actions(track, lyricsOn = pane == 1, onLyrics = { pane = if (pane == 1) 0 else 1 })
            val paneScroll = remember { BringIntoViewRequester() }
            LaunchedEffect(pane) {
                if (pane == 1) {
                    delay(80)
                    paneScroll.bringIntoView()
                }
            }
            Box(Modifier.bringIntoViewRequester(paneScroll)) {
                if (pane == 0) UpNext() else LyricsPane(track.id)
            }
            // quiet, at the very bottom — as in Yandex Music
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                val eqOn = App.equalizer.enabled
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (eqOn) 0.28f else 0.12f))
                        .clickable { showEqualizer = true }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.Tune, "Эквалайзер", tint = if (eqOn) Color.White else Color.White.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showEqualizer) EqualizerSheet { showEqualizer = false }
}

@Composable
private fun TopBar(track: Track, onClose: () -> Unit, onEqualizer: () -> Unit) {
    val player = App.player
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        // the title sits exactly in the middle of the screen whatever the width of the buttons around it
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                player.listName ?: if (player.fixed) "Плейлист" else "Радио",
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White,
            )
            if (player.offlineMode) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(12.dp), tint = Color.White)
                    Text("офлайн · с телефона", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            } else {
                Text(Labels.maturity(player.maturity), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            }
        }
        IconButton(onClick = onClose, Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.KeyboardArrowDown, "Свернуть", Modifier.size(30.dp), tint = Color.White)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { shareText(context, Links.track(App.settings.baseUrl, track)) }) {
                Icon(Icons.Filled.Share, "Поделиться", tint = Color.White)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Не предлагать", tint = Color.White) }
                DropdownMenu(menu, { menu = false }, containerColor = MusikColors.surfaceHigh) {
                    MenuItem("Эквалайзер", { menu = false }) { onEqualizer() }
                    MenuItem("Скрыть этот трек", { menu = false }) { player.hide(PlayerController.HideTarget.Track) }
                    track.artist.nonEmpty?.let { a ->
                        MenuItem("Скрыть артиста «$a»", { menu = false }) { player.hide(PlayerController.HideTarget.Artist) }
                    }
                }
            }
        }
    }
}

/** The cover; a sideways swipe skips forward (left) or goes back (right). */
@Composable
private fun SwipeCover(track: Track) {
    val player = App.player
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val scale by animateFloatAsState(if (player.isPlaying) 1f else 0.94f, spring(dampingRatio = 0.8f), label = "scale")
    LaunchedEffect(track.id) { offset.snapTo(0f) }
    Box(
        Modifier
            .padding(horizontal = 28.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer {
                alpha = 1f - (abs(offset.value) / (threshold * 3)).coerceIn(0f, 0.6f)
                rotationZ = offset.value / threshold * 2f
            }
            .scale(scale)
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = offset.value
                        scope.launch {
                            when {
                                v < -threshold -> { player.skip(); offset.animateTo(-threshold * 4, tween(160)) }
                                v > threshold -> { player.back(); offset.animateTo(threshold * 4, tween(160)) }
                                else -> offset.animateTo(0f, spring())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + drag) }
                }
            }
            .shadow(28.dp, RoundedCornerShape(22.dp)),
    ) {
        Artwork(track.id, Modifier.fillMaxSize(), 640, track.displayTitle, RoundedCornerShape(22.dp))
    }
}

@Composable
private fun TitleBlock(track: Track) {
    val nav = LocalNavigator.current
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            track.displayTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        // artist and album open their pages
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val faded = Color.White.copy(alpha = 0.75f)
            track.artist.nonEmpty?.let { a ->
                Text(a, Modifier.clickable { nav.openArtist(a) }, color = faded, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (track.artist.nonEmpty != null && track.album.nonEmpty != null) Text("·", color = faded)
            track.album.nonEmpty?.let { al ->
                Text(al, Modifier.weight(1f, fill = false).clickable { nav.openAlbum(track.artist, al) }, color = faded, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Labels.source(track.source)?.let { src ->
            Text(
                src,
                Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A tap anywhere on the line jumps there; dragging scrubs, the seek happens on release. */
@Composable
private fun SeekBar() {
    val player = App.player
    val clock = player.clock
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    val total = maxOf(clock.duration, 1.0)
    val value = if (scrubbing) scrub.toDouble() else minOf(clock.position, total)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            fun at(x: Float) = (x / widthPx).coerceIn(0f, 1f) * total.toFloat()
            val fraction = (value / total).toFloat().coerceIn(0f, 1f)
            val barHeight = if (scrubbing) 8.dp else 5.dp
            val knob = if (scrubbing) 22.dp else 14.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(total) {
                        detectTapGestures { pos -> player.seek(at(pos.x).toDouble()) }
                    }
                    .pointerInput(total) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos -> scrubbing = true; scrub = at(pos.x) },
                            onDragEnd = { player.seek(scrub.toDouble()); scrubbing = false },
                            onDragCancel = { scrubbing = false },
                        ) { change, _ ->
                            change.consume()
                            scrub = at(change.position.x)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(barHeight).background(Color.White.copy(alpha = 0.25f), CircleShape))
                Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(Color.White, CircleShape))
                val knobOffset = with(LocalDensity.current) { (fraction * widthPx).toDp() - knob / 2 }
                Box(Modifier.offset(x = knobOffset).size(knob).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val style = MaterialTheme.typography.labelMedium
            Text(formatTime(value), color = Color.White.copy(alpha = 0.75f), style = style)
            Spacer(Modifier.weight(1f))
            Text(formatTime(clock.duration), color = Color.White.copy(alpha = 0.75f), style = style)
        }
    }
}

@Composable
private fun Transport() {
    val player = App.player
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { player.back() }, Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, "Назад", Modifier.size(40.dp), tint = Color.White)
        }
        Box(
            Modifier.size(78.dp).shadow(10.dp, CircleShape).background(Color.White, CircleShape).clip(CircleShape)
                .clickable { player.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (player.isBuffering || player.busy) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
            } else {
                Icon(
                    if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (player.isPlaying) "Пауза" else "Играть",
                    Modifier.size(40.dp), tint = Color.Black.copy(alpha = 0.85f),
                )
            }
        }
        IconButton(onClick = { player.skip() }, Modifier.size(56.dp), enabled = !player.busy) {
            Icon(Icons.Filled.SkipNext, "Дальше", Modifier.size(40.dp), tint = Color.White)
        }
    }
}

private val LikeRed = Color(0xFFF0453A)

private fun RowScope.cell(): Modifier = Modifier.weight(1f)

@Composable
private fun Actions(track: Track, lyricsOn: Boolean, onLyrics: () -> Unit) {
    val player = App.player
    val state = App.state
    val offline = App.offline
    // the heart comes from the shared list of favourites, so it follows a like made anywhere
    val favorite = track.id in state.favoriteTracks
    val heartColor by animateColorAsState(if (favorite) LikeRed else Color.White, tween(200), label = "heart")
    val pulse = remember { Animatable(1f) }
    var seenTick by remember { mutableIntStateOf(player.likeTick) }
    LaunchedEffect(player.likeTick) {
        if (player.likeTick == seenTick) return@LaunchedEffect
        seenTick = player.likeTick
        // heartbeat: 1 → 1.45 → 1 → 1.45 → 1 in about half a second
        repeat(2) {
            pulse.animateTo(1.45f, spring(dampingRatio = 0.45f, stiffness = 900f))
            pulse.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 700f))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip(onClick = { player.dislike() }, cell(), active = player.disliked) {
                Icon(if (player.disliked) Icons.Filled.HeartBroken else Icons.Outlined.HeartBroken, if (player.disliked) "Снять дизлайк" else "Дизлайк", Modifier.size(22.dp))
            }
            GlassChip(
                onClick = { if (offline.isSaved(track.id)) offline.remove(track.id) else offline.save(track) },
                cell(), active = offline.isSaved(track.id),
            ) {
                when {
                    track.id in offline.downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    offline.isSaved(track.id) -> Icon(Icons.Filled.CheckCircle, "Удалить с телефона", Modifier.size(22.dp))
                    else -> Icon(Icons.Outlined.DownloadForOffline, "Сохранить на телефон", Modifier.size(22.dp))
                }
            }
            GlassChip(onClick = { player.like() }, cell(), active = favorite) {
                Icon(
                    if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Любимая",
                    Modifier.size(22.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
                    tint = heartColor,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip(onClick = { player.playSimilarNow() }, cell()) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Text("Похожее", maxLines = 1)
            }
            GlassChip(onClick = onLyrics, cell(), active = lyricsOn) {
                Icon(Icons.AutoMirrored.Filled.Notes, null, Modifier.size(18.dp)); Text("Текст", maxLines = 1)
            }
            GlassChip(onClick = { player.startRadio(seed = track.id) }, cell()) {
                Icon(Icons.Filled.Radio, null, Modifier.size(18.dp)); Text("Радио", maxLines = 1)
            }
        }
    }
}

@Composable
private fun UpNext() {
    val player = App.player
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val header = MaterialTheme.typography.labelLarge
        if (player.fixed) {
            Text("В этом списке · ${player.playlist.size}", color = Color.White.copy(alpha = 0.75f), style = header)
            player.playlist.forEachIndexed { i, t ->
                TrackRow(t, index = i, onDark = true) { player.jump(t.id, t.position ?: i) }
            }
        } else {
            Text("Дальше в радио", color = Color.White.copy(alpha = 0.75f), style = header)
            player.queue.forEachIndexed { i, item -> QueueRow(item) { player.jump(item.trackId, i) } }
            if (player.queue.isEmpty()) EmptyHint("Очередь пополнится после следующего трека")
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(item.trackId, Modifier.size(46.dp), 96, item.title ?: "♪", RoundedCornerShape(7.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title.nonEmpty ?: "#${item.trackId}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val sub = Color.White.copy(alpha = 0.7f)
                Text(item.artist ?: "", color = sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Labels.why(item)?.let { Text(it, color = sub, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
        }
        Text(formatTime(item.duration), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
    }
}
