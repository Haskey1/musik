package app.musik.android

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a `musik://` link asks for. */
sealed interface DeepLink {
    data class Track(val id: Int) : DeepLink
    data class Artist(val name: String) : DeepLink
    data class Album(val album: String, val artist: String?) : DeepLink
}

/**
 * `musik://track/{id}`, `musik://artist?name=`, `musik://album?album=&artist=`.
 * A link can arrive before the app has signed in, so it waits here until the main screen shows up.
 */
object DeepLinks {
    var pending by mutableStateOf<DeepLink?>(null)

    fun accept(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        parse(intent.data)?.let { pending = it }
    }

    fun parse(uri: Uri?): DeepLink? {
        if (uri == null || uri.scheme != "musik") return null
        return when (uri.host) {
            "track" -> uri.pathSegments.firstOrNull()?.toIntOrNull()?.let { DeepLink.Track(it) }
            "artist" -> uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }?.let { DeepLink.Artist(it) }
            "album" -> uri.getQueryParameter("album")?.takeIf { it.isNotBlank() }
                ?.let { DeepLink.Album(it, uri.getQueryParameter("artist")?.takeIf { a -> a.isNotBlank() }) }
            else -> null
        }
    }
}
