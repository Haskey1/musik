package app.musik.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import app.musik.android.playback.PlaybackService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = application as MusikApp
        // a link that started the app (not a re-created activity after rotation)
        if (savedInstanceState == null) DeepLinks.accept(intent)
        setContent {
            MusikTheme {
                Root(app.state)
            }
        }
        // Android 13+: the playback notification needs this permission.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.accept(intent)
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller starts the playback service, which owns the media session.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
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
