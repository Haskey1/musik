package app.musik.android.ui.screens

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import app.musik.android.ui.ArtistAvatar
import app.musik.android.ui.CoverBackdrop
import app.musik.android.data.jsonBody
import app.musik.android.data.Haptics
import app.musik.android.data.Passport
import androidx.compose.material.icons.filled.Cached
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
    var pane by rememberSaveable { mutableStateOf(0) }
    var showEqualizer by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val paneScroll = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun openPane(value: Int) {
        pane = value
        scope.launch { delay(80); paneScroll.bringIntoView() }
    }
    Box(Modifier.fillMaxSize().background(MusikColors.background).pointerInput(Unit) { detectTapGestures { } }) {
        CoverBackdrop(track.id)
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !swiping), horizontalAlignment = Alignment.CenterHorizontally) {
                AdaptivePlayerViewport(
                    viewportHeight = viewport,
                    header = { TopBar(track, onClose, { showEqualizer = true }, { openPane(1) }, { openPane(0) }) },
                    cover = { SwipeCover(track, it) { active -> swiping = active } },
                    details = { TitleBlock(track) },
                    seek = { SeekBar() },
                    transport = { Transport(track) },
                    utilities = { Utilities(track) },
                    similar = { SimilarButton(track) },
                )
                Spacer(Modifier.height(20.dp))
                Box(Modifier.bringIntoViewRequester(paneScroll).padding(bottom = 32.dp)) {
                    if (pane == 0) UpNext() else LyricsPane(track.id)
                }
            }
        }
    }
    if (showEqualizer) EqualizerSheet { showEqualizer = false }
}

/** Measure real control heights before fitting the square cover, then spread extra space. */
@Composable
private fun AdaptivePlayerViewport(
    viewportHeight: Dp,
    header: @Composable () -> Unit,
    cover: @Composable (Modifier) -> Unit,
    details: @Composable () -> Unit,
    seek: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    utilities: @Composable () -> Unit,
    similar: @Composable () -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val viewport = viewportHeight.roundToPx()
        val minimumGap = (if (viewportHeight < 700.dp) 8.dp else 12.dp).roundToPx()
        val top = 8.dp.roundToPx()
        val bottom = 16.dp.roundToPx()
        val controls = listOf(header, details, seek, transport, utilities, similar).mapIndexed { index, content ->
            subcompose(index) { Box(Modifier.fillMaxWidth()) { content() } }.single()
                .measure(Constraints(minWidth = width, maxWidth = width))
        }
        val controlHeight = controls.sumOf { it.height }
        val idealCover = minOf((width * 0.82f).roundToInt(), width - 48.dp.roundToPx(), 380.dp.roundToPx()).coerceAtLeast(1)
        val available = viewport - top - bottom - controlHeight - minimumGap * 6
        val side = minOf(idealCover, maxOf(140.dp.roundToPx(), available))
        val artwork = subcompose("cover") { cover(Modifier.fillMaxSize()) }.single()
            .measure(Constraints.fixed(side, side))
        val children = listOf(controls[0], artwork) + controls.drop(1)
        val contentHeight = top + bottom + controlHeight + side + minimumGap * 6
        val height = maxOf(viewport, contentHeight)
        val extra = height - contentHeight
        val weights = listOf(2f, 2f, 0.5f, 0.5f, 2f, 3f)
        layout(width, height) {
            var y = top
            var distributed = 0
            children.forEachIndexed { index, child ->
                child.placeRelative((width - child.width) / 2, y)
                y += child.height
                if (index < weights.size) {
                    val target = (extra * weights.take(index + 1).sum() / 10f).roundToInt()
                    y += minimumGap + target - distributed
                    distributed = target
                }
            }
        }
    }
}

