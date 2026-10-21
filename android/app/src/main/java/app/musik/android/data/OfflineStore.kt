package app.musik.android.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A track kept on the phone for listening without a connection. */
@Serializable
data class SavedTrack(
    val id: Int,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val duration: Double? = null,
    /** File name inside the offline folder ("123.m4a"). */
    val file: String,
    val bytes: Long = 0,
    val savedAt: Long = 0,
    /** Saved automatically because the track is liked; removed again when the like goes. */
    val auto: Boolean = false,
) {
    val track: Track get() = Track(id = id, artist = artist, title = title, album = album, duration = duration)
}

/**
 * Audio files on the device, in two places:
 *
 * * **Saved** (files/offline): tracks the user saved or liked. Kept until removed,
 *   listed on the «Сохранённые» tab, played when there is no connection.
 * * **Prefetch** (cache/prefetch): the next couple of tracks of the current radio or
 *   playlist, downloaded ahead so a dropped connection does not stop playback.
 *
 * Files are fetched with the same stream URL as playback (mobile quality when that
 * setting is on), so a saved track sounds exactly like a streamed one.
 */
class OfflineStore(context: Context, private val api: Api, private val settings: Settings) {
    var saved by mutableStateOf<Map<Int, SavedTrack>>(emptyMap())
        private set
    var downloading by mutableStateOf<Set<Int>>(emptySet())
        private set
    /** Bumped when a saved cover arrives, so lists redraw it. */
    var artworkRevision by mutableIntStateOf(0)
        private set
    var autoSaveFavorites by mutableStateOf(settings.autoSaveFavorites)
        private set

    var toast: (String) -> Unit = {}

    private val scope = MainScope()
    private val savedDir = File(context.filesDir, "offline").apply { mkdirs() }
    private val prefetchDir = File(context.cacheDir, "prefetch").apply { mkdirs() }
    private val prefetched = mutableMapOf<Int, File>()
    private val prefetching = mutableSetOf<Int>()
    private val pending = ArrayDeque<Pair<Track, Boolean>>()
    private var worker: Job? = null
    private val prefetchLimit = 8
    private val indexFile = File(savedDir, "index.json")
    private val listSerializer = ListSerializer(SavedTrack.serializer())

    init {
        loadIndex()
        prefetchDir.listFiles()?.forEach { f ->
            f.nameWithoutExtension.toIntOrNull()?.let { prefetched[it] = f }
        }
    }

    // MARK: Queries

    fun isSaved(id: Int) = saved.containsKey(id)

    /** Saved tracks, newest first. */
    val savedTracks: List<SavedTrack> get() = saved.values.sortedByDescending { it.savedAt }

    val totalBytes: Long get() = saved.values.sumOf { it.bytes }

    /** A local file for this track, saved or prefetched; null when it has to be streamed. */
    fun playableFile(id: Int): File? {
        saved[id]?.let { s -> File(savedDir, s.file).takeIf { it.exists() }?.let { return it } }
        return prefetched[id]?.takeIf { it.exists() }
    }

    fun artworkFile(id: Int): File? = File(savedDir, "$id.jpg").takeIf { it.exists() }

    fun updateAutoSave(on: Boolean) {
        autoSaveFavorites = on
        settings.autoSaveFavorites = on
    }

    // MARK: Saving

    fun save(track: Track, auto: Boolean = false) {
        saved[track.id]?.let { s ->
            if (!auto && s.auto) { // an explicit save keeps the file even after an unlike
                saved = saved + (track.id to s.copy(auto = false))
                writeIndex()
            }
            return
        }
        if (track.id in downloading || pending.any { it.first.id == track.id }) return
        pending.addLast(track to auto)
        runQueue()
    }

    fun remove(id: Int) {
        val s = saved[id] ?: return
        saved = saved - id
        File(savedDir, s.file).delete()
        File(savedDir, "$id.jpg").delete()
        writeIndex()
    }

    fun removeAll() = saved.keys.toList().forEach { remove(it) }

    /** Liked tracks follow the like: saved when liked, removed when unliked (only auto-saved ones). */
    fun favoriteChanged(track: Track, favorited: Boolean) {
        if (!autoSaveFavorites) return
        if (favorited) save(track, auto = true)
        else if (saved[track.id]?.auto == true) remove(track.id)
    }

