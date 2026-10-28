package app.drivegallery

import android.content.ContentValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.FileNotFoundException
import java.io.IOException

/** One full cycle: refresh from Drive if due, scan the phone, upload what is new, free space if asked to. */
class SyncEngine(private val app: App) {

    val running = MutableStateFlow(false)
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    private val mutex = Mutex()

    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }) {
        if (!mutex.tryLock()) return
        running.value = true
        try {
            if (app.prefs.remoteSyncDue()) app.remote.run()

            for (pass in 0 until 3) {
                app.scanner.scan()
                val pending = app.db.pending()
                if (pending.isEmpty()) break
                total.value = pending.size
                done.value = 0
                val uploaded = uploadAll(pending, onProgress)
                if (uploaded == 0) break // nothing succeeded, do not hammer the same failures
            }

            if (app.prefs.deleteMode == DeleteMode.AUTO) {
                val out = app.deleter.deleteUploaded()
                if (out.needConsent.isNotEmpty()) Notifier.consentNeeded(app, out.needConsent.size)
            }
        } finally {
            running.value = false
            total.value = 0
            done.value = 0
            mutex.unlock()
        }
    }

    private suspend fun uploadAll(list: List<Item>, onProgress: suspend (Int, Int) -> Unit): Int {
        val auto = app.prefs.deleteMode == DeleteMode.AUTO
        val needConsent = ArrayList<Item>()
        var uploadedCount = 0
        onProgress(0, list.size)
        for (chunk in list.chunked(2)) {
            val uploaded = coroutineScope {
                chunk.map { item -> async { uploadOne(item) } }.awaitAll().filterNotNull()
            }
            uploadedCount += uploaded.size
            done.value += chunk.size
            onProgress(done.value, total.value)
            if (auto && uploaded.isNotEmpty()) {
                needConsent += app.deleter.deleteUploaded(uploaded).needConsent
            }
        }
        if (needConsent.isNotEmpty()) Notifier.consentNeeded(app, needConsent.size)
        return uploadedCount
    }

    private suspend fun uploadOne(item: Item): Item? = try {
        app.uploader.upload(item) { }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthNeeded) {
        throw e
    } catch (e: IOException) {
        if (isTransient(e)) throw e
        recordFailure(item, e)
        null
    } catch (e: Exception) {
        recordFailure(item, e)
        null
    }

    /** Problems that say nothing about this file: no network, server hiccup, Drive full. */
    fun isTransient(e: IOException): Boolean = when (e) {
        is FileProblem, is FileNotFoundException -> false
        is DriveException ->
            e.code == 429 || e.code >= 500 ||
                (e.code == 403 && (e.message.orEmpty().contains("uota") || e.message.orEmpty().contains("ateLimit")))
        else -> true
    }

    fun isQuota(e: Throwable) = e is DriveException && e.message.orEmpty().contains("storageQuotaExceeded")

    private fun recordFailure(item: Item, e: Exception) {
        val n = item.failCount + 1
        app.db.update(item.key, ContentValues().apply {
            put("fail_count", n)
            put("last_error", (e.message ?: e.javaClass.simpleName).take(200))
            put("state", if (n >= 5) State.FAILED else State.PENDING)
        })
    }
}
