package app.musik.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor

// DTOs for the Go player API (docs/API.md). Almost every field is optional:
// the server omits empty values.

@Serializable
data class Track(
    val id: Int,
    val artist: String? = null,
    /** Credited artists split by the server; library rows only. */
    val artists: List<String>? = null,
    val title: String? = null,
    val album: String? = null,
    val duration: Double? = null,
    val stream: String? = null,
    val artwork: String? = null,
    @SerialName("impression_id") val impressionId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val source: String? = null,
    val position: Int? = null,
    val explanation: String? = null,
) {
    val displayTitle: String get() = title.nonEmpty ?: "#$id"
    val displayArtist: String get() = artist ?: ""
    val subtitle: String get() = listOfNotNull(artist.nonEmpty, album.nonEmpty).joinToString(" · ")
}

/** An upcoming radio item, keyed by `track_id` rather than `id`. */
@Serializable
data class QueueItem(
    @SerialName("track_id") val trackId: Int,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val duration: Double? = null,
    val source: String? = null,
    @SerialName("impression_id") val impressionId: String? = null,
    val explanation: String? = null,
    @SerialName("new_boost") val newBoost: Boolean? = null,
) {
    fun asTrack() = Track(
        id = trackId, artist = artist, title = title, album = album, duration = duration,
        stream = "/api/stream/$trackId", artwork = "/api/artwork/$trackId",
        impressionId = impressionId, source = source,
    )
}

/** Response of radio/start, play, mixes/{kind}/play, session/jump|back and now. */
@Serializable
data class PlayPayload(
    @SerialName("session_id") val sessionId: String? = null,
    val mode: String? = null,
    val kind: String? = null,
    val name: String? = null,
    val index: Int? = null,
    val count: Int? = null,
    val current: Track? = null,
    val queue: List<QueueItem>? = null,
    val tracks: List<Track>? = null,
    val fixed: Boolean? = null,
    val maturity: String? = null,
)

/** Response of POST /api/events. `current` is a bare id there, so it is not decoded. */
@Serializable
data class EventResult(
    @SerialName("session_id") val sessionId: String? = null,
    val maturity: String? = null,
    val next: Track? = null,
    val queue: List<QueueItem>? = null,
    val tracks: List<Track>? = null,
    val ended: Boolean? = null,
    val index: Int? = null,
    val name: String? = null,
    val fixed: Boolean? = null,
    val rating: String? = null,
    val ignored: Boolean? = null,
)

