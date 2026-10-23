package app.musik.android.ui.screens

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Search
import app.musik.android.data.Haptics
import app.musik.android.ui.Routes
import app.musik.android.ui.ScrollToTopOnReselect
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.musik.android.data.AlbumItem
import app.musik.android.data.ApiError
import app.musik.android.data.ArtistItem
import app.musik.android.data.DiscoverTip
import app.musik.android.data.FavoritesResponse
import app.musik.android.data.Labels
import app.musik.android.data.Mix
import app.musik.android.data.RecommendResponse
import app.musik.android.data.Track
import app.musik.android.data.jsonBody
import app.musik.android.data.nonEmpty
import app.musik.android.ui.AlbumCard
import app.musik.android.ui.App
import app.musik.android.ui.StatusBarScrim
import app.musik.android.ui.ArtistCard
import app.musik.android.ui.Artwork
import app.musik.android.ui.Card
import app.musik.android.ui.ChipButton
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.LocalNavigator
import app.musik.android.ui.MixCard
import app.musik.android.ui.MusikColors
import app.musik.android.ui.PrimaryButton
import app.musik.android.ui.Shelf
import app.musik.android.ui.TrackCard
import app.musik.android.ui.displayStyle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home shelves, kept in memory so coming back to the tab doesn't reload them. */
class HomeModel {
    var mixes by mutableStateOf<List<Mix>>(emptyList())
    var favorites by mutableStateOf(FavoritesResponse())
    var recs by mutableStateOf(RecommendResponse())
    var newTips by mutableStateOf<List<DiscoverTip>>(emptyList())
    var oldTips by mutableStateOf<List<DiscoverTip>>(emptyList())
    var artists by mutableStateOf<List<ArtistItem>>(emptyList())
    var albums by mutableStateOf<List<AlbumItem>>(emptyList())
    var tracks by mutableStateOf<List<Track>>(emptyList())
    var maturity by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var buildingMixes by mutableStateOf(false)
    var revision = -1

    suspend fun load() = coroutineScope {
        val api = App.api
        val m = async { runCatching { api.mixes() }.getOrNull() }
        val r = async { runCatching { api.recommendFavorites() }.getOrNull() }
        val n = async { runCatching { api.discover(false) }.getOrNull() }
        val o = async { runCatching { api.discover(true) }.getOrNull() }
        val ar = async { runCatching { api.artists(28) }.getOrNull() }
        val al = async { runCatching { api.albums(28) }.getOrNull() }
        val tr = async { runCatching { api.library(limit = 36) }.getOrNull() }
        val p = async { runCatching { api.profile() }.getOrNull() }
        val f = async { runCatching { api.favorites() }.getOrNull() }
        m.await()?.let { mixes = it }
        r.await()?.let { recs = it }
        n.await()?.let { newTips = it }
        o.await()?.let { oldTips = it }
        ar.await()?.let { artists = it }
        al.await()?.let { albums = it }
        tr.await()?.let { tracks = it }
        p.await()?.maturity?.let { maturity = it }
        f.await()?.let { favorites = it; App.state.applyFavorites(it) }
        loaded = true
    }

    /** After a heart or a «Потом»: favorites, mixes and recommendations change. */
    suspend fun loadFavorites() = coroutineScope {
        val api = App.api
        val f = async { runCatching { api.favorites() }.getOrNull() }
        val m = async { runCatching { api.mixes() }.getOrNull() }
        val r = async { runCatching { api.recommendFavorites() }.getOrNull() }
        f.await()?.let { favorites = it; App.state.applyFavorites(it) }
        m.await()?.let { mixes = it }
        r.await()?.let { recs = it }
    }

    companion object {
        var shared = HomeModel()
            private set

        /** Signed out: the next account starts from an empty screen. */
        fun reset() {
            shared = HomeModel()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val model = HomeModel.shared
    val state = App.state
    val player = App.player
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!model.loaded) {
            model.revision = state.homeRevision
            model.load()
        }
    }
    LaunchedEffect(state.homeRevision) {
        if (model.loaded && model.revision != state.homeRevision) {
            model.revision = state.homeRevision
            model.loadFavorites()
        }
    }

