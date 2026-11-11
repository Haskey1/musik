package app.musik.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AmbientBitmapsTest {
    @Test
    fun hazeIsSoftAlreadyOnTheMinimumAndroidVersion() {
        val bitmap = AmbientBitmaps.createHaze()
        val levels = mutableSetOf<Int>()
        var largestStep = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            levels += alpha
            if (y > 0) largestStep = maxOf(largestStep, abs(alpha - Color.alpha(bitmap.getPixel(x, y - 1))))
            if (x > 0) largestStep = maxOf(largestStep, abs(alpha - Color.alpha(bitmap.getPixel(x - 1, y))))
        }
        assertTrue("Texture needs gradual alpha transitions", levels.size > 40)
        assertTrue("No hard oval boundaries", largestStep < 20)
    }

    @Test
    fun artworkBlurDoesNotDependOnRenderEffect() {
        val source = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) for (x in 0 until 32) source.setPixel(x, y, if (x < 16) Color.BLACK else Color.WHITE)
        val blurred = AmbientBitmaps.blur(source, radius = 6)
        assertTrue(Color.red(blurred.getPixel(15, 8)) in 30..150)
        assertTrue(Color.red(blurred.getPixel(16, 8)) in 100..220)
        assertTrue(Color.red(blurred.getPixel(0, 8)) < 10)
        assertTrue(Color.red(blurred.getPixel(31, 8)) > 240)
    }
}
