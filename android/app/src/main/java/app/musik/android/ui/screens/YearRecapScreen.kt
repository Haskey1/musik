package app.musik.android.ui.screens

import android.animation.ValueAnimator
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
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
import app.musik.android.ui.HazeTexture
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
    Time(Color(0xFF678A70)),
    Track(Color(0xFFF0453A)),
    Artist(Color(0xFF678A70)),
    Finish(Color(0xFFF1772D)),
}

private val recapCream = Color(0xFFF6E9DC)

@Composable
internal fun YearRecapBanner(year: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF49251C), Color(0xFF9C441E), MusikColors.accent)))
            .clickable(onClick = onOpen).padding(19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Твой $year", style = displayStyle(25), color = recapCream)
            Text("Смотреть итоги", fontWeight = FontWeight.SemiBold, color = recapCream)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = recapCream)
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
        else RecapPage.entries.filter { it != RecapPage.Artist || data.artistTrack != null }
    }
    val page = pages.getOrNull(index) ?: RecapPage.Intro
    val backingTrack = if (page == RecapPage.Artist) recap?.artistTrack else recap?.stats?.tracks?.firstOrNull()
    val running = page != RecapPage.Intro && page != RecapPage.Finish
    val enabled = active && running && !muted && !paused && !holding
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
            sound.prepare(bytes) {
                ready = true
                sound.setPlaying(playSound)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ready = true
        }
    }
    LaunchedEffect(enabled) { sound.setPlaying(enabled) }
    LaunchedEffect(index, running, paused, active, muted, ready, holding) {
        if (!running || paused || holding || !active) return@LaunchedEffect
        if (!muted && backingTrack != null && !ready) delay(6000)
        while (progress < 1f) {
            delay(100)
            progress = (progress + 1f / 160).coerceAtMost(1f)
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
                HazeTexture(Modifier.matchParentSize().alpha(0.27f))
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
                                    (fadeIn(tween(420)) + slideInHorizontally(tween(420)) { sign * it / 7 }) togetherWith
                                        (fadeOut(tween(340)) + slideOutHorizontally(tween(420)) { -sign * it / 7 })
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
                Text("Твой\n${recap.year}", style = displayStyle(76))
                Text("У каждого года есть своё звучание", style = MaterialTheme.typography.titleLarge)
                RecapButton("Начать →", onStart)
            }
            RecapPage.Time -> {
                Text("Музыка была рядом", style = displayStyle(28))
                Text(number((recap.stats.seconds / 60).toInt()), style = displayStyle(63), maxLines = 1)
                Text("минут музыки", style = MaterialTheme.typography.titleLarge)
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
            RecapPage.Finish -> {
                Text("Так звучал\nтвой ${recap.year}", style = displayStyle(44))
                if (track != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recap.stats.tracks.take(3).forEach {
                            Artwork(it.id, Modifier.size(minOf(82.dp, artworkSize / 3)), fallback = it.title)
                        }
                    }
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

    fun prepare(bytes: ByteArray, onReady: () -> Unit) {
        val next = MediaPlayer()
        current = next
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
                    prepared = true
                    onReady()
                }
            }
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
        if (play && !media.isPlaying) media.start()
        if (!play && media.isPlaying) media.pause()
    }

    fun fadeOut() {
        val old = current ?: return
        val playing = prepared && old.isPlaying
        current = null
        prepared = false
        if (!playing) {
            old.release()
            return
        }
        val fade = ValueAnimator.ofFloat(1f, 0f).apply {
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
        current?.release()
        current = null
        prepared = false
    }
}