    fun playMix(mix: Mix) {
        if (mix.ready == false) {
            state.show(if (mix.kind == "later" || mix.kind == "favorites") mix.metaLabel else "Микс не собран — нажми «Обновить миксы»")
            return
        }
        player.playMix(mix.kind)
    }

    Box(Modifier.fillMaxSize()) {
    PullToRefreshBox(
        isRefreshing = model.refreshing,
        onRefresh = {
            scope.launch {
                model.refreshing = true
                state.photos.refresh(App.api, force = true)
                model.load()
                model.refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        val listState = rememberLazyListState()
        ScrollToTopOnReselect(Routes.HOME) { listState.animateScrollToItem(0) }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        Column {
                            Text("musik", style = displayStyle(40))
                            Text(Labels.maturity(player.maturity ?: model.maturity), color = MusikColors.teal)
                        }
                        IconButton(onClick = { nav.openSearch() }, Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Search, "Поиск", Modifier.size(26.dp), tint = Color.White)
                        }
                    }
                    RadioCard()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChipButton(onClick = { scope.launch { refreshMixes(model) } }, enabled = !model.buildingMixes) {
                            if (model.buildingMixes) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                            Text(if (model.buildingMixes) "Собираю…" else "Обновить миксы")
                        }
                    }
                }
            }
            if (!model.loaded) {
                item { Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MusikColors.accent) } }
            }

            val main = model.mixes.filter { !it.isWeekday }
            val week = model.mixes.filter { it.isWeekday }
            if (main.isNotEmpty()) item { Shelf("Для тебя") { items(main) { MixCard(it) { playMix(it) } } } }
            if (week.isNotEmpty()) item { Shelf("Дни недели") { items(week) { MixCard(it) { playMix(it) } } } }

            val favTracks = (model.favorites.tracks ?: emptyList()).map { it.asTrack() }
            if (favTracks.isNotEmpty()) item {
                Shelf("Любимые песни") {
                    items(favTracks) { t -> TrackCard(t) { player.playMix("favorites", startTrackId = t.id) } }
                }
            }
            model.favorites.artists?.takeIf { it.isNotEmpty() }?.let { list ->
                item { Shelf("Любимые артисты") { items(list) { ArtistCard(it) { a -> nav.openArtist(a) } } } }
            }
            model.favorites.albums?.takeIf { it.isNotEmpty() }?.let { list ->
                item { Shelf("Любимые альбомы") { items(list) { AlbumCard(it) { a, al -> nav.openAlbum(a, al) } } } }
            }

            val recTracks = model.recs.tracks ?: emptyList()
            if (recTracks.isNotEmpty()) item {
                Shelf("Похожее на любимое", "по звучанию (CLAP)") {
                    items(recTracks) { t ->
                        TrackCard(t) {
                            player.play(jsonBody("track_ids" to recTracks.map { it.id }, "start_track_id" to t.id, "name" to "Похожее на любимое"))
                        }
                    }
                }
            } else model.recs.hint?.takeIf { model.loaded }?.let { hint -> item { EmptyHint(hint) } }

            if (model.newTips.isNotEmpty()) item {
                Shelf("Открытия", "новое в библиотеке") { items(model.newTips) { TipCard(it) } }
            }
            if (model.oldTips.isNotEmpty()) item {
                Shelf("Давно не звучало") { items(model.oldTips) { TipCard(it) } }
            }
            if (model.artists.isNotEmpty()) item {
                Shelf("Артисты") { items(model.artists) { ArtistCard(it) { a -> nav.openArtist(a) } } }
            }
            if (model.albums.isNotEmpty()) item {
                Shelf("Альбомы") { items(model.albums) { AlbumCard(it) { a, al -> nav.openAlbum(a, al) } } }
            }
            if (model.tracks.isNotEmpty()) item {
                Shelf("Треки") {
                    items(model.tracks) { t ->
                        TrackCard(t) { player.play(jsonBody("track_id" to t.id, "name" to t.displayTitle)) }
                    }
                }
            }
            if (model.loaded && model.mixes.isEmpty() && model.tracks.isEmpty()) {
                item { EmptyHint("Библиотека пуста или сервер ещё индексирует треки.") }
            }
        }
    }
        StatusBarScrim()
    }
}

