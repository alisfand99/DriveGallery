package app.drivegallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest

/** Stable identity of a media file, the same on the phone and on Drive. */
fun makeKey(relPath: String, name: String, size: Long, taken: Long): String {
    val d = MessageDigest.getInstance("SHA-1").digest("$relPath/$name|$size|$taken".toByteArray())
    return d.joinToString("") { "%02x".format(it) }
}

/** Reads the phone's photo and video library and keeps the local index in sync with it. */
class MediaScanner(
    private val ctx: Context,
    private val db: Db,
    private val prefs: Prefs,
) {
    /**
     * Full photo access AND access to location data. Without the second one Android hands out
     * copies with the GPS tags blanked, and a backup made from those would not be an exact copy.
     */
    fun hasFullAccess() =
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun scan() {
        // Without full access the query returns only a subset, and we must not mistake that for deletions.
        if (!hasFullAccess()) return

        val existing = db.query(order = null).associateBy { it.key }
        val seen = HashSet<String>()
        val changes = ArrayList<Item>()

        scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, existing, seen, changes)
        if (prefs.includeVideos) {
            scanCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, existing, seen, changes)
        }

        val gone = ArrayList<String>()
        for (it in existing.values) {
            if (!it.localPresent || it.key in seen) continue
            if (it.isVideo && !prefs.includeVideos) continue
            if (it.state == State.UPLOADED) {
                changes.add(it.copy(localPresent = false, localUri = null, localId = null))
            } else {
                gone.add(it.key) // never uploaded, failed, or hidden: nothing left to keep
            }
        }
        db.upsertAll(changes)
        db.delete(gone)
    }

    private fun scanCollection(
        collection: Uri,
        existing: Map<String, Item>,
        seen: MutableSet<String>,
        out: MutableList<Item>,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        ctx.contentResolver.query(collection, projection, null, null, order)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val rel = (c.getString(2) ?: "Other").trim('/').ifEmpty { "Other" }
                val mime = c.getString(3) ?: continue
                val size = c.getLong(4)
                if (size <= 0) continue
                var taken = c.getLong(5)
                if (taken <= 0) taken = c.getLong(6) * 1000

                val key = makeKey(rel, name, size, taken)
                seen.add(key)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val ex = existing[key]
                if (ex == null) {
                    out.add(Item(key, name, mime, size, taken, rel, localId = id, localUri = uri, localPresent = true))
                } else if (!ex.localPresent || ex.localId != id) {
                    out.add(ex.copy(localId = id, localUri = uri, localPresent = true))
                }
            }
        }
    }
}
