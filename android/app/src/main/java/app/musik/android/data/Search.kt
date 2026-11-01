package app.musik.android.data

import java.text.Normalizer
import java.util.Locale

/** Client-side catalog search: all words, any order, across all relevant fields. */
object SearchMatcher {
    fun matches(query: String, vararg fields: String?): Boolean = matches(query, fields.asIterable())

    fun matches(query: String, fields: Iterable<String?>): Boolean {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fold(fields.filterNotNull().joinToString(" "))
        return words.all { it in haystack }
    }

    fun track(query: String, track: Track): Boolean = matches(
        query,
        buildList {
            add(track.title)
            add(track.artist)
            add(track.album)
            addAll(track.artists ?: emptyList())
        },
    )

    private fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
}
