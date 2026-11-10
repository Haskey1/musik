package app.musik.android.data

import android.content.Context
import android.os.Build
import app.musik.android.BuildConfig
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Persistent client settings in private SharedPreferences. */
class Settings(
    context: Context,
    primaryServer: String = builtInServer,
    fallbackServer: String = builtInFallback,
) {
    private val prefs = context.getSharedPreferences("musik", Context.MODE_PRIVATE)
    private val primary = normalize(primaryServer)
    private val fallback = normalize(fallbackServer)
    private val builtInServers = setOf(primary, fallback).filter { it.isNotEmpty() }.toSet()

    companion object {
        /** Server addresses built into the app by CI; empty when not configured. */
        val builtInServer: String = normalize(BuildConfig.SERVER_URL)
        val builtInFallback: String = normalize(BuildConfig.FALLBACK_URL)

        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.contains("://")) {
                val host = s.substringBefore('/')
                val isIp = host.substringBefore(':').all { it.isDigit() || it == '.' }
                s = (if (isIp || host.contains(':')) "http://" else "https://") + s
            }
            return s
        }
    }

    init {
        // Older releases stored the built-in primary in baseUrl. It must not turn into
        // a custom server during migration: custom servers deliberately have no CDN fallback.
        val legacy = prefs.getString("baseUrl", null)?.let(::normalize).orEmpty()
        if (legacy.isNotEmpty()) {
            val editor = prefs.edit().remove("baseUrl")
            if (legacy in builtInServers) {
                if (customServer == null) editor.putString("activeBuiltInUrl", legacy)
            } else if (prefs.getString("customBaseUrl", null) == null) {
                editor.putString("customBaseUrl", legacy)
            }
            editor.apply()
        }
    }

    private val customServer: String?
        get() = prefs.getString("customBaseUrl", null)?.let(::normalize)?.takeIf { it.isNotEmpty() }

    val hasCustomServer: Boolean
        get() = customServer != null

    private val activeBuiltInUrl: String
        get() = prefs.getString("activeBuiltInUrl", null)?.let(::normalize)
            ?.takeIf { it in builtInServers } ?: primary

    var baseUrl: String
        get() = customServer ?: activeBuiltInUrl
        set(value) {
            val url = normalize(value)
            val editor = prefs.edit().remove("baseUrl")
            if (url.isEmpty() || url in builtInServers) {
                editor.remove("customBaseUrl")
                if (url.isNotEmpty()) editor.putString("activeBuiltInUrl", url)
            } else {
                editor.putString("customBaseUrl", url)
            }
            editor.apply()
        }

    /** Switches only the managed pair. A failed fallback never loops back to primary. */
    @Synchronized
    fun fallbackAfterFailure(failedBase: String): String? {
        if (hasCustomServer || primary.isEmpty() || fallback.isEmpty() || primary == fallback) return null
        if (normalize(failedBase) != primary) return null
        if (baseUrl != fallback) prefs.edit().putString("activeBuiltInUrl", fallback).apply()
        return fallback
    }

    /** Bearer credentials may go to both managed hosts, never from custom server to a CDN. */
    fun isTrustedHost(host: String): Boolean {
        val urls = customServer?.let { listOf(it) } ?: builtInServers
        return urls.any { it.toHttpUrlOrNull()?.host == host }
    }

    var token: String?
        get() = prefs.getString("apiToken", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("apiToken", value).apply()

    var sessionId: String?
        get() = prefs.getString("sessionId", null)
        set(value) = prefs.edit().putString("sessionId", value).apply()

    var mobileStream: Boolean
        get() = prefs.getBoolean("mobileStream", true)
        set(value) = prefs.edit().putBoolean("mobileStream", value).apply()

    var autoSaveFavorites: Boolean
        get() = prefs.getBoolean("autoSaveFavorites", true)
        set(value) = prefs.edit().putBoolean("autoSaveFavorites", value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    var crossfade: Boolean
        get() = prefs.getBoolean("crossfade", true)
        set(value) = prefs.edit().putBoolean("crossfade", value).apply()

    var crossfadeSeconds: Int
        get() = prefs.getInt("crossfadeSeconds", 6).coerceIn(1, 12)
        set(value) = prefs.edit().putInt("crossfadeSeconds", value.coerceIn(1, 12)).apply()

    var crossfadeSkipAlbums: Boolean
        get() = prefs.getBoolean("crossfadeSkipAlbums", true)
        set(value) = prefs.edit().putBoolean("crossfadeSkipAlbums", value).apply()

    var prefetchNext: Boolean
        get() = prefs.getBoolean("prefetchNext", true)
        set(value) = prefs.edit().putBoolean("prefetchNext", value).apply()

    val clientId: String
        get() {
            prefs.getString("clientId", null)?.let { return it }
            val id = "android-" + UUID.randomUUID().toString()
            prefs.edit().putString("clientId", id).apply()
            return id
        }

    val deviceId: String = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"
    val deviceName: String = "musik Android · ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun cookies(): Set<String> = prefs.getStringSet("cookies", emptySet()) ?: emptySet()
    fun setCookies(value: Set<String>) = prefs.edit().putStringSet("cookies", value).apply()
    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}
