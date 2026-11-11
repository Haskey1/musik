package app.musik.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiFallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val servers = mutableListOf<MockWebServer>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("musik", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun stopServers() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    @Test
    fun primaryGatewayFailureRetriesFallback() = runBlocking {
        val primary = server()
        val fallback = server()
        primary.enqueue(MockResponse().setResponseCode(503))
        fallback.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val settings = Settings(context, primary.url("/").toString(), fallback.url("/").toString())
        settings.token = "secret"

        assertTrue(Api(settings).authMe().ok)
        assertEquals(1, primary.requestCount)
        assertEquals(1, fallback.requestCount)
        assertEquals("Bearer secret", fallback.takeRequest().getHeader("Authorization"))
        assertEquals(fallback.url("/").toString().trimEnd('/'), settings.baseUrl)
    }

    @Test
    fun primaryConnectionFailureRetriesFallback() = runBlocking {
        val primary = server()
        val fallback = server()
        val primaryUrl = primary.url("/").toString()
        primary.shutdown()
        fallback.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val settings = Settings(context, primaryUrl, fallback.url("/").toString())

        assertTrue(Api(settings).authMe().ok)
        assertEquals(1, fallback.requestCount)
    }

    @Test
    fun customServerFailureNeverUsesBuiltInFallback(): Unit = runBlocking {
        val custom = server()
        val fallback = server()
        custom.enqueue(MockResponse().setResponseCode(503))
        val settings = Settings(context, "https://primary.example.com", fallback.url("/").toString())
        settings.baseUrl = custom.url("/").toString()

        try {
            Api(settings).authMe()
            fail("Expected HTTP 503")
        } catch (e: ApiError) {
            assertEquals(503, e.status)
        }
        assertEquals(1, custom.requestCount)
        assertEquals(0, fallback.requestCount)
    }

    @Test
    fun unavailableFallbackIsNotPersistedAcrossAppRestarts() {
        val primary = "https://music.example.com"
        val fallback = "https://cdn.example.com"
        val settings = Settings(context, primary, fallback)
        settings.fallbackAfterFailure(primary)
        assertEquals(fallback, settings.baseUrl)
        assertEquals(primary, Settings(context, primary, fallback).baseUrl)
    }

    @Test
    fun explicitSignInCanRecoverFromPreviouslyFailedFallback() = runBlocking {
        val primary = server()
        val fallback = server()
        val settings = Settings(context, primary.url("/").toString(), fallback.url("/").toString())
        settings.fallbackAfterFailure(settings.baseUrl)
        settings.restartManagedConnection()
        primary.enqueue(MockResponse().setBody("""{"token":"device-token"}"""))
        val token = Api(settings).issueToken("admin", "secret", "test")
        assertEquals("device-token", token)
        assertEquals(1, primary.requestCount)
        assertEquals(0, fallback.requestCount)
    }

    @Test
    fun retryDoesNotChangeCustomServer() {
        val settings = Settings(context, "https://primary.example.com", "https://cdn.example.com")
        settings.baseUrl = "https://custom.example.com"
        settings.restartManagedConnection()
        assertEquals("https://custom.example.com", settings.baseUrl)
        assertTrue(settings.hasCustomServer)
    }

    private fun server(): MockWebServer = MockWebServer().also {
        it.start()
        servers += it
    }
}

