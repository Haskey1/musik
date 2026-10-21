package app.musik.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musik.android.AppState
import app.musik.android.ui.ChipButton
import app.musik.android.ui.MusikColors
import app.musik.android.ui.displayStyle

@Composable
fun MainScreen(state: AppState) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("musik", style = displayStyle(40))
        Text("Вход выполнен: ${state.settings.baseUrl}", color = MusikColors.muted)
        Text("Любимых треков: ${state.favoriteTracks.size}", color = MusikColors.teal)
        ChipButton(onClick = { state.logout() }) { Text("Выйти") }
    }
}