@Composable
private fun TopBar(track: Track, onClose: () -> Unit, onEqualizer: () -> Unit, onLyrics: () -> Unit, onQueue: () -> Unit) {
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
                (player.listName ?: if (player.fixed) "Плейлист" else "Моё радио").uppercase(),
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
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, "Свернуть", Modifier.size(30.dp), tint = Color.White)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { shareText(context, Links.track(App.settings.baseUrl, track)) }) {
                Icon(Icons.Filled.Share, "Поделиться", tint = Color.White)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню трека", tint = Color.White) }
                DropdownMenu(menu, { menu = false }, containerColor = MusikColors.surfaceHigh) {
                    MenuItem("Текст", { menu = false }) { onLyrics() }
                    MenuItem("Дальше", { menu = false }) { onQueue() }
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
private fun SwipeCover(track: Track, modifier: Modifier, onSwiping: (Boolean) -> Unit) {
    val player = App.player
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val scale by animateFloatAsState(if (player.isPlaying || player.isBuffering || player.busy) 1f else 0.94f, spring(dampingRatio = 0.8f), label = "scale")
    LaunchedEffect(track.id) { offset.snapTo(0f) }
    Box(
        modifier
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer {
                alpha = 1f - (abs(offset.value) / (threshold * 3)).coerceIn(0f, 0.6f)
                rotationZ = offset.value / threshold * 2f
            }
            .scale(scale)
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragStart = { onSwiping(true) },
                    onDragEnd = {
                        onSwiping(false)
                        val v = offset.value
                        scope.launch {
                            when {
                                v < -threshold -> { player.skip(); offset.animateTo(-threshold * 4, tween(160)) }
                                v > threshold -> { player.back(); offset.animateTo(threshold * 4, tween(160)) }
                                else -> offset.animateTo(0f, spring())
                            }
                        }
                    },
                    onDragCancel = { onSwiping(false); scope.launch { offset.animateTo(0f, spring()) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + drag) }
                }
            }
            .shadow(28.dp, RoundedCornerShape(28.dp)),
    ) {
        Artwork(track.id, Modifier.fillMaxSize(), 640, track.displayTitle, RoundedCornerShape(28.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleBlock(track: Track) {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ArtistAvatar(track.displayArtist, null, Modifier.size(48.dp), size = 96)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(track.displayTitle, Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    track.artist.nonEmpty?.let { artist ->
                        Text(artist, Modifier.weight(1f, fill = false).clickable { nav.openArtist(artist) },
                            color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (track.artist.nonEmpty != null && track.album.nonEmpty != null) Text("·", color = MusikColors.muted)
                    track.album.nonEmpty?.let { album ->
                        Text(album, Modifier.weight(1f, fill = false).clickable { nav.openAlbum(track.artist, album) },
                            color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TrackInfoLine(track.id) }
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
                Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(MusikColors.accentBright, CircleShape))
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
private fun Transport(track: Track) {
    val player = App.player
    val context = LocalContext.current
    val favorite = track.id in App.state.favoriteTracks
    val pulse = remember { Animatable(1f) }
    var seenTick by remember { mutableIntStateOf(player.likeTick) }
    LaunchedEffect(player.likeTick) {
        if (seenTick == player.likeTick) return@LaunchedEffect
        seenTick = player.likeTick
        repeat(2) {
            pulse.animateTo(1.45f, spring(dampingRatio = 0.45f, stiffness = 900f))
            pulse.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 700f))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (player.disliked) player.dislike() else player.dislikeAndSkip() }, enabled = !player.busy) {
            Icon(if (player.disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Дизлайк и пропустить",
                tint = if (player.disliked) MusikColors.accentBright else Color.White)
        }
        IconButton(onClick = { Haptics.tick(context, App.settings); player.back() }) {
            Icon(Icons.Filled.SkipPrevious, "Предыдущий трек", Modifier.size(30.dp), tint = Color.White)
        }
        Box(Modifier.size(60.dp).shadow(14.dp, CircleShape).background(MusikColors.accent, CircleShape).clip(CircleShape)
            .clickable { Haptics.press(context, App.settings); player.togglePlay() }, contentAlignment = Alignment.Center) {
            if (player.isBuffering || player.busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (player.isPlaying) "Пауза" else "Воспроизвести", Modifier.size(32.dp), tint = Color.White)
        }
        IconButton(onClick = { Haptics.tick(context, App.settings); player.skip() }, enabled = !player.busy) {
            Icon(Icons.Filled.SkipNext, "Следующий трек", Modifier.size(30.dp), tint = Color.White)
        }
        IconButton(onClick = { player.like() }) {
            Icon(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (favorite) "Убрать из любимых" else "В любимые",
                Modifier.size(26.dp).scale(pulse.value), tint = if (favorite) MusikColors.love else Color.White)
        }
    }
}

@Composable
private fun UtilityButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(Modifier.size(68.dp, 44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Utilities(track: Track) {
    val player = App.player
    val offline = App.offline
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        UtilityButton(onClick = { player.seek(0.0) }) { Icon(Icons.Filled.Repeat, "С начала", tint = Color.White.copy(alpha = 0.8f)) }
        UtilityButton(onClick = {
            when {
                player.offlineMode -> player.playOffline(player.playlist, shuffle = true)
                player.fixed -> player.play(jsonBody("track_ids" to player.playlist.shuffled().map { it.id }, "name" to (player.listName ?: "Плейлист")))
                else -> player.startRadio()
            }
        }, enabled = !player.busy) {
            Icon(if (player.fixed || player.offlineMode) Icons.Filled.Shuffle else Icons.Filled.Refresh,
                if (player.fixed || player.offlineMode) "Перемешать" else "Обновить радио", tint = Color.White.copy(alpha = 0.8f))
        }
        UtilityButton(onClick = { offline.save(track) }, enabled = track.id !in offline.downloading && !offline.isSaved(track.id)) {
            if (track.id in offline.downloading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(if (offline.isSaved(track.id)) Icons.Filled.Check else Icons.Filled.Download,
                if (offline.isSaved(track.id)) "Сохранено на телефон" else "Скачать трек", tint = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SimilarButton(track: Track) {
    val player = App.player
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(shape)
        .background(Brush.linearGradient(listOf(MusikColors.accent.copy(alpha = 0.24f), Color.Black.copy(alpha = 0.24f), MusikColors.accentBright.copy(alpha = 0.12f))))
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
        .clickable(enabled = !player.busy && !player.offlineMode) { player.playSimilarNow() }
        .heightIn(min = 56.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Похожее на трек", color = MusikColors.accentBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = Color.White)
            Text(track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
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

private val infoCache = HashMap<Int, String>()

/** Genre and file quality from the track's passport, in quiet text; empty while loading or offline. */
@Composable
private fun TrackInfoLine(trackId: Int) {
    var text by remember(trackId) { mutableStateOf(infoCache[trackId] ?: "") }
    LaunchedEffect(trackId) {
        if (infoCache.containsKey(trackId)) return@LaunchedEffect
        val passport = runCatching { App.api.trackDetail(trackId).passport }.getOrNull() ?: return@LaunchedEffect
        val line = describePassport(passport)
        infoCache[trackId] = line
        text = line
    }
    // a blank line keeps the layout from jumping while the text loads
    Text(
        text.ifEmpty { " " }, color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodySmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
    )
}

private fun describePassport(p: Passport): String {
    val parts = ArrayList<String>()
    val genres = (p.genres ?: emptyList()).filter { it.isNotBlank() }.take(2)
    if (genres.isNotEmpty()) parts.add(genres.joinToString(", "))
    val format = p.format?.takeIf { it.isNotBlank() }
    if (format != null) {
        val lossless = format.uppercase() in setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "WV")
        val rate = p.sampleRate
        val bitrate = p.bitrate
        parts.add(
            when {
                lossless && rate != null && rate > 0 -> "$format · " + khz(rate)
                !lossless && bitrate != null && bitrate > 0 -> "$format · ${(bitrate / 1000.0).roundToInt()} кбит/с"
                else -> format
            },
        )
    }
    return parts.joinToString(" · ")
}

private fun khz(hz: Int): String {
    val k = hz / 1000.0
    return if (k % 1.0 == 0.0) "${k.toInt()} кГц" else String.format(java.util.Locale.US, "%.1f", k).replace('.', ',') + " кГц"
}