/**
 * The big «Радио» card. Radio playing = there is a current track, it is not a fixed list and not offline;
 * then a tap pauses / resumes it, otherwise it starts the radio.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RadioCard() {
    val player = App.player
    val radioPlaying = player.current != null && !player.fixed && !player.offlineMode
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.7f), label = "press")
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(14.dp, shape, ambientColor = MusikColors.accent, spotColor = MusikColors.accent)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFFF29A55), Color(0xFFE07A3A), Color(0xFF9C3F1E))))
            .clickable(interaction, indication = null) {
                Haptics.press(context, App.settings)
                if (radioPlaying) player.togglePlay() else player.startRadio()
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(60.dp).background(Color.Black.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            if (radioPlaying) EqualizerBars(animate = player.isPlaying)
            else Icon(Icons.Filled.Radio, null, Modifier.size(28.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text("Радио", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            val now = player.current?.takeIf { radioPlaying }
            Text(
                if (now != null) listOfNotNull(now.displayTitle, now.artist.nonEmpty).joinToString(" · ") else "Музыка под твой вкус",
                Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false,
            )
        }
        Box(Modifier.size(48.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (radioPlaying && player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (radioPlaying && player.isPlaying) "Пауза" else "Играть радио",
                Modifier.size(28.dp), tint = MusikColors.accent,
            )
        }
    }
}

/** Three bouncing bars, the «playing» mark inside the radio card. */
@Composable
private fun EqualizerBars(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "bars")
    val periods = listOf(520, 380, 450)
    val heights = periods.mapIndexed { i, ms ->
        val h by transition.animateFloat(
            0.3f, 1f,
            infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse, StartOffset(i * 120)),
            label = "bar$i",
        )
        if (animate) h else 0.4f
    }
    Row(Modifier.height(26.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h -> Box(Modifier.width(5.dp).fillMaxHeight(h).background(Color.White, RoundedCornerShape(3.dp))) }
    }
}

@Composable
private fun TipCard(tip: DiscoverTip) {
    val player = App.player
    Card(
        tip.album ?: "—", tip.artist, width = 160.dp, note = tip.explanation,
        onClick = {
            val ids = tip.trackIds
            if (!ids.isNullOrEmpty()) player.play(jsonBody("track_ids" to ids, "name" to (tip.album ?: tip.artist ?: "Открытие")))
            else player.playCatalog(tip.artist, tip.album)
        },
    ) { m -> Artwork(tip.coverTrackId, m, 256, tip.album ?: "♪") }
}

/** Creates a public link to the radio, copies it and offers the system share sheet. */
suspend fun shareRadio(context: Context) {
    val created = App.api.createShare()
    val url = created.url.nonEmpty ?: throw ApiError(0, "share", "Сервер не вернул ссылку")
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("musik radio", url))
    App.state.show("Ссылка на эфир скопирована")
    shareText(context, url)
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private suspend fun refreshMixes(model: HomeModel) {
    val state = App.state
    model.buildingMixes = true
    try {
        val id = App.api.enqueueJob("mix_pack")
        if (id == null) {
            state.show("Обновление поставлено в очередь")
            return
        }
        state.show("Собираю миксы…")
        repeat(300) {
            delay(2000)
            val job = App.api.job(id)
            when (job.status) {
                "done" -> {
                    state.show("Миксы обновлены")
                    model.load()
                    return
                }
                "failed", "error", "cancelled" -> {
                    state.show(job.error.nonEmpty ?: "Не удалось обновить миксы")
                    return
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        state.show(e)
    } finally {
        model.buildingMixes = false
    }
}
