package app.musik.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.core.graphics.drawable.toBitmap
import android.graphics.Bitmap
import android.util.LruCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Already soft pixels render consistently on every supported Android version. */
@Composable
fun HazeTexture(modifier: Modifier = Modifier) {
    val haze = remember { AmbientBitmaps.haze.asImageBitmap() }
    Image(haze, contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.High)
}

private val ambientCache = LruCache<String, ImageBitmap>(8)

@Composable
private fun AmbientArtwork(trackId: Int, modifier: Modifier) {
    val context = LocalContext.current
    val model = App.offline.artworkFile(trackId) ?: App.api.artworkUrl(trackId, 128)
    val image by produceState<ImageBitmap?>(null, model) {
        value = null
        val key = model?.toString() ?: return@produceState
        ambientCache.get(key)?.let { value = it; return@produceState }
        val request = ImageRequest.Builder(context).data(model).size(128).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@produceState
        val bitmap = withContext(Dispatchers.Default) {
            AmbientBitmaps.blur(result.drawable.toBitmap(128, 128, Bitmap.Config.ARGB_8888)).asImageBitmap()
        }
        ambientCache.put(key, bitmap)
        value = bitmap
    }
    image?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.High) }
}

/** Blurred artwork and its dominant colour supply the same ambient light as iOS. */
@Composable
fun CoverBackdrop(trackId: Int) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(trackId) { picked = CoverColor.forTrack(context, trackId) }
    val color by animateColorAsState(picked ?: MusikColors.surfaceHigh, tween(600), label = "ambient")
    BoxWithConstraints(Modifier.fillMaxSize().background(MusikColors.background)) {
        AmbientArtwork(trackId, Modifier.fillMaxSize().scale(1.45f).alpha(0.85f))
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
    val context = LocalContext.current.applicationContext
    val cutout by produceState<Bitmap?>(null, photo) {
        value = null
        value = photo?.let { ArtistCutouts.load(context, it) }
    }
    val portrait = remember(cutout) { cutout?.asImageBitmap() }
    val portraitAlpha by animateFloatAsState(if (portrait != null) 1f else 0f, tween(350), label = "person")
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFA94218), Color(0xFFD96B2B), Color(0xFF486653))))) {
        if (portrait != null) {
            Image(bitmap = portrait, contentDescription = null, contentScale = ContentScale.Fit,
                alignment = Alignment.BottomEnd,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.76f).align(Alignment.BottomEnd).alpha(portraitAlpha)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.22f to Color.White, 0.9f to Color.White, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.15f to Color.White, 0.88f to Color.White, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    })
        } else if (fallbackTrackId != null) {
            AmbientArtwork(fallbackTrackId, Modifier.fillMaxSize().alpha(0.35f))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.46f), Color.Black.copy(alpha = 0.22f), Color.Transparent))))
        HazeTexture(Modifier.fillMaxSize().alpha(0.65f)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White)), blendMode = BlendMode.DstIn)
            })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.42f)))))
    }
}


