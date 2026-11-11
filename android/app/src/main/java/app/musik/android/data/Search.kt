package app.musik.android.data

import java.text.Normalizer
import java.util.Locale

/** Shared normalization; regexes and catalog text are no longer rebuilt per keystroke. */
object SearchMatcher {
    private val whitespace = Regex("\\s+")
    private val combiningMarks = Regex("\\p{Mn}+")

    internal fun words(query: String): List<String> = fold(query).split(whitespace).filter { it.isNotEmpty() }
    internal fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(combiningMarks, "").lowercase(Locale.ROOT)
    internal fun trackText(track: Track): String = listOfNotNull(track.title, track.artist, track.album)
        .plus(track.artists.orEmpty()).joinToString(" ")

    fun matches(query: String, vararg fields: String?): Boolean = matches(query, fields.asIterable())
    fun matches(query: String, fields: Iterable<String?>): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val haystack = fold(fields.filterNotNull().joinToString(" "))
        return words.all { it in haystack }
    }
    fun track(query: String, track: Track): Boolean = matches(query, listOf(trackText(track)))
}
