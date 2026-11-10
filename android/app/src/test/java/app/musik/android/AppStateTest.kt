package app.musik.android

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.musik.android.data.Api
import app.musik.android.data.ArtistPhotos
import app.musik.android.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("musik", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun savedTracksOpenOfflineEvenWithoutServerOrCredentials() {
        val settings = Settings(context, "", "")
        val state = AppState(settings, Api(settings), ArtistPhotos(settings))
        state.hasSavedTracks = { true }
        state.offlineFavoriteIds = { setOf(7, 9) }

        state.bootstrap()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AppState.Phase.Ready, state.phase)
        assertTrue(state.offlineLaunch)
        assertEquals(setOf(7, 9), state.favoriteTracks)
    }

    @Test
    fun noSavedTracksStillOpensLogin() {
        val settings = Settings(context, "", "")
        val state = AppState(settings, Api(settings), ArtistPhotos(settings))

        state.bootstrap()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AppState.Phase.Login, state.phase)
    }
}
