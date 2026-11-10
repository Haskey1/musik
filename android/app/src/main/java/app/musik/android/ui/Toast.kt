package app.musik.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.musik.android.AppState

/** Short messages at the top of the screen, over everything. */
@Composable
fun ToastHost(state: AppState) {
    val message = state.toast
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Text(
                last,
                Modifier
                    .padding(horizontal = 24.dp)
                    .background(MusikColors.surfaceHigh.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}
