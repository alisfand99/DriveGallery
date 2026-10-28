package app.drivegallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    lateinit var prefs: Prefs
    lateinit var db: Db
    lateinit var http: OkHttpClient
    lateinit var api: DriveApi
    lateinit var scanner: MediaScanner
    lateinit var uploader: Uploader
    lateinit var deleter: Deleter
    lateinit var remote: RemoteSync
    lateinit var engine: SyncEngine

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Everything the gallery shows, newest first. Reloaded (debounced) whenever the index changes. */
    lateinit var items: StateFlow<List<Item>>

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = Db(this)
        http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
        api = DriveApi(http) { Auth.cachedToken ?: Auth.silentToken(this) }
        scanner = MediaScanner(this, db, prefs)
        uploader = Uploader(this)
        deleter = Deleter(this)
        remote = RemoteSync(this)
        engine = SyncEngine(this)

        items = db.tick
            .debounce(500)
            .map { db.visible() }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

        Notifier.createChannels(this)
        if (prefs.authorized) Scheduler.ensureScheduled(this)
    }

    /** Coil: adds the Drive access token to requests for Drive thumbnails and file content. */
    override fun newImageLoader(): ImageLoader {
        val client = http.newBuilder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val host = req.url.host
            val token = Auth.cachedToken
            if (token != null && req.header("Authorization") == null &&
                (host == "www.googleapis.com" || host.endsWith("googleusercontent.com"))
            ) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
            } else {
                chain.proceed(req)
            }
        }).build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .components { add(VideoFrameDecoder.Factory()) }
            .diskCache {
                DiskCache.Builder().directory(cacheDir.resolve("coil")).maxSizeBytes(512L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
    }
}