@Serializable
data class RadioRule(
    @SerialName("rule_id") val ruleId: String,
    @SerialName("target_type") val targetType: String = "",
    val action: String = "",
    @SerialName("target_key") val targetKey: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class RulesResponse(val rules: List<RadioRule> = emptyList())

@Serializable
data class RuleCreated(val playback: PlayPayload? = null)

@Serializable
data class Mix(
    val kind: String,
    val title: String = "",
    val subtitle: String? = null,
    val highlight: Boolean? = null,
    val today: Boolean? = null,
    val tracks: Int? = null,
    val ready: Boolean? = null,
    @SerialName("cover_track_id") val coverTrackId: Int? = null,
    val stale: Boolean? = null,
) {
    val isWeekday: Boolean get() = kind.startsWith("weekday_")

    val metaLabel: String
        get() {
            val n = tracks ?: 0
            return when (kind) {
                "later" -> if (n > 0) "$n в очереди" else "пусто — добавь с плеера"
                "favorites" -> if (n > 0) "$n ♥" else "жми ♥ на треке"
                else -> if (ready == true) "$n треков" else "нажми «Обновить миксы»"
            }
        }
}

@Serializable
data class MixesResponse(val mixes: List<Mix> = emptyList())

@Serializable
data class ArtistItem(
    val artist: String,
    val tracks: Int? = null,
    @SerialName("cover_track_id") val coverTrackId: Int? = null,
    val artwork: String? = null,
    val explanation: String? = null,
)

@Serializable
data class AlbumItem(
    val artist: String? = null,
    val album: String,
    val tracks: Int? = null,
    @SerialName("cover_track_id") val coverTrackId: Int? = null,
    val artwork: String? = null,
    val game: Boolean? = null,
    val explanation: String? = null,
) {
    val key: String get() = albumKey(artist, album)
}

fun albumKey(artist: String?, album: String): String =
    (artist ?: "").lowercase() + "\u001F" + album.lowercase()

@Serializable
data class ArtistsResponse(val artists: List<ArtistItem>? = null)

@Serializable
data class AlbumsResponse(val albums: List<AlbumItem>? = null)

@Serializable
data class FavoriteTrack(
    @SerialName("track_id") val trackId: Int,
    val artist: String? = null,
    val title: String? = null,
    val duration: Double? = null,
    val track: Track? = null,
) {
    fun asTrack(): Track = track ?: Track(id = trackId, artist = artist, title = title, duration = duration)
}

@Serializable
data class FavoritesResponse(
    val tracks: List<FavoriteTrack>? = null,
    val artists: List<ArtistItem>? = null,
    val albums: List<AlbumItem>? = null,
)

@Serializable
data class RecommendResponse(
    val tracks: List<Track>? = null,
    val artists: List<ArtistItem>? = null,
    val albums: List<AlbumItem>? = null,
    val empty: Boolean? = null,
    val hint: String? = null,
)

@Serializable
data class DiscoverTip(
    val id: Int,
    val kind: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val explanation: String? = null,
    @SerialName("track_ids") val trackIds: List<Int>? = null,
) {
    val coverTrackId: Int? get() = trackIds?.firstOrNull()
}

@Serializable
data class DiscoverResponse(val tips: List<DiscoverTip>? = null)

@Serializable
data class FavoriteToggleResult(val favorited: Boolean = false, val type: String? = null)

@Serializable
data class AuthMe(
    val ok: Boolean = false,
    @SerialName("auth_enabled") val authEnabled: Boolean? = null,
)

@Serializable
data class Health(
    val ok: Boolean? = null,
    val version: String? = null,
    @SerialName("api_version") val apiVersion: String? = null,
    val tracks: Int? = null,
)

@Serializable
data class IssuedToken(val token: String? = null)

@Serializable
data class ArtistCount(val artist: String, val count: Int = 0)

@Serializable
data class Profile(
    val maturity: String? = null,
    @SerialName("n_positive") val nPositive: Int? = null,
    @SerialName("n_negative") val nNegative: Int? = null,
    @SerialName("ready_at") val readyAt: Int? = null,
    @SerialName("forming_at") val formingAt: Int? = null,
    val confidence: Double? = null,
    @SerialName("top_artists") val topArtists: List<ArtistCount>? = null,
)

@Serializable
data class ShareLink(
    val token: String,
    val name: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_listen_at") val lastListenAt: String? = null,
    @SerialName("listen_count") val listenCount: Int? = null,
    val active: Boolean? = null,
    val url: String = "",
)

@Serializable
data class SharesResponse(val shares: List<ShareLink>? = null)

@Serializable
data class ShareCreated(val token: String? = null, val url: String? = null)

@Serializable
data class JobRef(val id: Int? = null, @SerialName("job_id") val jobId: Int? = null)

@Serializable
data class JobStatus(val id: Int? = null, val status: String? = null, val error: String? = null)

@Serializable
data class Lyrics(
    val status: String? = null,
    @SerialName("plain_lyrics") val plainLyrics: String? = null,
    @SerialName("synced_lyrics") val syncedLyrics: String? = null,
)

// Labels shared with the web UI.
object Labels {
    fun maturity(m: String?): String = when (m) {
        "discovering" -> "изучаем вкус · слушай и скипай"
        "forming" -> "вкус формируется"
        "ready" -> "вкус готов"
        else -> "твой локальный микс"
    }

    private val sources = mapOf(
        "exploit" to "похоже на тебя",
        "transition" to "часто после этой",
        "explore_adjacent" to "чуть в сторону",
        "resurface" to "давно не звучало",
        "new_in_library" to "новое в библиотеке",
        "wildcard" to "для разнообразия",
        "explore" to "чуть в сторону",
        "radio_start" to "старт радио",
        "refill" to "добор очереди",
        "manual" to "ты выбрал",
        "aggregate" to "общая доля",
    )

    fun source(s: String?): String? = if (s.isNullOrBlank()) null else sources[s] ?: s

    fun why(item: QueueItem): String? {
        val reason = source(item.source) ?: return null
        return if (item.newBoost == true && item.source != "new_in_library") "$reason · свежее" else reason
    }
}

fun formatTime(seconds: Double?): String {
    val s = seconds ?: return "0:00"
    if (!s.isFinite() || s <= 0) return "0:00"
    val total = floor(s).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

val String?.nonEmpty: String?
    get() = this?.trim()?.takeIf { it.isNotEmpty() }

fun tracksWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "$n трек"
        m10 in 2..4 && m100 !in 12..14 -> "$n трека"
        else -> "$n треков"
    }
}
