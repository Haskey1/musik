package app.musik.android.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.musik.android.AppState
import app.musik.android.data.Api
import app.musik.android.data.ApiError
import app.musik.android.data.EventResult
import app.musik.android.data.Haptics
import app.musik.android.data.JsonFormat
import app.musik.android.data.OfflineStore
import app.musik.android.data.PlayPayload
import app.musik.android.data.QueueItem
import app.musik.android.data.Settings
import app.musik.android.data.Track
import app.musik.android.data.jsonBody
import app.musik.android.data.nonEmpty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/** Playback position and duration, kept apart so only the seek bar redraws twice a second. */
class PlaybackClock {
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
}

/**
 * Playback state machine mirroring the web client and the iOS app:
 * start → stream (+auth) → track_start → progress every ~4 s → track_end | skip → next.
 * Radio sessions have an endless `queue`; fixed lists (mixes, albums, `/api/play`)
 * carry `tracks` and stop with `ended: true` instead of falling back to radio.
 *
 * ExoPlayer holds one item at a time: the server decides what comes next.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val context: Context,
    private val api: Api,
    private val settings: Settings,
    private val offline: OfflineStore,
    private val app: AppState,
) {
    var current by mutableStateOf<Track?>(null)
        private set
    var queue by mutableStateOf<List<QueueItem>>(emptyList())
        private set
    var playlist by mutableStateOf<List<Track>>(emptyList())
        private set
    var fixed by mutableStateOf(false)
        private set
    var listName by mutableStateOf<String?>(null)
        private set
    var maturity by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    /** Bumped on every fresh like so the heart in the player can pulse. */
    var likeTick by mutableStateOf(0)
        private set
    var disliked by mutableStateOf(false)
        private set
    /** A start / skip request is in flight. */
    var busy by mutableStateOf(false)
        private set
    /** Playing files from the phone (saved or prefetched) without the server. */
    var offlineMode by mutableStateOf(false)
        private set
    /** The device has a network; says nothing about the server itself. */
    var networkUp by mutableStateOf(true)
        private set

    val clock = PlaybackClock()
    private var position: Double
        get() = clock.position
        set(v) { clock.position = v }
    private var duration: Double
        get() = clock.duration
        set(v) { clock.duration = v }

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())

    /** Streams and covers go through the shared OkHttp client (Bearer / cookie auth). */
    val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(api.http))

    val exo: ExoPlayer = run {
        val sink: AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(EqualizerProcessor()))
            .build()
        // Music only: one audio renderer whose sink carries the equalizer.
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
        }
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** What the media session (notification, lock screen, headset) controls. */
    val sessionPlayer: Player = SessionPlayer(exo)

    var sessionId: String? = null
        private set

    private var requestSeq = 0
    private var startSent = false
    private var listened = 0.0
    private var lastPos = 0.0
    private var lastProgressAt = 0L
    private var failStreak = 0
    private var seeking = false

    // Offline playback: a local list played without server events.
    private var offlineList: List<Track> = emptyList()
    private var offlineIndex = 0
    /** Entered automatically because the server went away (vs. «Сохранённые» chosen by hand). */
    private var offlineFallback = false
    /** The server answers again: the next track boundary switches back to online radio. */
    private var serverReachable = false
    private var probeJob: Job? = null
    private var stallJob: Job? = null
    private var prefetchJob: Job? = null

    companion object {
        /** How long to wait for the server before playing from the phone instead. */
        private const val EVENT_TIMEOUT = 7L
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updatePlaying()
                when (state) {
                    Player.STATE_READY -> {
                        failStreak = 0
                        val d = exo.duration
                        if (d != C.TIME_UNSET && d > 0) duration = d / 1000.0
                    }
                    Player.STATE_ENDED -> {
                        val id = current?.id
                        if (id != null && exo.currentMediaItem?.mediaId == id.toString()) trackFinished()
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlaying()

            override fun onPlayerError(error: PlaybackException) = playbackFailed(error)
        })
        scope.launch {
            while (isActive) {
                delay(500)
                tick()
            }
        }
        observeNetwork()
    }

    val hasSession get() = sessionId != null

    // MARK: Starting playback

    fun startRadio(seed: Int? = null) = start { api.radioStart(seed) }

    /** POST /api/play: `track_id`, `track_ids`, `artist`, `album`, `start_track_id`, `name`. */
    fun play(body: JsonObject) = start { api.play(body) }

    /**
     * Plays an artist or an album as a fixed list of ids, like the web UI does.
     * `/api/play` with `artist` only matches the exact artist field, while `/api/library`
     * also lists tracks where the artist is a co-author («X Feat. Y»), so such pages used to
     * fail with «ничего не найдено». [tracks] is the list the page already shows; without it
     * the library is asked first.
     */
    fun playCatalog(artist: String?, album: String? = null, tracks: List<Track> = emptyList(), startTrackId: Int? = null) {
        val artistName = artist?.trim().orEmpty()
        val albumName = album?.trim().orEmpty()
        val name = listOf(artistName, albumName).filter { it.isNotEmpty() }.joinToString(" — ")
        start {
            val list = tracks.ifEmpty {
                api.library(artist = artistName.ifEmpty { null }, album = albumName.ifEmpty { null })
            }
            if (list.isEmpty()) throw ApiError(0, "empty", "Треков не нашлось")
            api.play(jsonBody("track_ids" to list.map { it.id }, "name" to name.ifEmpty { "Плейлист" }, "start_track_id" to startTrackId))
        }
    }

    fun playMix(kind: String, startTrackId: Int? = null) = start { api.playMix(kind, startTrackId) }

    fun playSimilarNow() {
        val track = current ?: return
        start {
            val hits = api.recommendSeed(track.id)
            if (hits.isEmpty()) throw ApiError(0, "empty", "Похожих треков не нашлось")
            api.play(jsonBody("track_ids" to hits.map { it.id }, "name" to "Похоже на «${track.displayTitle}»"))
        }
    }

    /** Jump inside the current list / radio queue. */
    fun jump(trackId: Int, index: Int) {
        if (offlineMode) {
            if (trackId == current?.id) return
            val i = offlineList.indexOfFirst { it.id == trackId }
            if (i < 0) return
            offlineIndex = i
            load(offlineList[i], autoplay = true)
            return
        }
        val sid = sessionId ?: return
        if (trackId == current?.id) return
        start { api.jump(sid, index, trackId) }
    }

    private fun start(request: suspend () -> PlayPayload) {
        val req = nextRequest()
        scope.launch {
            try {
                val payload = request()
                if (req == requestSeq) apply(payload, autoplay = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (req != requestSeq) return@launch
                // Asked for music with no server in reach: play what is saved on the phone.
                if (isNetwork(e) && offline.saved.isNotEmpty()) {
                    offlineMode = false
                    enterOfflineFallback()
                } else {
                    app.show(e)
                }
            } finally {
                finish(req)
            }
        }
    }

    /** Resume the last session after an app restart (paused, like the web UI). */
    suspend fun restore() {
        if (current != null) return
        val sid = settings.sessionId ?: return
        try {
            val payload = api.now(sid)
            setSession(sid)
            apply(payload, autoplay = false)
        } catch (e: ApiError) {
            if (e.isNotFound) settings.sessionId = null
        } catch (e: Exception) {
            // keep the session for the next launch
        }
    }

    private fun apply(p: PlayPayload, autoplay: Boolean) {
        leaveOffline()
        p.sessionId?.let { setSession(it) }
        p.maturity?.let { maturity = it }
        val tracks = p.tracks ?: emptyList()
        fixed = p.fixed ?: tracks.isNotEmpty()
        if (fixed) {
            playlist = tracks
            queue = emptyList()
            listName = p.name.nonEmpty ?: p.mode
        } else {
            playlist = emptyList()
            queue = p.queue ?: emptyList()
            listName = p.name.nonEmpty ?: "Радио"
        }
        val cur = p.current
        if (cur != null) load(cur, autoplay) else if (autoplay) app.show("Здесь пока нечего играть")
    }

    private fun setSession(id: String) {
        sessionId = id
        settings.sessionId = id
    }

    // MARK: Transport

    fun togglePlay() = if (isPlaying) pause() else resume()

    fun resume() {
        val track = current ?: return
        if (exo.currentMediaItem == null || exo.playerError != null || exo.playbackState == Player.STATE_IDLE) {
            load(track, autoplay = true, announce = !startSent)
            return
        }
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
        if (!startSent) sendStart(track)
    }

    fun pause() = exo.pause()

    fun seek(seconds: Double) {
        if (exo.currentMediaItem == null) return
        val target = maxOf(0.0, if (duration > 0) minOf(seconds, duration - 0.25) else seconds)
        position = target
        lastPos = target
        exo.seekTo((target * 1000).toLong())
    }

    fun skip() {
        if (offlineMode) {
            offlineStep(+1)
            return
        }
        val track = current ?: return
        if (sessionId == null) return
        if (duration > 0 && listened / duration < 0.3) noteEarlySkip(track)
        if (playAheadLocally(track, "skip", "skipped")) return
        val req = nextRequest()
        exo.pause()
        scope.launch {
            try {
                val res = postEvent("skip", track, "skipped", retry = true, timeout = EVENT_TIMEOUT)
                if (req == requestSeq && res != null) advance(res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (req != requestSeq) return@launch
                if (isNetwork(e)) {
                    enterOfflineFallback()
                } else {
                    app.show(e)
                    exo.play()
                }
            } finally {
                finish(req)
            }
        }
    }

    private fun nextRequest(): Int {
        requestSeq++
        busy = true
        return requestSeq
    }

    private fun finish(req: Int) {
        if (req == requestSeq) busy = false
    }

    fun back() {
        if (offlineMode) {
            if (position > 3 || offlineIndex == 0) {
                seek(0.0)
                if (!isPlaying) resume()
            } else {
                offlineStep(-1)
            }
            return
        }
        if (current == null) return
        val sid = sessionId ?: return
        if (position > 3) {
            seek(0.0)
            listened = 0.0
            if (!isPlaying) resume()
            return
        }
        start { api.back(sid) }
    }

    private fun trackFinished() {
        val track = current ?: return
        ArtistSkips.reset(settings, track.artist)
        if (offlineMode) {
            offlineStep(+1)
            return
        }
        if (playAheadLocally(track, "track_end", "completed")) return
        val req = ++requestSeq
        position = duration
        scope.launch {
            try {
                val res = postEvent("track_end", track, "completed", retry = true, timeout = EVENT_TIMEOUT)
                if (req == requestSeq && res != null) advance(res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (req != requestSeq) return@launch
                // No server to ask for the next track: carry on from the phone.
                if (isNetwork(e)) enterOfflineFallback() else app.show(e)
            }
        }
    }

    /**
     * Next track already downloaded: start it right away and tell the server in the
     * background, so a slow network gives no pause between tracks. The server's answer
     * only refreshes the queue; if it never comes, the player goes offline without
     * interrupting the track that is already playing.
     */
    private fun playAheadLocally(track: Track, event: String, reason: String): Boolean {
        if (fixed && playlist.size <= 1) return false
        val next = upcomingTracks(1).firstOrNull() ?: return false
        if (offline.playableFile(next.id) == null) return false
        val req = ++requestSeq
        load(next, autoplay = true, announce = false)
        scope.launch {
            try {
                val res = postEvent(event, track, reason, retry = true, timeout = EVENT_TIMEOUT)
                if (req != requestSeq || current?.id != next.id) return@launch
                if (res != null) {
                    applyLists(res)
                    // the server's copy carries this play's impression id
                    res.next?.let { fresh -> if (fresh.id == next.id) current = fresh }
                }
                current?.let { sendStart(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (req != requestSeq || current?.id != next.id) return@launch
                if (isNetwork(e)) enterOfflineFallback(keepCurrent = true)
            }
        }
        return true
    }

    private fun advance(res: EventResult) {
        applyLists(res)
        val next = res.next
        if (next != null) {
            load(next, autoplay = true)
        } else if (res.ended == true) {
            exo.pause()
            app.show("Конец плейлиста")
        }
    }

    private fun applyLists(res: EventResult) {
        res.sessionId?.let { setSession(it) }
        res.maturity?.let { maturity = it }
        res.name.nonEmpty?.let { listName = it }
        val tracks = res.tracks
        val q = res.queue
        if (tracks != null) {
            fixed = true
            playlist = tracks
        } else if (q != null) {
            queue = q
        }
        schedulePrefetch()
    }

    // MARK: Rating

    val isFavorite: Boolean get() = current?.id?.let { it in app.favoriteTracks } ?: false

    /** ♥ = favorite toggle; a fresh heart also sends a `like` event (as the web UI does). */
    fun like() {
        val track = current ?: return
        scope.launch {
            val liked = app.toggleFavoriteTrack(track)
            if (liked == true) {
                likeTick++
                Haptics.like(context, settings)
                ArtistSkips.reset(settings, track.artist)
                runCatching { postEvent("like", track) }.getOrNull()?.let { applyLists(it) }
            } else if (liked == false) {
                Haptics.tick(context, settings)
            }
        }
    }

    /** From the notification or a headset: a dislike also moves on to the next track. */
    fun dislikeAndSkip() {
        val track = current ?: return
        if (offlineMode) {
            skip()
            return
        }
        scope.launch {
            runCatching { postEvent("dislike", track) }.getOrNull()?.let {
                applyLists(it)
                app.show("Дизлайк — меньше такого")
            }
            if (track.id == current?.id) skip()
        }
    }

    /** Dislike; pressing it again takes the dislike back (`undislike`). */
    fun dislike() {
        val track = current ?: return
        val undo = disliked
        scope.launch {
            try {
                val res = postEvent(if (undo) "undislike" else "dislike", track) ?: return@launch
                applyLists(res)
                if (track.id == current?.id) disliked = !undo
                Haptics.tick(context, settings)
                app.show(
                    when {
                        undo -> "Дизлайк снят"
                        res.ignored == true -> "Уже дизлайк"
                        else -> "Дизлайк — меньше такого"
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (undo && e.code == "unknown_event") {
                    // An older server doesn't know «undislike»: reset the button anyway.
                    if (track.id == current?.id) disliked = false
                    app.show("Дизлайк снят в приложении — обнови сервер, чтобы он забыл его")
                } else {
                    app.show(e)
                }
            } catch (e: Exception) {
                app.show(e)
            }
        }
    }

    // MARK: Hiding

    enum class HideTarget { Track, Artist }

    /** «Скрыть»: never again in radio, mixes and recommendations (undo in Profile → Скрыто). */
    fun hide(target: HideTarget, track: Track? = null) {
        val t = track ?: current ?: return
        val isCurrent = t.id == current?.id
        scope.launch {
            try {
                val res = api.createRule(if (target == HideTarget.Artist) "artist" else "track", "block", t, sessionId)
                if (target == HideTarget.Artist) ArtistSkips.reset(settings, t.artist)
                app.show(if (target == HideTarget.Artist) "Артист «${t.displayArtist}» скрыт" else "Трек скрыт")
                if (isCurrent) {
                    skip()
                } else if (!fixed) {
                    res.playback?.queue?.let { queue = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.show(e)
            }
        }
    }

    /**
     * Early skips only count for the current session on the server. Three early skips of
     * the same artist in a row make it play less often for good — a soft rule, undone in
     * Profile → Скрыто.
     */
    private fun noteEarlySkip(track: Track) {
        val artist = track.artist.nonEmpty ?: return
        if (ArtistSkips.add(settings, artist) != 3) return
        scope.launch {
            runCatching { api.createRule("artist", "downrank", track, null) }.getOrNull() ?: return@launch
            app.show("«$artist» теперь будет звучать реже. Скрыть совсем — в меню трека")
        }
    }

    // MARK: Loading

    private fun load(track: Track, autoplay: Boolean, announce: Boolean = true) {
        current = track
        disliked = false
        startSent = false
        position = 0.0
        duration = track.duration ?: 0.0
        listened = 0.0
        lastPos = 0.0
        lastProgressAt = System.currentTimeMillis()

        val local = offline.playableFile(track.id)
        // Saved or prefetched: plays even with no connection, starts instantly.
        val uri = if (local != null) Uri.fromFile(local) else api.streamUrl(track)?.let { Uri.parse(it) } ?: return
        val art = offline.artworkFile(track.id)?.let { Uri.fromFile(it) } ?: api.artworkUrl(track.id, 640)?.let { Uri.parse(it) }
        val item = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.displayTitle)
                    .setArtist(track.displayArtist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(art)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = autoplay
        schedulePrefetch()
        if (autoplay && announce) sendStart(track)
    }

    private fun sendStart(track: Track) {
        startSent = true
        if (offlineMode) return
        scope.launch { runCatching { postEvent("track_start", track) } }
    }

    private fun updatePlaying() {
        val state = exo.playbackState
        val playing = exo.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        val buffering = exo.playWhenReady && state == Player.STATE_BUFFERING
        if (buffering != isBuffering) {
            isBuffering = buffering
            if (buffering) watchStall() else stallJob?.cancel()
        }
        if (playing != isPlaying) isPlaying = playing
    }

    private fun playbackFailed(error: PlaybackException) {
        val track = current ?: return
        if (!offlineMode && (!networkUp || isNetworkError(error))) {
            enterOfflineFallback()
            return
        }
        failStreak++
        app.show("Не удалось воспроизвести «${track.displayTitle}»")
        // Unsupported codec or a missing file: move on, but don't spin through a broken library.
        when {
            offlineMode -> offlineStep(+1)
            failStreak <= 3 -> skip()
            else -> exo.pause()
        }
    }

    private fun tick() {
        if (current == null || exo.currentMediaItem == null) return
        val seconds = exo.currentPosition / 1000.0
        if (seconds > lastPos && seconds - lastPos < 5) listened += seconds - lastPos
        lastPos = seconds
        position = seconds
        val d = exo.duration
        if (d != C.TIME_UNSET && d > 0 && kotlin.math.abs(d / 1000.0 - duration) > 0.5) duration = d / 1000.0
        val now = System.currentTimeMillis()
        if (isPlaying && now - lastProgressAt >= 4000) {
            lastProgressAt = now
            scope.launch { runCatching { postEvent("progress") } }
        }
    }

    fun stop(clearSession: Boolean) {
        requestSeq++
        exo.stop()
        exo.clearMediaItems()
        current = null
        queue = emptyList()
        playlist = emptyList()
        listName = null
        position = 0.0
        duration = 0.0
        busy = false
        leaveOffline()
        if (clearSession) {
            sessionId = null
            settings.sessionId = null
        }
    }

    // MARK: Events

    private suspend fun postEvent(
        type: String,
        track: Track? = null,
        reason: String? = null,
        retry: Boolean = false,
        timeout: Long? = null,
    ): EventResult? {
        if (offlineMode) return null
        val sid = sessionId ?: return null
        val t = track ?: current ?: return null
        val isCurrent = t.id == current?.id
        val body = jsonBody(
            "type" to type,
            "event_id" to api.newEventId(),
            "track_id" to t.id,
            "session_id" to sid,
            "client_id" to settings.clientId,
            "device_id" to settings.deviceId,
            "position_sec" to if (isCurrent) position else 0.0,
            "duration_sec" to if (isCurrent && duration > 0) duration else (t.duration ?: 0.0),
            "listened_sec" to if (isCurrent) listened else 0.0,
            "impression_id" to t.impressionId,
            "reason" to reason,
        )
        val started = System.currentTimeMillis()
        val text = try {
            api.send("POST", "/api/events", body = body, timeoutSeconds = timeout)
        } catch (e: ApiError) {
            // A quick failure (dropped connection) is worth one more try — same event_id,
            // the server deduplicates. A slow one is not: that is a bad network.
            if (retry && e.status == 0 && System.currentTimeMillis() - started < 3000) {
                delay(500)
                api.send("POST", "/api/events", body = body, timeoutSeconds = timeout)
            } else throw e
        }
        return api.decode(EventResult.serializer(), text)
    }

    // MARK: Offline

    /** Plays saved tracks from the phone («Сохранённые» tab); works with or without a connection. */
    fun playOffline(tracks: List<Track>, startAt: Int = 0, shuffle: Boolean = false) {
        val list = if (shuffle) tracks.shuffled() else tracks
        val start = if (shuffle) 0 else startAt
        if (start !in list.indices) return
        startOffline(list, start, fallback = false, name = "Сохранённые")
    }

    /**
     * The server can't be reached mid-listening: continue with what is on the phone —
     * first the upcoming tracks already prefetched, then saved tracks in random order.
     */
    private fun enterOfflineFallback(keepCurrent: Boolean = false) {
        if (offlineMode) return
        val list = upcomingTracks(3).filter { offline.playableFile(it.id) != null }.toMutableList()
        val seen = list.map { it.id }.toMutableSet()
        current?.id?.let { seen.add(it) }
        list += offline.savedTracks.map { it.track }.filter { it.id !in seen }.shuffled()
        if (list.isEmpty()) {
            exo.pause()
            app.show("Нет сети, а сохранённых треков пока нет")
            return
        }
        val cur = current
        if (keepCurrent && cur != null && offline.playableFile(cur.id) != null) {
            // the track playing is already on the phone: let it play on, go offline after it
            startOffline(listOf(cur) + list, 0, fallback = true, name = "Без сети · сохранённое", reload = false)
        } else {
            startOffline(list, 0, fallback = true, name = "Без сети · сохранённое")
        }
        app.show("Нет сети — играю сохранённое")
    }

    private fun startOffline(list: List<Track>, index: Int, fallback: Boolean, name: String, reload: Boolean = true) {
        stallJob?.cancel()
        requestSeq++
        busy = false
        offlineMode = true
        offlineFallback = fallback
        offlineList = list
        offlineIndex = index
        fixed = true
        playlist = list
        queue = emptyList()
        listName = name
        if (reload) load(list[index], autoplay = true)
        if (fallback) startProbe()
    }

    private fun leaveOffline() {
        probeJob?.cancel()
        serverReachable = false
        offlineMode = false
        offlineFallback = false
        offlineList = emptyList()
        offlineIndex = 0
    }

    private fun offlineStep(delta: Int) {
        // Back online after an automatic switch: return to the radio at the track boundary.
        if (delta > 0 && offlineFallback && serverReachable) {
            returnOnline()
            return
        }
        var i = maxOf(0, offlineIndex + delta)
        if (i >= offlineList.size) {
            if (!offlineFallback || offlineList.isEmpty()) {
                exo.pause()
                app.show("Конец списка")
                return
            }
            offlineList = offlineList.shuffled() // keep the music going until the network is back
            playlist = offlineList
            i = 0
        }
        offlineIndex = i
        load(offlineList[i], autoplay = true)
    }

    private fun returnOnline() {
        val req = nextRequest()
        scope.launch {
            try {
                val payload = api.radioStart()
                if (req != requestSeq) return@launch
                app.show("Сеть вернулась — снова онлайн-радио")
                apply(payload, autoplay = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (req != requestSeq) return@launch
                // Not reachable after all: keep playing from the phone and keep checking.
                serverReachable = false
                startProbe()
                offlineStep(+1)
            } finally {
                finish(req)
            }
        }
    }

    /**
     * Checks every 15 s whether the server answers again. Doesn't rely on the network
     * callback alone: Wi-Fi can be "up" while the server is out of reach, and the other way round.
     */
    private fun startProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (isActive && offlineFallback && !serverReachable) {
                val started = System.currentTimeMillis()
                if (networkUp && api.ping() && System.currentTimeMillis() - started < 2500) {
                    if (!offlineFallback) return@launch
                    serverReachable = true
                    app.show("Связь есть — после этого трека включу онлайн-радио")
                    return@launch
                }
                delay(15_000)
            }
        }
    }

    /** The next tracks of the current radio or list, as far as the client knows them. */
    private fun upcomingTracks(n: Int): List<Track> {
        val curId = current?.id
        val next = if (fixed) {
            val i = playlist.indexOfFirst { it.id == curId }
            if (i >= 0) playlist.drop(i + 1).take(n) else playlist.take(n)
        } else {
            queue.take(n).map { it.asTrack() }
        }
        return next.filter { it.id != curId }
    }

    /** Downloads the next two tracks a few seconds after a track starts. */
    private fun schedulePrefetch() {
        prefetchJob?.cancel()
        if (offlineMode || !networkUp) return
        prefetchJob = scope.launch {
            delay(5_000)
            if (!offlineMode) offline.prefetch(upcomingTracks(2))
        }
    }

    /**
     * A streamed track stopped to buffer: with no network give up after a few seconds,
     * on a bad one after ~8 s, and continue from the phone instead of a long silence.
     */
    private fun watchStall() {
        stallJob?.cancel()
        val id = current?.id ?: return
        if (offlineMode || offline.playableFile(id) != null) return
        stallJob = scope.launch {
            delay(4_000)
            if (!isBuffering || offlineMode) return@launch
            if (!networkUp) {
                enterOfflineFallback()
                return@launch
            }
            delay(4_000)
            if (!isBuffering || offlineMode) return@launch
            enterOfflineFallback()
        }
    }

    private fun observeNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        networkUp = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { networkChanged(true) }
            }

            override fun onLost(network: Network) {
                main.post { networkChanged(false) }
            }
        })
    }

    private fun networkChanged(up: Boolean) {
        if (up == networkUp) return
        networkUp = up
        if (up) {
            schedulePrefetch()
            if (offlineFallback) startProbe()
            scope.launch { app.reconnect() }
        } else if (isBuffering) {
            watchStall()
        }
    }

    private fun isNetwork(e: Throwable) = (e as? ApiError)?.isNetwork == true

    private fun isNetworkError(e: PlaybackException) = e.errorCode in listOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

    /**
     * The player the media session sees: next / previous always available and routed
     * to the server-driven skip and back, play routed through [resume].
     */
    private inner class SessionPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        private val extra = Player.Commands.Builder()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        private val wrappers = HashMap<Player.Listener, Player.Listener>()

        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
            for (i in 0 until extra.size()) b.add(extra.get(i))
            return b.build()
        }

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

        // The wrapped player reports its own commands (no "next" with one item); keep ours.
        override fun addListener(listener: Player.Listener) {
            val wrapped = object : Player.Listener by listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(this@SessionPlayer.availableCommands)
                }
            }
            wrappers[listener] = wrapped
            super.addListener(wrapped)
        }

        override fun removeListener(listener: Player.Listener) {
            super.removeListener(wrappers.remove(listener) ?: listener)
        }

        override fun seekToNext() = skip()
        override fun seekToNextMediaItem() = skip()
        override fun seekToPrevious() = back()
        override fun seekToPreviousMediaItem() = back()
        override fun play() = resume()
    }
}

/** Early skips in a row per artist, kept across launches. */
object ArtistSkips {
    private const val KEY = "artistEarlySkips"
    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    private fun norm(artist: String) = artist.lowercase().trim()

    private fun load(settings: Settings): MutableMap<String, Int> =
        settings.getString(KEY)?.let { runCatching { JsonFormat.decodeFromString(serializer, it) }.getOrNull() }
            ?.toMutableMap() ?: mutableMapOf()

    /** Counts one more early skip and returns the new streak. */
    fun add(settings: Settings, artist: String): Int {
        val all = load(settings)
        val n = (all[norm(artist)] ?: 0) + 1
        all[norm(artist)] = n
        settings.putString(KEY, JsonFormat.encodeToString(serializer, all))
        return n
    }

    fun reset(settings: Settings, artist: String?) {
        val a = artist.nonEmpty ?: return
        val all = load(settings)
        if (all.remove(norm(a)) == null) return
        settings.putString(KEY, JsonFormat.encodeToString(serializer, all))
    }
}
