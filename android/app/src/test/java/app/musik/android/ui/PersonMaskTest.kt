package app.musik.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PersonMaskTest {
    @Test
    fun removesBackgroundAndKeepsThePersonColors() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val mask = FloatArray(64) { i -> if (i % 8 in 2..5 && i / 8 in 2..5) 1f else 0f }
        val result = requireNotNull(PersonMask.cutout(source, mask, 8, 8))
        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        assertEquals(Color.RED, result.getPixel(3, 3))
    }

    @Test
    fun resizesMaskWithSoftEdgesInsteadOfAVisibleRectangle() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = requireNotNull(PersonMask.cutout(source, floatArrayOf(1f, 0f, 0f, 0f), 2, 2))
        assertEquals(255, Color.alpha(result.getPixel(0, 0)))
        assertEquals(0, Color.alpha(result.getPixel(3, 3)))
        assertTrue(Color.alpha(result.getPixel(1, 1)) in 1..254)
    }

    @Test
    fun doesNotShowWholePhotoWhenMaskIsEmptyOrUnusable() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertNull(PersonMask.cutout(source, FloatArray(16), 4, 4))
        assertNull(PersonMask.cutout(source, FloatArray(16) { 1f }, 4, 4))
        assertNull(PersonMask.cutout(source, FloatArray(16) { Float.NaN }, 4, 4))
        assertNull(PersonMask.cutout(source, FloatArray(3), 4, 4))
    }

    @Test
    fun preservesExistingTransparencyAndMultiplePeople() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(128, 255, 0, 0)) }
        val mask = FloatArray(64) { i -> if (i % 8 in listOf(1, 2, 5, 6)) 1f else 0f }
        val result = requireNotNull(PersonMask.cutout(source, mask, 8, 8))
        assertEquals(128, Color.alpha(result.getPixel(1, 4)))
        assertEquals(128, Color.alpha(result.getPixel(6, 4)))
        assertEquals(0, Color.alpha(result.getPixel(4, 4)))
    }
}
