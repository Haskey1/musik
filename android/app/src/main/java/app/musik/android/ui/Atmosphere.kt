package app.musik.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Soft wisps shared by the home hero, the player and the app background. */
@Composable
fun HazeTexture(modifier: Modifier = Modifier) {
    Canvas(modifier.blur(28.dp)) {
        repeat(18) { i ->
            val x = ((i * 73) % 101) / 100f * size.width
            val y = ((i * 47) % 103) / 102f * size.height
            drawOval(Color.White.copy(alpha = 0.16f), Offset(x - size.width * 0.35f, y), Size(size.width * 0.8f, size.height * 0.13f))
        }
    }
}

/** Blurred artwork and its dominant colour supply the same ambient light as iOS. */
@Composable
fun CoverBackdrop(trackId: Int) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(trackId) { picked = CoverColor.forTrack(context, trackId) }
    val color by animateColorAsState(picked ?: MusikColors.surfaceHigh, tween(600), label = "ambient")
    BoxWithConstraints(Modifier.fillMaxSize().background(MusikColors.background)) {
        AsyncImage(model = App.offline.artworkFile(trackId) ?: App.api.artworkUrl(trackId, 256), contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.45f).blur(82.dp).alpha(0.85f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0.4f), Color.Transparent))))
        HazeTexture(Modifier.fillMaxSize().alpha(0.32f))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))
            .background(Brush.verticalGradient(listOf(Color.Transparent, MusikColors.background.copy(alpha = 0.6f)))))
    }
}

/** Artist portrait at the right of the hero, fading into the warm smoky backdrop. */
@Composable
fun RadioArtistBackdrop(artist: String?, fallbackTrackId: Int?, modifier: Modifier = Modifier) {
    val photo = App.state.photos.fullUrl(App.api, artist)
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFA94218), Color(0xFFD96B2B), Color(0xFF486653))))) {
        if (photo != null) {
            AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.76f).align(Alignment.BottomEnd)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.White)), blendMode = BlendMode.DstIn)
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White, Color.White, Color.Transparent)), blendMode = BlendMode.DstIn)
                    })
        } else if (fallbackTrackId != null) {
            AsyncImage(model = App.offline.artworkFile(fallbackTrackId) ?: App.api.artworkUrl(fallbackTrackId, 256), contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.35f), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.46f), Color.Black.copy(alpha = 0.22f), Color.Transparent))))
        HazeTexture(Modifier.fillMaxSize().alpha(0.65f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.42f)))))
    }
}
