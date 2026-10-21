package app.musik.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import app.musik.android.data.Api
import app.musik.android.data.ArtistPhotos
import app.musik.android.data.Settings

/** Holds the app-wide singletons: settings, API client, state, player and offline store. */
class MusikApp : Application(), ImageLoaderFactory {
    lateinit var settings: Settings
        private set
    lateinit var api: Api
        private set
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        api = Api(settings)
        state = AppState(settings, api, ArtistPhotos(settings))
    }

    /** Covers and artist photos need the same auth as the API: Coil shares its OkHttp client. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(api.http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()

    companion object {
        lateinit var instance: MusikApp
            private set
    }
}
