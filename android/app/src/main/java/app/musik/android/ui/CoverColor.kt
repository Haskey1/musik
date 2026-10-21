package app.musik.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The colour of a cover, like Yandex Music's player background: the cover's main hue,
 * toned to a mid brightness so white text stays readable; grey for covers with no real colour.
 */
object CoverColor {
    private val cache = LruCache<String, Int>(300)

    suspend fun forTrack(context: Context, trackId: Int): Color? {
        val model: Any = App.offline.artworkFile(trackId) ?: App.api.artworkUrl(trackId, 96) ?: return null
        return forModel(context, "t$trackId", model)
    }

    suspend fun forModel(context: Context, key: String, model: Any): Color? {
        cache.get(key)?.let { return Color(it) }
        val request = ImageRequest.Builder(context).data(model).size(96).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        val color = withContext(Dispatchers.Default) { dominant(bitmap) } ?: return null
        cache.put(key, color.toArgb())
        return color
    }

    /** The most common hue among colourful pixels (12 hue bins, weighted by saturation), toned. */
    fun dominant(source: Bitmap): Color? {
        val side = 24
        val bmp = Bitmap.createScaledBitmap(source, side, side, true)
        val pixels = IntArray(side * side)
        bmp.getPixels(pixels, 0, side, 0, 0, side, side)
        val w = DoubleArray(12); val rs = DoubleArray(12); val gs = DoubleArray(12); val bs = DoubleArray(12)
        var colourful = 0.0
        var lightSum = 0.0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            lightSum += (mx + mn) / 2
            val sat = if (mx == 0.0) 0.0 else (mx - mn) / mx
            if (!(sat > 0.2 && mx > 0.15 && (mx < 0.98 || sat > 0.4))) continue
            val d = mx - mn
            var hue = when (mx) {
                r -> (g - b) / d
                g -> 2 + (b - r) / d
                else -> 4 + (r - g) / d
            }
            hue = (hue / 6) % 1.0
            if (hue < 0) hue += 1
            val bin = min((hue * 12).toInt(), 11)
            val weight = sat * (0.5 + mx / 2)
            w[bin] += weight; rs[bin] += r * weight; gs[bin] += g * weight; bs[bin] += b * weight
            colourful += 1
        }
        val count = pixels.size.toDouble()
        if (colourful / count < 0.08) {
            // black-and-white or nearly: a grey, lighter for light covers
            val v = (0.30 + 0.25 * lightSum / count).toFloat()
            return Color(v, v, v)
        }
        val top = w.indices.maxByOrNull { w[it] } ?: return null
        if (w[top] <= 0) return null
        val base = android.graphics.Color.rgb(
            (rs[top] / w[top] * 255).toInt(), (gs[top] / w[top] * 255).toInt(), (bs[top] / w[top] * 255).toInt(),
        )
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        // mid tones: bright enough to read as the cover's colour, dark enough for white text
        hsv[1] = hsv[1].coerceIn(0.35f, 0.7f)
        hsv[2] = (hsv[2] * 0.75f).coerceIn(0.38f, 0.58f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

fun Color.withBrightness(factor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
