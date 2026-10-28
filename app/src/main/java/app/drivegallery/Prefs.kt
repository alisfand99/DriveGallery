package app.drivegallery

import android.content.Context

/** What happens to the phone copy after a file is uploaded and verified. */
enum class DeleteMode { AUTO, MANUAL, ON_DEMAND }

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** MANUAL is the safe default: nothing is removed until the user picks another mode. */
    var deleteMode: DeleteMode
        get() = runCatching { DeleteMode.valueOf(sp.getString("deleteMode", "") ?: "") }
            .getOrDefault(DeleteMode.MANUAL)
        set(v) = sp.edit().putString("deleteMode", v.name).apply()

    var wifiOnly: Boolean
        get() = bool("wifiOnly", false)
        set(v) = setBool("wifiOnly", v)

    var chargingOnly: Boolean
        get() = bool("chargingOnly", false)
        set(v) = setBool("chargingOnly", v)

    var includeVideos: Boolean
        get() = bool("includeVideos", true)
        set(v) = setBool("includeVideos", v)

    /** True once the user has granted Drive access at least once. */
    var authorized: Boolean
        get() = bool("authorized", false)
        set(v) = setBool("authorized", v)

    var rootName: String
        get() = sp.getString("rootName", "PhoneGallery") ?: "PhoneGallery"
        set(v) = sp.edit().putString("rootName", v).apply()

    var rootFolderId: String?
        get() = sp.getString("rootFolderId", null)
        set(v) = sp.edit().putString("rootFolderId", v).apply()

    var lastRemoteSync: Long
        get() = sp.getLong("lastRemoteSync", 0L)
        set(v) = sp.edit().putLong("lastRemoteSync", v).apply()

    fun remoteSyncDue(maxAgeMs: Long = 6 * 60 * 60 * 1000L) =
        System.currentTimeMillis() - lastRemoteSync > maxAgeMs

    /** Cache of Drive folder ids, keyed by "parentId/name". */
    fun folderId(key: String): String? = sp.getString("f:$key", null)
    fun putFolderId(key: String, id: String) = sp.edit().putString("f:$key", id).apply()
    fun clearFolderIds() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("f:") }.forEach { e.remove(it) }
        e.apply()
    }
}
