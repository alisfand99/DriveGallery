package app.drivegallery

import android.app.PendingIntent
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class DeleteOutcome(
    val deleted: Int,
    /** Files Android will only let the user delete, through a system confirmation dialog. */
    val needConsent: List<Item>,
    /** Files left alone because their checksum could not be matched with the Drive copy. */
    val unverified: Int,
)

/**
 * Removes phone copies, and only when the Drive copy is confirmed identical (MD5 match).
 * With the "Media management" special access Android lets this run silently; without it
 * the files are handed back in [DeleteOutcome.needConsent] for one confirmation dialog.
 */
class Deleter(private val app: App) {
    private val resolver get() = app.contentResolver

    /** [items] null means "every uploaded file that is still on the phone". */
    suspend fun deleteUploaded(items: List<Item>? = null): DeleteOutcome =
        withContext(Dispatchers.IO) {
            var deleted = 0
            var unverified = 0
            val consent = ArrayList<Item>()
            for (item in items ?: app.db.uploadedOnPhone()) {
                if (item.state != State.UPLOADED || !item.localPresent) continue
                val uri = item.localUri?.let { Uri.parse(it) } ?: continue
                if (!item.verified && !verify(item, uri)) {
                    unverified++
                    continue
                }
                try {
                    resolver.delete(uri, null, null)
                    markGone(item)
                    deleted++
                } catch (e: SecurityException) {
                    consent.add(item)
                }
            }
            DeleteOutcome(deleted, consent, unverified)
        }

    /** One system dialog for a batch of files (max 500 per dialog to keep it responsive). */
    fun consentRequests(items: List<Item>): List<PendingIntent> =
        items.mapNotNull { it.localUri?.let { u -> Uri.parse(u) } }
            .chunked(500)
            .map { MediaStore.createDeleteRequest(resolver, it) }

    private fun verify(item: Item, uri: Uri): Boolean {
        val expected = item.md5 ?: return false
        val md = MessageDigest.getInstance("MD5")
        val readOk = runCatching {
            resolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { s ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            } != null
        }.getOrDefault(false)
        if (!readOk) return false
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) return false
        app.db.update(item.key, ContentValues().apply { put("verified", 1) })
        return true
    }

    private fun markGone(item: Item) {
        app.db.update(item.key, ContentValues().apply {
            put("local_present", 0)
            putNull("local_uri")
            putNull("local_id")
        })
    }
}
