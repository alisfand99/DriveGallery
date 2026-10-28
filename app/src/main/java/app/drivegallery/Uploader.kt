package app.drivegallery

import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A problem with this particular file (as opposed to the network), so retrying right away will not help. */
class FileProblem(message: String) : IOException(message)

/** Uploads one file to Drive, verifies it byte for byte, and records the result. */
class Uploader(private val app: App) {

    private suspend fun ensureRoot(): String {
        app.prefs.rootFolderId?.let { return it }
        val id = app.api.ensureFolder(app.prefs.rootName, "root")
        app.prefs.rootFolderId = id
        return id
    }

    /** Creates the same folder chain on Drive that the file has on the phone. */
    private suspend fun ensurePath(rootId: String, relPath: String): String {
        var parent = rootId
        for (seg in relPath.split('/').filter { it.isNotEmpty() }) {
            val cacheKey = "$parent/$seg"
            parent = app.prefs.folderId(cacheKey)
                ?: app.api.ensureFolder(seg, parent).also { app.prefs.putFolderId(cacheKey, it) }
        }
        return parent
    }

    suspend fun upload(item: Item, onProgress: (Long) -> Unit): Item {
        val uri = Uri.parse(item.localUri ?: throw FileProblem("The file is no longer on the phone"))
        val resolver = app.contentResolver
        try {
            val rootId = ensureRoot()
            val parent = ensurePath(rootId, item.relPath)
            val digest = MessageDigest.getInstance("MD5")

            val res = app.api.upload(
                name = item.name,
                mime = item.mime,
                parentId = parent,
                appProps = mapOf("k" to item.key, "t" to item.dateTaken.toString()),
                modifiedMs = item.dateTaken,
                size = item.size,
                digest = digest,
                // setRequireOriginal: the untouched file, GPS tags included, so the copy is exact
                open = {
                    resolver.openInputStream(MediaStore.setRequireOriginal(uri))
                        ?: throw FileProblem("Cannot open ${item.name}")
                },
                onProgress = onProgress,
            )

            val localMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (res.md5 == null || !res.md5.equals(localMd5, ignoreCase = true)) {
                runCatching { app.api.trash(res.id) }
                throw FileProblem("Checksum mismatch for ${item.name}")
            }

            val done = item.copy(
                size = res.size.takeIf { it > 0 } ?: item.size,
                driveId = res.id,
                md5 = res.md5,
                state = State.UPLOADED,
                verified = true,
                thumbPath = makeThumb(item, uri),
                thumbUrl = res.thumbnailLink,
                failCount = 0,
                lastError = null,
            )
            app.db.upsert(done)
            return done
        } catch (e: DriveException) {
            if (e.code == 404) { // a cached folder was deleted on Drive
                app.prefs.clearFolderIds()
                app.prefs.rootFolderId = null
            }
            throw e
        }
    }

    /** A small local preview so the grid stays fast and works offline, even after the original is gone. */
    private fun makeThumb(item: Item, uri: Uri): String? = runCatching {
        val bmp = app.contentResolver.loadThumbnail(uri, Size(480, 480), null)
        val f = File(app.filesDir, "thumbs/${item.key}.jpg")
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        f.absolutePath
    }.getOrNull()
}
