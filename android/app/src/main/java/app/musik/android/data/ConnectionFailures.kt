package app.musik.android.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Server reachability is not evidence that the phone has no internet connection. */
internal fun connectionFailureMessage(error: IOException, host: String): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
    val detail = when {
        causes.any { it is CertificateException || it is SSLPeerUnverifiedException } ->
            "Не удалось проверить сертификат сервера. Проверь дату на телефоне и сертификат сервера."
        causes.any { it is SSLHandshakeException || it is SSLException } ->
            "Не удалось установить защищённое соединение с сервером."
        causes.any { it is UnknownHostException } ->
            "Не удалось найти адрес сервера. Проверь адрес и DNS."
        causes.any { it is SocketTimeoutException } -> "Сервер не ответил вовремя."
        causes.any { it is ConnectException } -> "Сервер не принимает соединение."
        causes.any { it is UnknownServiceException && it.message?.contains("CLEARTEXT", ignoreCase = true) == true } ->
            "Android запретил HTTP-соединение с сервером."
        causes.any { it is SocketException } -> "Соединение с сервером прервано."
        error.message?.contains("Canceled", ignoreCase = true) == true -> "Запрос к серверу отменён."
        else -> "Ошибка соединения с сервером: ${error.javaClass.simpleName}."
    }
    return "$host: $detail"
}
