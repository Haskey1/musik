package app.musik.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.musik.android.data.*
import app.musik.android.ui.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
private data class ListeningTrack(@SerialName("track_id") val trackId: Int, val title: String = "", val artist: String = "", val plays: Int = 0)
@Serializable
private data class ListeningPeriod(
    val year: Int = 0,
    @SerialName("listened_sec") val listenedSec: Double = 0.0,
    val plays: Int = 0,
    @SerialName("finish_rate") val finishRate: Double = 0.0,
    @SerialName("top_tracks") val tracks: List<ListeningTrack>? = null,
    @SerialName("top_artists") val artists: List<ArtistCount>? = null,
)
@Serializable
private data class WeeklyListening(val listening: ListeningPeriod)
@Serializable
private data class YearlyListening(val year: ListeningPeriod)

/** The same weekly and calendar-year statistics and playback links as iOS. */
@Composable
fun ListeningStatisticsScreen() {
    var weekly by remember { mutableStateOf<ListeningPeriod?>(null) }
    var yearly by remember { mutableStateOf<ListeningPeriod?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        if (loading) return
        loading = true
        try {
            coroutineScope {
                val week = async { App.api.get<WeeklyListening>("/api/metrics/weekly") }
                val year = async { App.api.get<YearlyListening>("/api/metrics/listening") }
                weekly = week.await().listening
                yearly = year.await().year
            }
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            error = if ((e as? ApiError)?.status == 404) "Для статистики нужно обновить сервер musik до версии с поддержкой истории прослушиваний."
                else "Не удалось загрузить статистику. ${e.localizedMessage.orEmpty()}"
        } finally { loading = false }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val back = LocalProfileBack.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Статистика", style = displayStyle(28))
        }
        if (loading && weekly == null) CircularProgressIndicator(color = MusikColors.accent)
        weekly?.let { ListeningPeriodCard(it, "За неделю", "Время, дослушивания и то, что звучало чаще за последние 7 дней.") }
        yearly?.let { ListeningPeriodCard(it, "${it.year} в музыке", "Твои прослушивания с начала года.") }
        error?.let { Text(it, color = MusikColors.muted) }
        ChipButton(onClick = { scope.launch { load() } }, enabled = !loading) { Text(if (error != null) "Попробовать снова" else "Обновить") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ListeningPeriodCard(period: ListeningPeriod, title: String, subtitle: String) {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().background(MusikColors.surface, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MusikColors.muted, style = MaterialTheme.typography.bodyMedium)
        if (period.plays == 0) Text("Включи музыку — здесь появится твоя статистика", color = MusikColors.muted)
        else {
            val minutes = (period.listenedSec / 60).toInt().coerceAtLeast(0)
            val duration = if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else if (minutes == 0 && period.listenedSec > 0) "Меньше минуты" else "$minutes мин"
            listOf(duration to "время", "${(period.finishRate * 100).roundToInt()}%" to "дослушано", "${period.plays}" to "запусков").forEach { (value, label) ->
                Column(Modifier.fillMaxWidth().background(MusikColors.background.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Text(value, style = displayStyle(28))
                    Text(label, color = MusikColors.muted, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!period.tracks.isNullOrEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Text("Треки", fontWeight = FontWeight.Bold)
                period.tracks.forEach { track ->
                    Column(Modifier.fillMaxWidth().background(MusikColors.background.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .clickable { App.player.play(jsonBody("track_id" to track.trackId, "name" to track.title)) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" — "))
                        Text("${track.plays}", color = MusikColors.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!period.artists.isNullOrEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Text("Артисты", fontWeight = FontWeight.Bold)
                period.artists.forEach { artist ->
                    ChipButton(onClick = { nav.openArtist(artist.artist) }) { Text("${artist.artist} · ${artist.count}") }
                }
            }
        }
    }
}
