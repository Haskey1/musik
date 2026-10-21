package app.musik.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musik.android.data.Labels
import app.musik.android.ui.App
import app.musik.android.ui.ChipButton
import app.musik.android.ui.MusikColors
import app.musik.android.ui.PrimaryButton
import app.musik.android.ui.displayStyle

@Composable
fun HomeScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("musik", style = displayStyle(40))
        Text(Labels.maturity(App.player.maturity), color = MusikColors.teal)
        PrimaryButton(onClick = { App.player.startRadio() }) { Text("Радио") }
    }
}

@Composable
fun LibraryScreen() = Stub("Библиотека")

@Composable
fun DownloadsScreen() = Stub("Сохранённые: ${App.offline.saved.size}")

@Composable
fun ProfileScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("Профиль", style = displayStyle(32))
        ChipButton(onClick = { App.state.logout() }) { Text("Выйти") }
    }
}

@Composable
fun ArtistScreen(name: String) = Stub(name)

@Composable
fun AlbumScreen(artist: String?, album: String) = Stub(album)

@Composable
private fun Stub(title: String) {
    Text(title, Modifier.statusBarsPadding().padding(16.dp), style = displayStyle(28))
}
