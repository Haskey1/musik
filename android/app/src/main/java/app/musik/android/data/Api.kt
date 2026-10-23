package app.musik.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiError(val status: Int, val code: String?, message: String) : Exception(message) {
    val isUnauthorized get() = status == 401
    val isNotFound get() = status == 404
    val isNetwork get() = status == 0 && code == "network"
}

val JsonFormat = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

/** Builds a JSON object from Kotlin values; null values are left out. */
fun jsonBody(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.mapNotNull { (k, v) -> if (v == null) null else k to toJson(v) }.toMap())

fun jsonBody(map: Map<String, Any?>): JsonObject = jsonBody(*map.toList().toTypedArray())

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    else -> JsonPrimitive(v.toString())
}

/**
 * Thin JSON client for the Go player (`:8787`). Auth is a Bearer token when one is
 * stored, otherwise the `musik_session` cookie set by password login.
 */
class Api(private val settings: Settings) {
    /** Called on the main thread when the server answers 401 to an authed request. */
    var onUnauthorized: (() -> Unit)? = null

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val kept = loadAll().filter { old -> cookies.none { it.name == old.name && it.domain == old.domain } }
            settings.setCookies((kept + cookies).map { it.toString() }.toSet())
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            loadAll().filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }

        private fun loadAll(): List<Cookie> {
            val base = settings.baseUrl.toHttpUrlOrNull() ?: return emptyList()
            return settings.cookies().mapNotNull { Cookie.parse(base, it) }
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        val token = settings.token
        val base = settings.baseUrl.toHttpUrlOrNull()
        // Only our own server gets the token.
        if (token != null && base != null && req.url.host == base.host && req.header("Authorization") == null) {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        } else {
            chain.proceed(req)
        }
    }

    /** Shared by API calls, streaming, downloads and images. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun clearCookies() = settings.setCookies(emptySet())

    // MARK: URLs

    fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val base = settings.baseUrl
        val parsed = (base + path).toHttpUrlOrNull()
        if (base.isEmpty() || parsed == null) throw ApiError(0, "bad_url", "Неверный адрес сервера")
        if (query.isEmpty()) return parsed
        val b = parsed.newBuilder()
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    fun artworkUrl(trackId: Int, width: Int): String? =
        runCatching { url("/api/artwork/$trackId", listOf("w" to width.toString())).toString() }.getOrNull()

    fun streamUrl(track: Track): String? {
        val path = track.stream ?: "/api/stream/${track.id}"
        val query = if (settings.mobileStream) listOf("q" to "mobile") else emptyList()
        return runCatching { url(path, query).toString() }.getOrNull()
    }

    // MARK: Requests

    suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        redirectOn401: Boolean = true,
        timeoutSeconds: Long? = null,
    ): String {
        val builder = Request.Builder().url(url(path, query)).header("Accept", "application/json")
        if (method == "GET") {
            builder.get()
        } else {
            val text = (body ?: JsonObject(emptyMap())).toString()
            builder.method(method, text.toRequestBody("application/json".toMediaType()))
        }
        val client = if (timeoutSeconds != null) {
            http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        } else http
        val response = try {
            client.newCall(builder.build()).await()
        } catch (e: IOException) {
            throw ApiError(0, "network", describe(e))
        }
        return withContext(Dispatchers.IO) {
            response.use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val env = runCatching { JsonFormat.parseToJsonElement(text).jsonObject }.getOrNull()
                    val code = env?.get("code")?.jsonPrimitive?.contentOrNull
                    val msg = env?.get("error")?.jsonPrimitive?.contentOrNull
                    if (resp.code == 401 && redirectOn401) {
                        withContext(Dispatchers.Main) { onUnauthorized?.invoke() }
                    }
                    throw ApiError(resp.code, code, msg ?: "Ошибка сервера (HTTP ${resp.code})")
                }
                text
            }
        }
    }

    fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        JsonFormat.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw ApiError(0, "decode", "Неожиданный ответ сервера")
    }

    suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        decode(serializer<T>(), send("GET", path, query))

    suspend inline fun <reified T> post(path: String, body: JsonElement? = null): T =
        decode(serializer<T>(), send("POST", path, body = body))

    private fun describe(e: IOException): String = when (e) {
        is UnknownHostException -> "Не удаётся найти сервер"
        is ConnectException -> "Не удаётся подключиться к серверу"
        is SocketTimeoutException -> "Сервер не отвечает"
        else -> if (e.message?.contains("Canceled", true) == true) "Отменено" else "Нет сети"
    }

    // MARK: Auth & status

    suspend fun authMe(): AuthMe = decode(serializer(), send("GET", "/api/auth/me", redirectOn401 = false))

    /** Single-owner sign-in: one password, cookie session. */
    suspend fun login(password: String, username: String? = null) {
        send("POST", "/api/auth/login", body = jsonBody("username" to username, "password" to password), redirectOn401 = false)
    }

    /**
     * Phone sign-in: username + password give a personal device token. With no
     * username a single-user server returns its MUSIK_API_TOKEN for the password.
     */
    suspend fun issueToken(username: String?, password: String, deviceName: String): String {
        val text = send(
            "POST", "/api/auth/token",
            body = jsonBody("username" to username, "password" to password, "device_name" to deviceName),
            redirectOn401 = false,
        )
        return decode(IssuedToken.serializer(), text).token.nonEmpty
            ?: throw ApiError(0, "no_token", "Сервер не выдал токен")
    }

    /** Quick reachability check with a short timeout (the offline player polls it). */
    suspend fun ping(): Boolean {
        val target = runCatching { url("/api/health") }.getOrNull() ?: return false
        val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url(target).build()).await().use { it.code in 1..499 }
        } catch (e: IOException) {
            false
        }
    }

    suspend fun logout() {
        runCatching { send("POST", "/api/auth/logout", redirectOn401 = false) }
    }

    suspend fun profile(): Profile = get("/api/profile")
    suspend fun health(): Health = get("/api/health")

    // MARK: Catalog

    suspend fun library(artist: String? = null, album: String? = null, limit: Int? = null): List<Track> {
        val q = buildList {
            artist?.let { add("artist" to it) }
            album?.let { add("album" to it) }
            limit?.let { add("limit" to it.toString()) }
        }
        return get("/api/library", q)
    }

    suspend fun artists(limit: Int? = null): List<ArtistItem> =
        get<ArtistsResponse>("/api/artists", limit?.let { listOf("limit" to it.toString()) } ?: emptyList()).artists ?: emptyList()

    suspend fun albums(limit: Int? = null): List<AlbumItem> =
        get<AlbumsResponse>("/api/albums", limit?.let { listOf("limit" to it.toString()) } ?: emptyList()).albums ?: emptyList()

    suspend fun trackDetail(trackId: Int): TrackDetail = get("/api/tracks/$trackId")
    suspend fun lyrics(trackId: Int): Lyrics = get("/api/tracks/$trackId/lyrics")

    // MARK: Shelves

    suspend fun mixes(): List<Mix> = get<MixesResponse>("/api/mixes").mixes
    suspend fun favorites(): FavoritesResponse = get("/api/favorites")
    suspend fun recommendFavorites(): RecommendResponse = get("/api/recommend/favorites")

    suspend fun recommendSeed(trackId: Int, limit: Int = 20): List<Track> =
        get<RecommendResponse>(
            "/api/recommend/seed",
            listOf("type" to "track", "track_id" to trackId.toString(), "limit" to limit.toString()),
        ).tracks ?: emptyList()

    suspend fun similarArtists(artist: String): List<ArtistItem> =
        get<ArtistsResponse>("/api/similar/artists", listOf("artist" to artist)).artists ?: emptyList()

    suspend fun similarAlbums(artist: String?, album: String): List<AlbumItem> =
        get<AlbumsResponse>("/api/similar/albums", listOf("artist" to (artist ?: ""), "album" to album)).albums ?: emptyList()

    suspend fun discover(resurfaced: Boolean): List<DiscoverTip> =
        get<DiscoverResponse>(if (resurfaced) "/api/discover/resurfaced" else "/api/discover/albums").tips ?: emptyList()

    /** `GET /api/artist-photos` → normalized artist name → image path. */
    suspend fun artistPhotos(): Map<String, String> {
        val obj = JsonFormat.parseToJsonElement(send("GET", "/api/artist-photos")).jsonObject
        val photos = obj["photos"] as? JsonObject ?: return emptyMap()
        return photos.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    // MARK: Library actions

    suspend fun toggleFavorite(body: JsonObject): FavoriteToggleResult = post("/api/favorites/toggle", body)

    /** Radio rules («Скрыть», «Реже»): kept out of radio, mixes and recommendations. */
    suspend fun createRule(target: String, action: String, track: Track, sessionId: String?, strength: Double = 1.0): RuleCreated =
        post(
            "/api/rules",
            jsonBody(
                "target_type" to target, "action" to action, "scope" to "global", "preset" to "forever",
                "track_id" to track.id, "artist" to (track.artist ?: ""), "album" to (track.album ?: ""),
                "strength" to strength, "session_id" to sessionId,
            ),
        )

    suspend fun rules(): List<RadioRule> = get<RulesResponse>("/api/rules").rules

    suspend fun deleteRule(id: String) {
        send("DELETE", "/api/rules/$id")
    }

    suspend fun addLater(trackId: Int) {
        send("POST", "/api/later", body = jsonBody("track_id" to trackId))
    }

    // MARK: Jobs

    suspend fun enqueueJob(kind: String): Int? = post<JobRef>("/api/jobs/$kind").let { it.id ?: it.jobId }
    suspend fun job(id: Int): JobStatus = get("/api/jobs/$id")

    // MARK: Share radio

    suspend fun shares(): List<ShareLink> = get<SharesResponse>("/api/share/radio").shares ?: emptyList()
    suspend fun createShare(): ShareCreated = post("/api/share/radio", jsonBody("name" to "musik radio"))
    suspend fun revokeShare(token: String) {
        send("DELETE", "/api/share/radio/" + java.net.URLEncoder.encode(token, "UTF-8").replace("+", "%20"))
    }

    // MARK: Playback

    suspend fun radioStart(seed: Int? = null): PlayPayload = post("/api/radio/start", jsonBody("seed_track_id" to seed))
    suspend fun play(body: JsonObject): PlayPayload = post("/api/play", body)
    suspend fun playMix(kind: String, startTrackId: Int? = null): PlayPayload =
        post("/api/mixes/" + java.net.URLEncoder.encode(kind, "UTF-8").replace("+", "%20") + "/play",
            jsonBody("start_track_id" to startTrackId))
    suspend fun jump(sessionId: String, index: Int, trackId: Int): PlayPayload =
        post("/api/session/jump", jsonBody("session_id" to sessionId, "index" to index, "track_id" to trackId))
    suspend fun back(sessionId: String): PlayPayload = post("/api/session/back", jsonBody("session_id" to sessionId))
    suspend fun now(sessionId: String): PlayPayload = get("/api/now", listOf("session_id" to sessionId))

    fun newEventId(): String = UUID.randomUUID().toString()
}

/** Suspends until the call answers; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
