package app.musik.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoodContextsTest {
    @Test
    fun reusesNamedContextAndPassesPreset() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"contexts":[{"context_id":"mood-energy","kind":"mood","name":"Бодрое"}]}"""))
            server.enqueue(MockResponse().setBody("{}"))
            api(server).radioStartMood("Бодрое", 42)
            assertEquals("/api/contexts", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/api/radio/start", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("energy", body["mood"]?.jsonPrimitive?.content)
            assertEquals("mood-energy", body["context_ids"]?.jsonArray?.single()?.jsonPrimitive?.content)
            assertEquals(42, body["seed_track_id"]?.jsonPrimitive?.int)
        }
    }

    @Test
    fun createsMissingContextBeforeStartingRadio() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"contexts":[]}"""))
            server.enqueue(MockResponse().setBody("""{"context_id":"mood-calm","kind":"mood","name":"Спокойное"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            api(server).radioStartMood("Спокойное")
            server.takeRequest()
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/contexts", create.path)
            val start = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("calm", start["mood"]?.jsonPrimitive?.content)
            assertEquals("mood-calm", start["context_ids"]?.jsonArray?.single()?.jsonPrimitive?.content)
        }
    }

    private fun api(server: MockWebServer): Api {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("musik", Context.MODE_PRIVATE).edit().clear().commit()
        return Api(Settings(context, server.url("/").toString(), ""))
    }
}
