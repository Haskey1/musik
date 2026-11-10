package app.musik.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val primary = "https://music.example.com"
    private val fallback = "https://cdn.example.com"

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("musik", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun legacyBuiltInPrimaryDoesNotBecomeCustom() {
        prefs().edit().putString("baseUrl", primary).commit()
        val settings = Settings(context, primary, fallback)
        assertFalse(settings.hasCustomServer)
        assertEquals(primary, settings.baseUrl)
        assertEquals(fallback, settings.fallbackAfterFailure(primary))
    }

    @Test
    fun legacyCustomStaysCustomWithoutFallback() {
        prefs().edit().putString("baseUrl", "https://private.example.com").commit()
        val settings = Settings(context, primary, fallback)
        assertTrue(settings.hasCustomServer)
        assertEquals("https://private.example.com", settings.baseUrl)
        assertNull(settings.fallbackAfterFailure(settings.baseUrl))
    }

    @Test
    fun explicitlyEnteredBuiltInAddressKeepsFallback() {
        val settings = Settings(context, primary, fallback)
        settings.baseUrl = primary
        assertFalse(settings.hasCustomServer)
        assertEquals(fallback, settings.fallbackAfterFailure(primary))
    }

    @Test
    fun parallelPrimaryFailuresCanBothRetryFallback() {
        val settings = Settings(context, primary, fallback)
        assertEquals(fallback, settings.fallbackAfterFailure(primary))
        assertEquals(fallback, settings.fallbackAfterFailure(primary))
        assertEquals(fallback, settings.baseUrl)
    }

    @Test
    fun fallbackFailureDoesNotSwitchBackAutomatically() {
        val settings = Settings(context, primary, fallback)
        settings.fallbackAfterFailure(primary)
        assertNull(settings.fallbackAfterFailure(fallback))
        assertEquals(fallback, settings.baseUrl)
    }

    private fun prefs() = context.getSharedPreferences("musik", Context.MODE_PRIVATE)
}
