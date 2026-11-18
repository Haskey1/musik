package app.musik.android.ui.screens

import android.animation.ValueAnimator
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import kotlin.math.sin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.musik.android.data.ArtistCount
import app.musik.android.data.jsonBody
import app.musik.android.ui.App
import app.musik.android.ui.Artwork
import app.musik.android.ui.MusikColors
import app.musik.android.ui.displayStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import kotlin.math.abs

@Serializable
internal data class RecapAvailability(val available: Boolean, val year: Int)

@Serializable
private data class RecapTrack(
    @SerialName("track_id") val id: Int,
    val title: String,
    val artist: String,
    val count: Int,
)

@Serializable
private data class RecapStats(
    @SerialName("listened_seconds") val seconds: Double,
    val plays: Int,
    val tracks: List<RecapTrack>,
    val artists: List<ArtistCount>,
)

@Serializable
private data class RecapPayload(
    val year: Int,
    val stats: RecapStats,
    @SerialName("artist_track") val artistTrack: RecapTrack? = null,
)

@Serializable
private data class RecapClip(val audio: String)

private enum class RecapPage(val tint: Color) {
    Intro(Color(0xFFF1772D)),
    Time(Color(0xFFBA4930)),
    Track(Color(0xFFF0453A)),
    Tracks(Color(0xFF6744B6)),
    Artist(Color(0xFF9C3271)),
    Artists(Color(0xFF973866)),
    Finish(Color(0xFFF1772D)),
}

private val recapCream = Color(0xFFF6E9DC)

@Composable
internal fun YearRecapBanner(year: Int, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF49251C), Color(0xFF9C441E), MusikColors.accent)))
            .clickable(onClick = onOpen)) {
        RecapSilk(Modifier.matchParentSize(), MusikColors.accentBright)
        Row(Modifier.fillMaxWidth().padding(19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Твой $year", style = displayStyle(25), color = recapCream)
            Text("Смотреть итоги", fontWeight = FontWeight.SemiBold, color = recapCream)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = recapCream)
        }
    }
}

