package app.musik.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/** Turns a person-confidence mask into real transparency, preserving soft hair/edge pixels. */
internal object PersonMask {
    fun cutout(source: Bitmap, confidence: FloatArray, maskWidth: Int, maskHeight: Int): Bitmap? {
        if (maskWidth <= 0 || maskHeight <= 0 || confidence.size != maskWidth * maskHeight) return null
        var strong = 0
        var foreground = 0
        for (value in confidence) {
            if (value.isFinite() && value >= 0.85f) strong++
            if (value.isFinite() && value >= 0.5f) foreground++
        }
        // Logos, empty masks and masks covering the entire photo stay ambient, as in iOS.
        val count = confidence.size.toFloat()
        if (strong / count < 0.025f || foreground / count < 0.04f || foreground / count > 0.985f) return null
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        fun sample(x: Int, y: Int): Float = confidence[y.coerceIn(0, maskHeight - 1) * maskWidth + x.coerceIn(0, maskWidth - 1)]
            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        for (y in 0 until height) for (x in 0 until width) {
            val mx = ((x + 0.5f) * maskWidth / width - 0.5f).coerceIn(0f, (maskWidth - 1).toFloat())
            val my = ((y + 0.5f) * maskHeight / height - 0.5f).coerceIn(0f, (maskHeight - 1).toFloat())
            val x0 = mx.toInt(); val y0 = my.toInt()
            val fx = mx - x0; val fy = my - y0
            val upper = sample(x0, y0) * (1 - fx) + sample(x0 + 1, y0) * fx
            val lower = sample(x0, y0 + 1) * (1 - fx) + sample(x0 + 1, y0 + 1) * fx
            val value = upper * (1 - fy) + lower * fy
            val t = ((value - 0.2f) / 0.65f).coerceIn(0f, 1f)
            val alpha = (Color.alpha(pixels[y * width + x]) * t * t * (3 - 2 * t)).roundToInt()
            pixels[y * width + x] = (pixels[y * width + x] and 0x00ffffff) or (alpha shl 24)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
