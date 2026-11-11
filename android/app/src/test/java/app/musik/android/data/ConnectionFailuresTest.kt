package app.musik.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class ConnectionFailuresTest {
    @Test
    fun certificateFailureIsNotReportedAsNoInternet() {
        val ssl = SSLHandshakeException("handshake").apply { initCause(CertificateException("expired")) }
        val message = connectionFailureMessage(ssl, "music.example.com")
        assertTrue(message.contains("сертификат"))
        assertTrue(message.contains("music.example.com"))
        assertFalse(message.contains("Нет сети"))
    }

    @Test
    fun explainsDnsAndSocketFailuresSeparately() {
        assertTrue(connectionFailureMessage(UnknownHostException("dns"), "server").contains("DNS"))
        assertTrue(connectionFailureMessage(SocketException("reset"), "server").contains("прервано"))
    }

    @Test
    fun unknownErrorKeepsDiagnosticTypeWithoutLeakingRawExceptionText() {
        val message = connectionFailureMessage(IOException("https://username:password@host/private"), "host")
        assertTrue(message.contains("IOException"))
        assertFalse(message.contains("password"))
    }
}
