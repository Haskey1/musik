package app.musik.android.ui.screens

import app.musik.android.ui.Routes
import app.musik.android.ui.ScrollToTopOnReselect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.musik.android.data.formatBytes
import app.musik.android.data.tracksWord
import app.musik.android.ui.App
import app.musik.android.ui.StatusBarScrim
import app.musik.android.ui.ChipButton
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.MusikColors
import app.musik.android.ui.PrimaryButton
import app.musik.android.ui.TrackRow
import app.musik.android.ui.displayStyle

/** «Сохранённые»: tracks kept on the phone, playable without a connection. */
@Composable
fun DownloadsScreen() {
    val state = App.state
    val player = App.player
    val offline = App.offline
    val items = offline.savedTracks

    fun play(index: Int, shuffle: Boolean) = player.playOffline(items.map { it.track }, index, shuffle)

    Box(Modifier.fillMaxSize()) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    ScrollToTopOnReselect(Routes.DOWNLOADS) { listState.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Сохранённые", style = displayStyle(32))
                if (!player.networkUp || state.offlineLaunch) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.WifiOff, null, Modifier.size(16.dp), tint = MusikColors.accentBright)
                        Text("Нет связи — доступны только сохранённые треки", color = MusikColors.accentBright, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (items.isNotEmpty()) {
                    Text("${tracksWord(items.size)} · ${formatBytes(offline.totalBytes)}", color = MusikColors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(onClick = { play(0, false) }) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text("Играть") }
                        ChipButton(onClick = { play(0, true) }) { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text("Перемешать") }
                    }
                }
            }
        }
        if (items.isEmpty()) item { EmptyHint("Пока пусто. Жми ↓ у трека или ставь ♥ — лайкнутые сохраняются сами.") }
        itemsIndexed(items) { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TrackRow(s.track, unlikable = true) { play(i, false) } }
                IconButton(onClick = { offline.remove(s.id) }) { Icon(Icons.Filled.DeleteOutline, "Удалить", tint = MusikColors.muted) }
            }
        }
        if (offline.downloading.isNotEmpty()) item {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MusikColors.muted)
                Text("Загружается: ${offline.downloading.size}", color = MusikColors.muted)
            }
        }
    }

        StatusBarScrim()
    }
}

