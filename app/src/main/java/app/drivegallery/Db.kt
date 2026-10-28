package app.drivegallery

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object State {
    const val PENDING = 0   // on the phone, not uploaded yet
    const val UPLOADED = 1  // exists on Drive
    const val FAILED = 2    // gave up after repeated errors
    const val HIDDEN = 3    // removed by the user, kept only so it is not uploaded again
}

data class Item(
    val key: String,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
    val relPath: String,
    val driveId: String? = null,
    val md5: String? = null,
    val localId: Long? = null,
    val localUri: String? = null,
    val state: Int = State.PENDING,
    val verified: Boolean = false,
    val thumbPath: String? = null,
    val thumbUrl: String? = null,
    val failCount: Int = 0,
    val lastError: String? = null,
    val localPresent: Boolean = false,
) {
    val isVideo get() = mime.startsWith("video/")
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "gallery.db", null, 1) {

    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick
    private fun bump() { _tick.value = _tick.value + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items(
              k TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
              taken INTEGER NOT NULL, path TEXT NOT NULL, drive_id TEXT, md5 TEXT,
              local_id INTEGER, local_uri TEXT, state INTEGER NOT NULL, verified INTEGER NOT NULL,
              thumb_path TEXT, thumb_url TEXT, fail_count INTEGER NOT NULL, last_error TEXT,
              local_present INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_taken ON items(taken DESC)")
        db.execSQL("CREATE INDEX idx_drive ON items(drive_id)")
        db.execSQL("CREATE INDEX idx_state ON items(state, local_present)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private val cols = arrayOf(
        "k", "name", "mime", "size", "taken", "path", "drive_id", "md5", "local_id", "local_uri",
        "state", "verified", "thumb_path", "thumb_url", "fail_count", "last_error", "local_present"
    )

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.lng(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private fun read(c: Cursor) = Item(
        key = c.getString(0), name = c.getString(1), mime = c.getString(2), size = c.getLong(3),
        dateTaken = c.getLong(4), relPath = c.getString(5), driveId = c.str(6), md5 = c.str(7),
        localId = c.lng(8), localUri = c.str(9), state = c.getInt(10), verified = c.getInt(11) == 1,
        thumbPath = c.str(12), thumbUrl = c.str(13), failCount = c.getInt(14), lastError = c.str(15),
        localPresent = c.getInt(16) == 1,
    )

    private fun values(i: Item) = ContentValues().apply {
        put("k", i.key); put("name", i.name); put("mime", i.mime); put("size", i.size)
        put("taken", i.dateTaken); put("path", i.relPath); put("drive_id", i.driveId)
        put("md5", i.md5); put("local_id", i.localId); put("local_uri", i.localUri)
        put("state", i.state); put("verified", if (i.verified) 1 else 0)
        put("thumb_path", i.thumbPath); put("thumb_url", i.thumbUrl)
        put("fail_count", i.failCount); put("last_error", i.lastError)
        put("local_present", if (i.localPresent) 1 else 0)
    }

    fun query(
        where: String? = null,
        args: Array<String>? = null,
        order: String? = "taken DESC",
        limit: String? = null,
    ): List<Item> {
        readableDatabase.query("items", cols, where, args, null, null, order, limit).use { c ->
            val out = ArrayList<Item>(c.count)
            while (c.moveToNext()) out.add(read(c))
            return out
        }
    }

    fun get(key: String): Item? = query("k=?", arrayOf(key), null, "1").firstOrNull()

    /** Everything the gallery shows. */
    fun visible(): List<Item> = query("state<>${State.HIDDEN}")

    fun pending(): List<Item> =
        query("state=${State.PENDING} AND local_present=1", null, "taken DESC")

    fun uploadedOnPhone(): List<Item> =
        query("state=${State.UPLOADED} AND local_present=1", null, "taken ASC")

    fun count(where: String): Int =
        DatabaseUtils.queryNumEntries(readableDatabase, "items", where).toInt()

    fun paths(): List<String> {
        readableDatabase.rawQuery(
            "SELECT DISTINCT path FROM items WHERE state<>${State.HIDDEN}", null
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            return out
        }
    }

    fun upsert(i: Item) {
        writableDatabase.insertWithOnConflict("items", null, values(i), SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun upsertAll(list: List<Item>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in list) db.insertWithOnConflict("items", null, values(i), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    fun update(key: String, v: ContentValues) {
        writableDatabase.update("items", v, "k=?", arrayOf(key))
        bump()
    }

    fun delete(keys: List<String>) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (k in keys) db.delete("items", "k=?", arrayOf(k))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    fun resetFailed() {
        writableDatabase.execSQL(
            "UPDATE items SET state=${State.PENDING}, fail_count=0, last_error=NULL WHERE state=${State.FAILED}"
        )
        bump()
    }
}
