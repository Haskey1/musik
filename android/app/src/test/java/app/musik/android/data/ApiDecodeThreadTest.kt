package app.musik.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiDecodeThreadTest {
    @Test
    fun parsingIsMovedOffTheCallingUiThread() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = Api(Settings(context, "https://example.com", ""))
        val caller = Thread.currentThread().id
        var parserThread = caller
        val serializer = object : KSerializer<String> {
            override val descriptor = PrimitiveSerialDescriptor("RecordedString", PrimitiveKind.STRING)
            override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
            override fun deserialize(decoder: Decoder): String {
                parserThread = Thread.currentThread().id
                return decoder.decodeString()
            }
        }
        assertEquals("catalog", api.decode(serializer, "\"catalog\""))
        assertNotEquals(caller, parserThread)
    }
}