@Composable
internal fun YearRecapScreen(onClose: () -> Unit) {
    val player = App.player
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val sound = remember { RecapSound() }
    val clips = remember { mutableMapOf<Int, ByteArray>() }
    val restoreMusic = remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var recap by remember { mutableStateOf<RecapPayload?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val pages = remember(recap) {
        val data = recap ?: return@remember emptyList()
        if (data.stats.tracks.isEmpty()) listOf(RecapPage.Intro, RecapPage.Time, RecapPage.Finish)
        else RecapPage.entries.filter { when (it) {
            RecapPage.Artist -> data.artistTrack != null
            RecapPage.Artists -> data.stats.artists.size > 1
            RecapPage.Tracks -> data.stats.tracks.size > 1
            else -> true
        } }
    }
    val page = pages.getOrNull(index) ?: RecapPage.Intro
    val backingTrack = when (page) {
        RecapPage.Artist, RecapPage.Artists -> recap?.artistTrack ?: recap?.stats?.tracks?.firstOrNull()
        RecapPage.Tracks -> recap?.stats?.tracks?.getOrNull(1)
        RecapPage.Time -> recap?.stats?.tracks?.lastOrNull()
        else -> recap?.stats?.tracks?.firstOrNull()
    }
    val running = page != RecapPage.Intro && page != RecapPage.Finish
    val enabled = active && running && !paused && !holding
    val playSound by rememberUpdatedState(enabled)
    val tint by animateColorAsState(page.tint, tween(430), label = "Фон итогов")

    fun select(target: Int) {
        if (target in pages.indices && target != index) {
            progress = if (pages[target] == RecapPage.Finish) 1f else 0f
            index = target
        }
    }

    DisposableEffect(lifecycle) {
        restoreMusic.value = player.isPlaying
        if (restoreMusic.value) player.pause()
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sound.close()
            if (restoreMusic.value) player.resume()
        }
    }
    LaunchedEffect(retry) {
        failed = false
        try {
            recap = App.api.get("/api/year-recap")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(page, backingTrack?.id) {
        sound.fadeOut()
        ready = false
        val track = backingTrack ?: return@LaunchedEffect
        if (!running) return@LaunchedEffect
        try {
            val bytes = clips[track.id] ?: App.api.get<RecapClip>("/api/year-recap/clips/${track.id}").let {
                Base64.decode(it.audio, Base64.DEFAULT).also { data -> clips[track.id] = data }
            }
            sound.prepare(bytes, { progress }) {
                sound.mute(muted)
                ready = true
                sound.setPlaying(playSound)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ready = true
        }
    }
    LaunchedEffect(enabled, muted) { sound.mute(muted); sound.setPlaying(enabled) }
    LaunchedEffect(index, running, paused, active, muted, ready, holding) {
        if (!running || paused || holding || !active) return@LaunchedEffect
        if (!muted && backingTrack != null && !ready) return@LaunchedEffect
        while (progress < 1f) {
            delay(100)
            progress = sound.fraction() ?: (progress + 1f / 160).coerceAtMost(1f)
        }
        select(index + 1)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalContentColor provides recapCream) {
            Box(Modifier.fillMaxSize().background(MusikColors.background)
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.58f), Color.Transparent, tint.copy(alpha = 0.2f))))) {
                RecapSilk(Modifier.matchParentSize(), tint)
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    if (pages.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            pages.indices.forEach { i ->
                                val amount = when {
                                    i < index -> 1f
                                    i == index -> progress
                                    else -> 0f
                                }
                                Box(
                                    Modifier.weight(1f).height(22.dp).semantics { contentDescription = "Слайд ${i + 1}" }
                                        .clickable { select(i) },
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.24f), CircleShape))
                                    Box(Modifier.fillMaxWidth(amount).height(4.dp).background(MusikColors.accentBright, CircleShape))
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(recap?.let { "musik · ${it.year}" } ?: "musik · Итоги года",
                            Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { muted = !muted }) {
                            Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                if (muted) "Включить звук" else "Выключить звук")
                        }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть итоги") }
                    }

                    val data = recap
                    if (data == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Итоги сейчас недоступны")
                                TextButton(onClick = { retry++ }) { Text("Повторить") }
                            } else CircularProgressIndicator(color = MusikColors.accent)
                        }
                    } else {
                        BoxWithConstraints(
                            Modifier.weight(1f).fillMaxWidth().pointerInput(index) {
                                detectHorizontalDragGestures(
                                    onDragStart = { drag = 0f },
                                    onDragCancel = { drag = 0f },
                                    onDragEnd = {
                                        if (abs(drag) > 50.dp.toPx()) select(index + if (drag < 0) 1 else -1)
                                        drag = 0f
                                    },
                                ) { change, dx -> change.consume(); drag += dx }
                            }
                            .pointerInput(index) {
                                detectTapGestures(
                                    onPress = {
                                        holding = true
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            holding = false
                                        }
                                    },
                                    onTap = {
                                        if (running && it.x < size.width * 0.24f) select(index - 1)
                                        if (running && it.x > size.width * 0.76f) select(index + 1)
                                    },
                                )
                            },
                        ) {
                            val artworkSize = minOf(maxHeight * 0.46f, maxWidth, 285.dp)
                            AnimatedContent(
                                targetState = index,
                                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = drag * 0.8f },
                                transitionSpec = {
                                    val sign = if (targetState > initialState) 1 else -1
                                    (scaleIn(tween(550), initialScale = 0.84f) + fadeIn(tween(500)) + slideInHorizontally(tween(420)) { sign * it / 2 }) togetherWith
                                        (fadeOut(tween(340)) + slideOutHorizontally(tween(420)) { -sign * it / 2 })
                                },
                                label = "Слайды итогов",
                            ) { visibleIndex ->
                                RecapContent(data, pages[visibleIndex], artworkSize,
                                    onStart = { select(1) },
                                    onReplay = { select(0) },
                                    onPlaylist = {
                                        restoreMusic.value = false
                                        sound.close()
                                        player.play(jsonBody("track_ids" to data.stats.tracks.map { it.id }, "name" to "Твой ${data.year}"))
                                        onClose()
                                    },
                                    onShare = {
                                        val track = data.stats.tracks.first()
                                        val text = "Мой ${data.year} в musik · ${number((data.stats.seconds / 60).toInt())} мин музыки · ${track.title} — ${track.artist}"
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        }, null))
                                    },
                                )
                            }
                        }
                    }

                    if (running) {
                        Row(
                            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { select(index - 1) }) { Icon(Icons.Filled.ChevronLeft, "Предыдущий слайд") }
                            Text(backingTrack?.title.orEmpty(), Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { paused = !paused }) {
                                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Продолжить" else "Пауза")
                            }
                            IconButton(onClick = { select(index + 1) }) { Icon(Icons.Filled.ChevronRight, "Следующий слайд") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapContent(
    recap: RecapPayload,
    page: RecapPage,
    artworkSize: androidx.compose.ui.unit.Dp,
    onStart: () -> Unit,
    onReplay: () -> Unit,
    onPlaylist: () -> Unit,
    onShare: () -> Unit,
) {
    val track = recap.stats.tracks.firstOrNull()
    val artist = recap.stats.artists.firstOrNull()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        when (page) {
            RecapPage.Intro -> {
                CoverFan(recap.stats.tracks, artworkSize)
                Text("Твой\n${recap.year}", style = displayStyle(76))
                Text("У каждого года есть своё звучание", style = MaterialTheme.typography.titleLarge)
                RecapButton("Начать →", onStart)
            }
            RecapPage.Time -> {
                CoverFan(recap.stats.tracks, artworkSize)
                Text("Музыка была рядом", style = displayStyle(28))
                Text(number((recap.stats.seconds / 60).toInt()), style = displayStyle(63), maxLines = 1)
                Text("минут с музыкой", style = MaterialTheme.typography.titleLarge)
                if (recap.stats.seconds >= 3600) Text("Если включить всё подряд — ${(recap.stats.seconds / 3600).toInt()} ч ${(recap.stats.seconds / 60).toInt() % 60} мин")
                Text("Прослушиваний за год · ${number(recap.stats.plays)}")
            }
            RecapPage.Track -> if (track != null) {
                Text("Твой трек года", style = displayStyle(28))
                Artwork(track.id, Modifier.size(artworkSize), 640, track.title, RoundedCornerShape(22.dp))
                Text(track.title, style = displayStyle(30), maxLines = 2)
                Text(track.artist, color = MusikColors.muted, maxLines = 1)
                Text("Прослушиваний · ${number(track.count)}")
            }
            RecapPage.Artist -> if (artist != null) {
                Text("Твой артист года", style = displayStyle(28))
                Text(artist.artist, style = displayStyle(42), maxLines = 2)
                Artwork(recap.artistTrack?.id, Modifier.size(artworkSize), 640, artist.artist, RoundedCornerShape(22.dp),
                    photo = App.state.photos.url(App.api, artist.artist, 640))
                Text("Прослушиваний · ${number(artist.count)}")
            }
            RecapPage.Tracks -> {
                Text("Твой хит-парад", style = displayStyle(38))
                Text("Песни, к которым ты возвращался чаще всего.")
                recap.stats.tracks.take(5).forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", Modifier.width(30.dp), style = displayStyle(26))
                        Artwork(item.id, Modifier.size(minOf(55.dp, artworkSize / 5)), fallback = item.title)
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 1, fontWeight = FontWeight.Bold)
                            Text(item.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("×${item.count}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            RecapPage.Artists -> {
                Text("Твои люди", style = displayStyle(42))
                Text("Эти голоса звучали у тебя чаще других.")
                recap.stats.artists.take(3).forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${i + 1}", style = displayStyle(30))
                        Artwork(recap.stats.tracks.firstOrNull { it.artist == item.artist }?.id,
                            Modifier.size(minOf(76.dp, artworkSize / 3)).clip(CircleShape), fallback = item.artist,
                            photo = App.state.photos.url(App.api, item.artist, 320))
                        Column {
                            Text(item.artist, style = displayStyle(22), maxLines = 2)
                            Text("Прослушиваний · ${item.count}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            RecapPage.Finish -> {
                Text("Так звучал\nтвой ${recap.year}", style = displayStyle(44))
                if (track != null) {
                    CoverFan(recap.stats.tracks, artworkSize * 0.7f)
                    Text("${number((recap.stats.seconds / 60).toInt())} минут вместе", fontWeight = FontWeight.Bold)
                    Text("Трек года — ${track.title}", maxLines = 2)
                    if (artist != null) Text("Артист года — ${artist.artist}", maxLines = 2)
                    RecapButton("Слушать плейлист года", onPlaylist)
                    OutlinedButton(onClick = onShare) { Text("Поделиться итогами", color = recapCream) }
                } else Text("В истории пока нет треков за этот год.")
                TextButton(onClick = onReplay) { Text("Смотреть снова", color = recapCream) }
            }
        }
    }
}

@Composable
private fun CoverFan(tracks: List<RecapTrack>, artworkSize: androidx.compose.ui.unit.Dp) {
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy((-18).dp)) {
        tracks.take(3).forEachIndexed { i, item ->
            Artwork(item.id, Modifier.size(minOf(120.dp, artworkSize * 0.42f)).rotate((i - 1) * 9f), fallback = item.title)
        }
    }
}

@Composable
private fun RecapSilk(modifier: Modifier, tint: Color) {
    val motion = rememberInfiniteTransition(label = "Свет")
    val phase by motion.animateFloat(0f, 6.283185f,
        infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "Шёлк")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val light = Brush.linearGradient(listOf(tint.copy(alpha = .18f), Color(0xAAFFAB54),
            Color(0x66FF7299), tint), Offset.Zero, Offset(w, h))
        repeat(32) { i ->
            val u = i / 31f
            val wave = sin(phase + 2f * u)
            val ribbon = Path().apply {
                moveTo(-.15f * w, h * (.17f + .21f * u))
                cubicTo(w * (.95f + wave * .22f), -h * (.18f + .35f * u),
                    w * (.22f + wave * .24f), h * (1.08f - .27f * u),
                    1.15f * w, h * (.56f + .28f * u))
            }
            drawPath(ribbon, light, style = Stroke((if (i % 8 == 0) 2.2.dp else .85.dp).toPx()))
        }
    }
}

@Composable
private fun RecapButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = MusikColors.accentBright, contentColor = Color.Black)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

private fun number(value: Int) = NumberFormat.getIntegerInstance().format(value)

private class RecapSound {
    private var current: MediaPlayer? = null
    private var prepared = false
    private val fading = mutableMapOf<MediaPlayer, ValueAnimator>()

    private var completed = false
    private var silent = false
    private var gain = 0f
    private var attack: ValueAnimator? = null

    fun mute(value: Boolean) {
        silent = value
        if (prepared) current?.setVolume(if (silent) 0f else gain, if (silent) 0f else gain)
    }

    fun fraction(): Float? {
        val media = current ?: return null
        if (!prepared || media.duration <= 0) return null
        return if (completed) 1f else (media.currentPosition.toFloat() / media.duration).coerceIn(0f, 1f)
    }

    fun prepare(bytes: ByteArray, position: () -> Float, onReady: () -> Unit) {
        val next = MediaPlayer()
        current = next
        completed = false
        gain = 0f
        try {
            next.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            next.setDataSource(object : MediaDataSource() {
                override fun getSize(): Long = bytes.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            next.setOnPreparedListener {
                if (current == next) {
                    if (position() > 0) next.seekTo((position() * next.duration).toInt())
                    prepared = true
                    onReady()
                }
            }
            next.setOnCompletionListener { if (current == next) completed = true }
            next.setOnErrorListener { _, _, _ ->
                if (current == next) {
                    releaseCurrent()
                    onReady()
                }
                true
            }
            next.prepareAsync()
        } catch (error: Exception) {
            releaseCurrent()
            throw error
        }
    }

    fun setPlaying(play: Boolean) {
        val media = current ?: return
        if (!prepared) return
        if (play && !completed && !media.isPlaying) {
            attack?.cancel()
            gain = 0f
            media.setVolume(0f, 0f)
            media.start()
            attack = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1250
                addUpdateListener {
                    gain = it.animatedValue as Float
                    val level = if (silent) 0f else gain
                    media.setVolume(level, level)
                }
                start()
            }
        }
        if (!play && media.isPlaying) media.pause()
    }

    fun fadeOut() {
        attack?.cancel()
        val old = current ?: return
        val playing = prepared && old.isPlaying
        current = null
        prepared = false
        if (!playing) {
            old.release()
            return
        }
        val fade = ValueAnimator.ofFloat(if (silent) 0f else gain, 0f).apply {
            duration = 250
            addUpdateListener {
                val volume = it.animatedValue as Float
                old.setVolume(volume, volume)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fading.remove(old) != null) old.release()
                }
            })
        }
        fading[old] = fade
        fade.start()
    }

    fun close() {
        releaseCurrent()
        for ((media, fade) in fading.toMap()) {
            fading.remove(media)
            fade.cancel()
            media.release()
        }
    }

    private fun releaseCurrent() {
        attack?.cancel()
        current?.release()
        current = null
        prepared = false
    }
}
