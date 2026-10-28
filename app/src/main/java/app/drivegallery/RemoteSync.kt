package app.drivegallery

import org.json.JSONObject
import java.time.Instant

/** Rebuilds the local index from what is actually on Drive (new phone, reinstall, edits made elsewhere). */
class RemoteSync(private val app: App) {

    private class RemoteFile(
        val id: String, val name: String, val mime: String, val size: Long,
        val md5: String?, val path: String, val key: String, val taken: Long, val thumb: String?,
    )

    suspend fun run() {
        val prefs = app.prefs
        val api = app.api

        var rootId = prefs.rootFolderId
        if (rootId != null && !api.exists(rootId)) {
            prefs.rootFolderId = null
            prefs.clearFolderIds()
            rootId = null
        }
        if (rootId == null) {
            rootId = api.findFolder(prefs.rootName, "root")
            if (rootId == null) {
                markGone(emptySet())
                prefs.lastRemoteSync = System.currentTimeMillis()
                return
            }
            prefs.rootFolderId = rootId
        }

        // 1) every folder, so each file's path can be rebuilt
        val folders = HashMap<String, Pair<String, String?>>()
        api.list("mimeType='application/vnd.google-apps.folder' and trashed=false", "id,name,parents") { page ->
            for (i in 0 until page.length()) {
                val o = page.getJSONObject(i)
                folders[o.getString("id")] = o.getString("name") to o.optJSONArray("parents")?.optString(0)
            }
        }
        val pathCache = HashMap<String, String?>()
        fun pathOf(start: String): String? {
            if (pathCache.containsKey(start)) return pathCache[start]
            val parts = ArrayList<String>()
            var cur: String? = start
            var guard = 0
            while (cur != null && cur != rootId && guard++ < 64) {
                val f = folders[cur] ?: break
                parts.add(f.first)
                cur = f.second
            }
            val result = if (cur == rootId) parts.reversed().joinToString("/") else null
            pathCache[start] = result
            return result
        }

        // 2) every media file
        val remote = ArrayList<RemoteFile>()
        api.list(
            "trashed=false and mimeType!='application/vnd.google-apps.folder'",
            "id,name,mimeType,size,md5Checksum,parents,appProperties,thumbnailLink,modifiedTime"
        ) { page ->
            for (i in 0 until page.length()) {
                val o = page.getJSONObject(i)
                val mime = o.optString("mimeType")
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                val parent = o.optJSONArray("parents")?.optString(0) ?: continue
                val path = pathOf(parent) ?: continue
                val name = o.getString("name")
                val size = o.optString("size", "0").toLongOrNull() ?: 0L
                val props: JSONObject? = o.optJSONObject("appProperties")
                val taken = props?.optString("t")?.toLongOrNull()
                    ?: runCatching { Instant.parse(o.optString("modifiedTime")).toEpochMilli() }.getOrDefault(0L)
                val key = props?.optString("k")?.ifEmpty { null } ?: makeKey(path, name, size, taken)
                remote.add(
                    RemoteFile(
                        o.getString("id"), name, mime, size, o.optString("md5Checksum").ifEmpty { null },
                        path, key, taken, o.optString("thumbnailLink").ifEmpty { null }
                    )
                )
            }
        }

        // 3) merge
        val existing = app.db.query(order = null)
        val byKey = existing.associateBy { it.key }
        val byDrive = existing.filter { it.driveId != null }.associateBy { it.driveId!! }
        val out = ArrayList<Item>()
        val seenDrive = HashSet<String>()
        for (rf in remote) {
            seenDrive.add(rf.id)
            val ex = byDrive[rf.id] ?: byKey[rf.key]
            if (ex == null) {
                out.add(
                    Item(
                        key = rf.key, name = rf.name, mime = rf.mime, size = rf.size, dateTaken = rf.taken,
                        relPath = rf.path, driveId = rf.id, md5 = rf.md5, state = State.UPLOADED, thumbUrl = rf.thumb
                    )
                )
            } else if (ex.state != State.HIDDEN) {
                out.add(
                    ex.copy(
                        driveId = rf.id, md5 = rf.md5, thumbUrl = rf.thumb,
                        state = State.UPLOADED, failCount = 0, lastError = null
                    )
                )
            }
        }
        app.db.upsertAll(out)
        markGone(seenDrive)
        prefs.lastRemoteSync = System.currentTimeMillis()
    }

    /**
     * Files that vanished from Drive. If the phone still has them they are queued again
     * (never lose data); otherwise the row is dropped.
     */
    private fun markGone(seenDrive: Set<String>) {
        val fix = ArrayList<Item>()
        val drop = ArrayList<String>()
        for (ex in app.db.query("state=${State.UPLOADED} AND drive_id IS NOT NULL", null, null)) {
            if (ex.driveId in seenDrive) continue
            if (ex.localPresent) {
                fix.add(ex.copy(driveId = null, md5 = null, thumbUrl = null, state = State.PENDING, verified = false))
            } else {
                drop.add(ex.key)
            }
        }
        app.db.upsertAll(fix)
        app.db.delete(drop)
    }
}
