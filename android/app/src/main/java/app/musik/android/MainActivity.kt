package app.musik.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musik.android.ui.Backdrop
import app.musik.android.ui.MusikColors
import app.musik.android.ui.MusikTheme
import app.musik.android.ui.ToastHost
import app.musik.android.ui.displayStyle
import app.musik.android.ui.screens.LoginScreen
import app.musik.android.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = application as MusikApp
        setContent {
            MusikTheme {
                Root(app.state)
            }
        }
    }
}

@Composable
private fun Root(state: AppState) {
    LaunchedEffect(Unit) {
        if (state.phase == AppState.Phase.Checking) state.bootstrap()
    }
    Backdrop {
        when (state.phase) {
            AppState.Phase.Checking -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("musik", style = displayStyle(44))
                CircularProgressIndicator(color = MusikColors.accent)
            }
            AppState.Phase.Login -> LoginScreen(state)
            AppState.Phase.Ready -> MainScreen(state)
        }
        Box(Modifier.fillMaxSize()) { ToastHost(state) }
    }
}
