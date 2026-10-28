package app.drivegallery

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the cloud library as a storage location ("Cloud Gallery") in the system file picker.
 * Any app that lets you attach a file (WhatsApp documents, Telegram files, email, ...) can browse
 * it and receive photos or videos from it. Document ids: "dir:<folder path>" and "file:<item key>".
 */
class GalleryDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "app.drivegallery.documents"
        private const val ROOT_ID = "gallery"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )
        private const val MAX_CACHE_BYTES = 1024L * 1024 * 1024

        fun fileDocId(key: String) = "file:$key"
    }

    private val app get() = context!!.applicationContext as App

    override fun onCreate() = true

    private fun cols(p: Array<out String>?, d: Array<String>): Array<String> = p?.let { arrayOf(*it) } ?: d

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(cols(projection, ROOT_COLUMNS))
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_TITLE, "Cloud Gallery")
            .add(Root.COLUMN_SUMMARY, "Photos and videos from Google Drive")
            .add(Root.COLUMN_DOCUMENT_ID, "dir:")
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
        return c
    }

    private fun addDir(c: MatrixCursor, path: String) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, "dir:$path")
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_DISPLAY_NAME, if (path.isEmpty()) "Cloud Gallery" else path.substringAfterLast('/'))
            .add(Document.COLUMN_LAST_MODIFIED, null)
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, null)
    }

    private fun addFile(c: MatrixCursor, item: Item) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, fileDocId(item.key))
            .add(Document.COLUMN_MIME_TYPE, item.mime)
            .add(Document.COLUMN_DISPLAY_NAME, item.name)
            .add(Document.COLUMN_LAST_MODIFIED, item.dateTaken)
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
            .add(Document.COLUMN_SIZE, item.size)
    }

    private fun itemFor(documentId: String): Item =
        app.db.get(documentId.removePrefix("file:")) ?: throw FileNotFoundException(documentId)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(cols(projection, DOC_COLUMNS))
        if (documentId.startsWith("dir:")) addDir(c, documentId.removePrefix("dir:"))
        else addFile(c, itemFor(documentId))
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        val c = MatrixCursor(cols(projection, DOC_COLUMNS))
        val parent = parentDocumentId.removePrefix("dir:")
        val prefix = if (parent.isEmpty()) "" else "$parent/"

        app.db.paths()
            .filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { prefix + it.removePrefix(prefix).substringBefore('/') }
            .toSortedSet()
            .forEach { addDir(c, it) }

        app.db.query(
            "state<>${State.HIDDEN} AND path=? AND (local_present=1 OR drive_id IS NOT NULL)", arrayOf(parent)
        ).forEach { addFile(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parentDocumentId.removePrefix("dir:")
        val path = if (documentId.startsWith("dir:")) documentId.removePrefix("dir:")
        else runCatching { itemFor(documentId).relPath }.getOrNull() ?: return false
        return parent.isEmpty() || path == parent || path.startsWith("$parent/")
    }

    override fun openDocumentThumbnail(
        documentId: String, sizeHint: Point?, signal: CancellationSignal?
    ): AssetFileDescriptor {
        val path = itemFor(documentId).thumbPath
        if (path == null || !File(path).exists()) throw FileNotFoundException("no thumbnail")
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY),
            0, AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("The cloud gallery is read only")
        val item = itemFor(documentId)

        // Still on the phone: hand out the original, no download needed.
        if (item.localPresent && item.localUri != null) {
            runCatching {
                app.contentResolver.openFileDescriptor(Uri.parse(item.localUri), "r")?.let { return it }
            }
        }

        val driveId = item.driveId ?: throw FileNotFoundException("not uploaded yet")
        val dir = File(app.cacheDir, "dl").apply { mkdirs() }
        val target = File(dir, driveId)
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(dir, "$driveId.part")
            runBlocking { app.api.download(driveId, tmp) }
            if (!tmp.renameTo(target)) throw FileNotFoundException("download failed")
            trimCache(dir)
        }
        target.setLastModified(System.currentTimeMillis())
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
