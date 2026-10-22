package app.musik.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.musik.android.data.Api
import app.musik.android.data.ApiError
import app.musik.android.data.ArtistPhotos
import app.musik.android.data.FavoritesResponse
import app.musik.android.data.Settings
import app.musik.android.data.Track
import app.musik.android.data.albumKey
import app.musik.android.data.jsonBody
import app.musik.android.data.nonEmpty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App-wide state: login gate, toasts and favorite sets shared by every screen. */
class AppState(
    val settings: Settings,
    val api: Api,
    val photos: ArtistPhotos,
) {
    enum class Phase { Checking, Login, Ready }
    enum class LoginMode { Account, Token }

    val scope = MainScope()

    var phase by mutableStateOf(Phase.Checking)
    var toast by mutableStateOf<String?>(null)
        private set
    var favoriteTracks by mutableStateOf<Set<Int>>(emptySet())
    var favoriteArtists by mutableStateOf<Set<String>>(emptySet())
    var favoriteAlbums by mutableStateOf<Set<String>>(emptySet())
    /** Bumped when shelves should reload (favorites or mixes changed). */
    var homeRevision by mutableIntStateOf(0)
    /** Started without a connection to the server: only saved tracks are available. */
    var offlineLaunch by mutableStateOf(false)

    /** Hooks for the player and the offline store, set by [MusikApp]. */
    var onSignedIn: suspend () -> Unit = {}
    var onSignedOut: (clearSession: Boolean) -> Unit = {}
    var onFavoritesLoaded: (List<Track>) -> Unit = {}
    var onFavoriteChanged: (Track, Boolean) -> Unit = { _, _ -> }

    private var toastJob: Job? = null

    init {
        api.onUnauthorized = { handleUnauthorized() }
    }

    // MARK: Boot & auth

    fun bootstrap() {
        scope.launch {
            if (settings.baseUrl.isEmpty()) {
                phase = Phase.Login
                return@launch
            }
            try {
                val me = api.authMe()
                if (me.ok || me.authEnabled == false) {
                    phase = Phase.Ready
                    afterSignIn()
                } else {
                    phase = Phase.Login
                }
            } catch (e: ApiError) {
                if (e.status == 0 && (settings.token != null || settings.cookies().isNotEmpty())) {
                    // No connection, but we were signed in before: open the app with what is
                    // saved on the phone instead of the login screen.
                    offlineLaunch = true
                    phase = Phase.Ready
                    show("Нет связи с сервером — играют сохранённые треки")
                } else {
                    phase = Phase.Login
                    show(e)
                }
            }
        }
    }

    private suspend fun afterSignIn() {
        refreshFavorites()
        scope.launch { photos.refresh(api, force = true) }
        onSignedIn()
    }

    /** Called when the connection comes back after an offline launch. */
    suspend fun reconnect() {
        if (!offlineLaunch) return
        val me = runCatching { api.authMe() }.getOrNull() ?: return
        if (!(me.ok || me.authEnabled == false)) return
        offlineLaunch = false
        homeRevision++
        refreshFavorites()
        photos.refresh(api, force = true)
    }

    suspend fun login(baseUrl: String, mode: LoginMode, username: String, secret: String) {
        settings.baseUrl = baseUrl
        val password = secret.trim()
        val user = username.trim().nonEmpty
        settings.token = null
        api.clearCookies()
        when (mode) {
            LoginMode.Token -> settings.token = password
            LoginMode.Account -> try {
                settings.token = api.issueToken(user, password, settings.deviceName)
            } catch (e: ApiError) {
                // Older server, or one without device tokens: sign in like the website does.
                if (e.status in listOf(404, 405, 409)) api.login(password, user) else throw e
            }
        }
        val me = try {
            api.authMe()
        } catch (e: ApiError) {
            if (e.status != 0) throw ApiError(e.status, e.code, "Вход не удался: ${e.message}") else throw e
        }
        if (!(me.ok || me.authEnabled == false)) {
            settings.token = null
            throw ApiError(401, "auth_required", if (mode == LoginMode.Token) "Сервер не принял токен" else "Неверное имя или пароль")
        }
        phase = Phase.Ready
        afterSignIn()
    }

    fun logout() {
        scope.launch {
            onSignedOut(true)
            api.logout()
            settings.token = null
            api.clearCookies()
            photos.clear()
            favoriteTracks = emptySet()
            favoriteArtists = emptySet()
            favoriteAlbums = emptySet()
            offlineLaunch = false
            phase = Phase.Login
        }
    }

    private fun handleUnauthorized() {
        if (phase != Phase.Ready) return
        onSignedOut(false)
        phase = Phase.Login
        show("Сессия истекла — войди снова")
    }

    // MARK: Favorites

    suspend fun refreshFavorites() {
        val fav = runCatching { api.favorites() }.getOrNull() ?: return
        applyFavorites(fav)
    }

    fun applyFavorites(fav: FavoritesResponse) {
        val tracks = (fav.tracks ?: emptyList()).map { it.asTrack() }
        onFavoritesLoaded(tracks)
        favoriteTracks = tracks.map { it.id }.toSet()
        favoriteArtists = (fav.artists ?: emptyList()).map { it.artist }.toSet()
        favoriteAlbums = (fav.albums ?: emptyList()).map { it.key }.toSet()
    }

    /**
     * Toggles a track heart; returns the new state, or null on error.
     * The heart flips at once and goes back if the server refuses.
     */
    suspend fun toggleFavoriteTrack(track: Track): Boolean? {
        val before = track.id in favoriteTracks
        favoriteTracks = if (before) favoriteTracks - track.id else favoriteTracks + track.id
        return try {
            val r = api.toggleFavorite(jsonBody("type" to "track", "track_id" to track.id))
            favoriteTracks = if (r.favorited) favoriteTracks + track.id else favoriteTracks - track.id
            onFavoriteChanged(track, r.favorited)
            show(if (r.favorited) "Любимая песня" else "Песня убрана из любимых")
            homeRevision++
            r.favorited
        } catch (e: Exception) {
            favoriteTracks = if (before) favoriteTracks + track.id else favoriteTracks - track.id
            show(e)
            null
        }
    }

    fun toggleFavoriteArtist(artist: String) = perform {
        val r = api.toggleFavorite(jsonBody("type" to "artist", "artist" to artist))
        favoriteArtists = if (r.favorited) favoriteArtists + artist else favoriteArtists - artist
        show(if (r.favorited) "Любимый артист" else "Артист убран")
        homeRevision++
    }

    fun toggleFavoriteAlbum(artist: String?, album: String) = perform {
        val r = api.toggleFavorite(jsonBody("type" to "album", "artist" to (artist ?: ""), "album" to album))
        val key = albumKey(artist, album)
        favoriteAlbums = if (r.favorited) favoriteAlbums + key else favoriteAlbums - key
        show(if (r.favorited) "Любимый альбом" else "Альбом убран")
        homeRevision++
    }

    fun addLater(trackId: Int) = perform {
        api.addLater(trackId)
        show("В «Потом»")
        homeRevision++
    }

    // MARK: Toasts

    fun show(message: String) {
        toastJob?.cancel()
        toast = message
        toastJob = scope.launch {
            delay(2600)
            toast = null
        }
    }

    fun show(error: Throwable) {
        if (error is CancellationException) return
        show(error.message ?: "Ошибка")
    }

    /** Runs an action and turns any error into a toast. */
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(e)
            }
        }
    }
}
