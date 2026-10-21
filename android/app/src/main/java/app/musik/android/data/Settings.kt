package app.musik.android.data

import android.content.Context
import android.os.Build
import app.musik.android.BuildConfig
import java.util.UUID

/** Persistent client settings in private SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("musik", Context.MODE_PRIVATE)

    companion object {
        /** Server address built into the app by CI (MUSIK_SERVER_URL); empty when none. */
        val builtInServer: String = normalize(BuildConfig.SERVER_URL)

        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.contains("://")) {
                // A domain is served over HTTPS; a LAN IP or an explicit port (…:8787) is plain HTTP.
                val host = s.substringBefore('/')
                val isIp = host.substringBefore(':').all { it.isDigit() || it == '.' }
                s = (if (isIp || host.contains(':')) "http://" else "https://") + s
            }
            return s
        }
    }

    var baseUrl: String
        get() = prefs.getString("baseUrl", null)?.takeIf { it.isNotBlank() } ?: builtInServer
        set(value) = prefs.edit().putString("baseUrl", normalize(value)).apply()

    var token: String?
        get() = prefs.getString("apiToken", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("apiToken", value).apply()

    /** Last playback session, used to resume after the app was killed. */
    var sessionId: String?
        get() = prefs.getString("sessionId", null)
        set(value) = prefs.edit().putString("sessionId", value).apply()

    /** Ask the server for its mobile AAC/MP3 profile instead of the original file. */
    var mobileStream: Boolean
        get() = prefs.getBoolean("mobileStream", true)
        set(value) = prefs.edit().putBoolean("mobileStream", value).apply()

    /** Liked tracks are saved to the phone automatically. */
    var autoSaveFavorites: Boolean
        get() = prefs.getBoolean("autoSaveFavorites", true)
        set(value) = prefs.edit().putBoolean("autoSaveFavorites", value).apply()

    /** Short vibration on like / unlike / dislike. */
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    /** Stable per-install id sent with playback events. */
    val clientId: String
        get() {
            prefs.getString("clientId", null)?.let { return it }
            val id = "android-" + UUID.randomUUID().toString()
            prefs.edit().putString("clientId", id).apply()
            return id
        }

    val deviceId: String = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"

    val deviceName: String = "musik Android · ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // Cookies of the password sign-in (servers without device tokens).
    fun cookies(): Set<String> = prefs.getStringSet("cookies", emptySet()) ?: emptySet()
    fun setCookies(value: Set<String>) = prefs.edit().putStringSet("cookies", value).apply()

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}
