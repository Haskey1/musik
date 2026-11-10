package app.musik.android.data

import android.net.Uri

/** Public «share» links: `<server>/open/...` shows a preview page and hands over to `musik://`. */
object Links {
    private fun enc(s: String) = Uri.encode(s)

    private fun query(vararg pairs: Pair<String, String?>): String =
        pairs.filter { !it.second.isNullOrBlank() }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }
            .let { if (it.isEmpty()) "" else "?$it" }

    fun track(base: String, track: Track) =
        "$base/open/track/${track.id}" + query("t" to track.title, "a" to track.artist)

    fun artist(base: String, name: String) = "$base/open/artist" + query("name" to name)

    fun album(base: String, artist: String?, album: String) = "$base/open/album" + query("album" to album, "artist" to artist)
}
