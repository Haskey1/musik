package app.musik.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.musik.android.data.Api
import app.musik.android.data.Settings
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryModelTest {
    @Test
    fun searchAndLibraryShareOneDownloadAndCacheIt() = runBlocking {
        MockWebServer().use { server ->
            configure(server)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = model(server, scope)
                coroutineScope { awaitAll(async { model.load() }, async { model.load() }) }
                assertTrue(model.loaded)
                assertEquals(1, model.tracks.size)
                assertEquals(1, model.artists.size)
                assertEquals(3, server.requestCount)
                model.load()
                assertEquals(3, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    @Test
    fun leavingScreenDoesNotCancelSharedCatalogRequest() = runBlocking {
        MockWebServer().use { server ->
            configure(server, 100)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = model(server, scope)
                val screen = launch { model.load() }
                yield()
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                screen.cancelAndJoin()
                model.load()
                assertTrue(model.loaded)
                assertEquals(3, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    private fun model(server: MockWebServer, scope: CoroutineScope): LibraryModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("musik", Context.MODE_PRIVATE).edit().clear().commit()
        val api = Api(Settings(context, server.url("/").toString(), ""))
        return LibraryModel({ api }, scope)
    }

    private fun configure(server: MockWebServer, delay: Long = 0) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(when (request.path) {
                "/api/library" -> """[{"id":1,"title":"Song","artist":"Artist"}]"""
                "/api/albums" -> """{"albums":[]}"""
                "/api/favorites" -> "{}"
                else -> error("Unexpected request: ${request.path}")
            }).setBodyDelay(delay, TimeUnit.MILLISECONDS)
        }
        server.start()
    }
}