    /** Brings saved likes in line with the server's list of favorites. */
    fun syncFavorites(tracks: List<Track>) {
        if (!autoSaveFavorites) return
        val ids = tracks.map { it.id }.toSet()
        tracks.filter { !saved.containsKey(it.id) }.forEach { save(it, auto = true) }
        saved.values.filter { it.auto && it.id !in ids }.forEach { remove(it.id) }
    }

    private fun runQueue() {
        if (worker != null) return
        worker = scope.launch {
            while (pending.isNotEmpty()) {
                val (track, auto) = pending.removeFirst()
                downloading = downloading + track.id
                download(track, auto)
                downloading = downloading - track.id
            }
            worker = null
        }
    }

    private suspend fun download(track: Track, auto: Boolean) {
        // Already fetched ahead for playback: keep that file instead of downloading again.
        prefetched[track.id]?.takeIf { it.exists() }?.let { cached ->
            val dest = File(savedDir, cached.name)
            val moved = withContext(Dispatchers.IO) { dest.delete(); cached.renameTo(dest) || runCatching { cached.copyTo(dest, true); cached.delete() }.isSuccess }
            if (moved) {
                prefetched.remove(track.id)
                finishSave(track, dest, auto)
                return
            }
        }
        val file = fetchAudio(track, savedDir)
        if (file == null) {
            if (!auto) toast("Не удалось сохранить «${track.displayTitle}»")
            return
        }
        finishSave(track, file, auto)
    }

    private suspend fun finishSave(track: Track, file: File, auto: Boolean) {
        saved = saved + (track.id to SavedTrack(
            id = track.id, artist = track.artist, title = track.title, album = track.album,
            duration = track.duration, file = file.name, bytes = file.length(),
            savedAt = System.currentTimeMillis(), auto = auto,
        ))
        writeIndex()
        if (!auto) toast("Сохранено: «${track.displayTitle}»")
        fetchArtwork(track.id)
    }

    private suspend fun fetchArtwork(id: Int) {
        val url = api.artworkUrl(id, 640) ?: return
        val ok = withContext(Dispatchers.IO) {
            try {
                api.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val bytes = resp.body?.bytes() ?: return@use false
                    File(savedDir, "$id.jpg").writeBytes(bytes)
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
        if (ok) artworkRevision++
    }

    // MARK: Prefetch

    /** Downloads upcoming tracks so the next one plays even if the connection drops. */
    fun prefetch(tracks: List<Track>) {
        val keep = tracks.map { it.id }.toSet()
        for (track in tracks) {
            if (playableFile(track.id) != null || track.id in prefetching) continue
            prefetching.add(track.id)
            scope.launch {
                fetchAudio(track, prefetchDir)?.let { file ->
                    prefetched[track.id] = file
                    trimPrefetch(keep)
                }
                prefetching.remove(track.id)
            }
        }
    }

    private fun trimPrefetch(keeping: Set<Int>) {
        if (prefetched.size <= prefetchLimit) return
        val byAge = prefetched.entries.sortedBy { it.value.lastModified() }
        for ((id, file) in byAge) {
            if (prefetched.size <= prefetchLimit) break
            if (id in keeping) continue
            file.delete()
            prefetched.remove(id)
        }
    }

    // MARK: Network

    /** Downloads the track's stream into `dir` as "<id>.<ext>"; the extension comes from the response type. */
    private suspend fun fetchAudio(track: Track, dir: File): File? {
        val url = api.streamUrl(track) ?: return null
        return withContext(Dispatchers.IO) {
            val client = api.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body ?: return@use null
                    val ext = fileExtension(resp.header("Content-Type"), url)
                    val tmp = File(dir, "${track.id}.part")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    val dest = File(dir, "${track.id}.$ext")
                    dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.delete()
                        return@use null
                    }
                    dest
                }
            } catch (e: IOException) {
                File(dir, "${track.id}.part").delete()
                null
            }
        }
    }

    private fun fileExtension(mime: String?, url: String): String =
        when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/aacp" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            else -> url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                .lowercase().takeIf { it.length in 2..4 } ?: "m4a"
        }

    // MARK: Index

    private fun loadIndex() {
        val list = runCatching { JsonFormat.decodeFromString(listSerializer, indexFile.readText()) }.getOrNull() ?: return
        saved = list.filter { File(savedDir, it.file).exists() }.associateBy { it.id }
    }

    private fun writeIndex() {
        val text = JsonFormat.encodeToString(listSerializer, saved.values.toList())
        scope.launch(Dispatchers.IO) {
            val tmp = File(savedDir, "index.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(indexFile)
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes Б"
}
