package app.musik.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** Software-rendered wisps: no RenderEffect/Android 12 dependency and no oval edges. */
internal object AmbientBitmaps {
    val haze: Bitmap by lazy { createHaze() }

    internal fun createHaze(width: Int = 192, height: Int = 320): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val u = x.toDouble() / width
            val v = y.toDouble() / height
            val warp = noise(u * 3.0, v * 4.0) - 0.5
            val cloud = fractal(u * 3.0 + warp, v * 5.0)
            var ribbons = 0.0
            for (i in 0..3) {
                val center = 0.12 + i * 0.25 + 0.07 * sin(u * 5.0 + i * 1.7) + warp * 0.12
                val distance = (v - center) / (0.055 + cloud * 0.06)
                ribbons += exp(-distance * distance) * (0.45 + 0.55 * cloud)
            }
            val alpha = ((cloud * 0.12 + ribbons * 0.34).coerceIn(0.0, 0.58) * 255).toInt()
            pixels[y * width + x] = Color.argb(alpha, 246, 233, 220)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun fractal(x: Double, y: Double): Double {
        var value = 0.0
        var weight = 0.5
        var scale = 1.0
        repeat(5) { value += noise(x * scale, y * scale) * weight; weight *= 0.5; scale *= 2 }
        return value / 0.96875
    }

    private fun noise(x: Double, y: Double): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        fun smooth(t: Double) = t * t * (3 - 2 * t)
        val fx = smooth(x - ix)
        val fy = smooth(y - iy)
        fun hash(a: Int, b: Int): Double {
            var n = a * 374761393 + b * 668265263 + 1376312589
            n = (n xor (n ushr 13)) * 1274126177
            return ((n xor (n ushr 16)) and 0x7fffffff).toDouble() / Int.MAX_VALUE
        }
        val a = hash(ix, iy) * (1 - fx) + hash(ix + 1, iy) * fx
        val b = hash(ix, iy + 1) * (1 - fx) + hash(ix + 1, iy + 1) * fx
        return a * (1 - fy) + b * fy
    }

    /** Small, separable Gaussian blur is applied to artwork before it is enlarged. */
    internal fun blur(source: Bitmap, radius: Int = 12): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val temp = IntArray(input.size)
        val output = IntArray(input.size)
        val sigma = radius / 2.0
        val weights = DoubleArray(radius * 2 + 1) { exp(-((it - radius).toDouble() * (it - radius)) / (2 * sigma * sigma)) }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
            for (y in 0 until height) for (x in 0 until width) {
                var red = 0.0; var green = 0.0; var blue = 0.0
                for (k in -radius..radius) {
                    val px = if (horizontal) (x + k).coerceIn(0, width - 1) else x
                    val py = if (horizontal) y else (y + k).coerceIn(0, height - 1)
                    val pixel = from[py * width + px]
                    val w = weights[k + radius]
                    red += Color.red(pixel) * w; green += Color.green(pixel) * w; blue += Color.blue(pixel) * w
                }
                to[y * width + x] = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
            }
        }
        pass(input, temp, true)
        pass(temp, output, false)
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }
}
