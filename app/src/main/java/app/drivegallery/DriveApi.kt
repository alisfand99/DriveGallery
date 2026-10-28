package app.drivegallery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class DriveException(val code: Int, message: String) : IOException("HTTP $code: $message")

data class UploadResult(val id: String, val size: Long, val md5: String?, val thumbnailLink: String?)

/** Streams a file to Drive while computing its MD5 on the fly, so the upload can be verified. */
private class StreamBody(
    private val type: MediaType?,
    private val length: Long,
    private val digest: MessageDigest,
    private val open: () -> InputStream,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        digest.reset()
        open().use { input ->
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                sink.write(buf, 0, n)
                total += n
                onProgress(total)
            }
        }
    }
}

class DriveApi(private val http: OkHttpClient, private val token: suspend () -> String?) {
    private val api = "https://www.googleapis.com/drive/v3"
    private val jsonType = "application/json; charset=UTF-8".toMediaType()
    private val folderMime = "application/vnd.google-apps.folder"

    fun mediaUrl(id: String) = "$api/files/$id?alt=media"

    private suspend fun <T> call(build: (String) -> Request, parse: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            for (attempt in 0..1) {
                val t = token() ?: throw AuthNeeded()
                http.newCall(build(t)).execute().use { r ->
                    if (r.code == 401 && attempt == 0) {
                        Auth.cachedToken = null
                        return@use
                    }
                    if (!r.isSuccessful) throw DriveException(r.code, r.body?.string()?.take(300) ?: "")
                    return@withContext parse(r)
                }
            }
            throw DriveException(401, "unauthorized")
        }

    private fun authed(t: String, url: okhttp3.HttpUrl) =
        Request.Builder().url(url).header("Authorization", "Bearer $t")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    suspend fun findFolder(name: String, parentId: String): String? {
        val q = "mimeType='$folderMime' and trashed=false and name='${esc(name)}' and '$parentId' in parents"
        val url = "$api/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("spaces", "drive")
            .build()
        return call({ authed(it, url).build() }) { r ->
            val arr = JSONObject(r.body!!.string()).getJSONArray("files")
            if (arr.length() > 0) arr.getJSONObject(0).getString("id") else null
        }
    }

    suspend fun createFolder(name: String, parentId: String): String {
        val body = JSONObject().put("name", name).put("mimeType", folderMime)
            .put("parents", JSONArray().put(parentId)).toString().toRequestBody(jsonType)
        val url = "$api/files?fields=id".toHttpUrl()
        return call({ authed(it, url).post(body).build() }) { r ->
            JSONObject(r.body!!.string()).getString("id")
        }
    }

    suspend fun ensureFolder(name: String, parentId: String): String =
        findFolder(name, parentId) ?: createFolder(name, parentId)

    /** True when the file exists and is not in the trash. */
    suspend fun exists(id: String): Boolean = try {
        val url = "$api/files/$id?fields=id,trashed".toHttpUrl()
        call({ authed(it, url).build() }) { r ->
            !JSONObject(r.body!!.string()).optBoolean("trashed", false)
        }
    } catch (e: DriveException) {
        if (e.code == 404) false else throw e
    }

    /** Pages through a files.list query and hands each page to [onPage]. */
    suspend fun list(q: String, fields: String, onPage: (JSONArray) -> Unit) {
        var pageToken: String? = null
        do {
            val b = "$api/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files($fields)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("spaces", "drive")
            pageToken?.let { b.addQueryParameter("pageToken", it) }
            val url = b.build()
            val obj = call({ authed(it, url).build() }) { r -> JSONObject(r.body!!.string()) }
            onPage(obj.optJSONArray("files") ?: JSONArray())
            pageToken = obj.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
    }

    suspend fun upload(
        name: String,
        mime: String,
        parentId: String,
        appProps: Map<String, String>,
        modifiedMs: Long,
        size: Long,
        digest: MessageDigest,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): UploadResult {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", mime)
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", JSONObject(appProps))
            .put("modifiedTime", java.time.Instant.ofEpochMilli(modifiedMs).toString())
            .toString().toRequestBody(jsonType)
        val initUrl = ("https://www.googleapis.com/upload/drive/v3/files" +
            "?uploadType=resumable&fields=id,size,md5Checksum,thumbnailLink").toHttpUrl()
        val session = call({
            authed(it, initUrl)
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", size.toString())
                .post(meta).build()
        }) { r -> r.header("Location") ?: throw IOException("Drive did not return an upload session") }

        return withContext(Dispatchers.IO) {
            val body = StreamBody(mime.toMediaTypeOrNull(), size, digest, open, onProgress)
            val req = Request.Builder().url(session).put(body).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw DriveException(r.code, r.body?.string()?.take(300) ?: "")
                val o = JSONObject(r.body!!.string())
                UploadResult(
                    id = o.getString("id"),
                    size = o.optString("size", "0").toLongOrNull() ?: 0L,
                    md5 = o.optString("md5Checksum").ifEmpty { null },
                    thumbnailLink = o.optString("thumbnailLink").ifEmpty { null },
                )
            }
        }
    }

    /** Moves a file to the Drive trash (Drive keeps it there for 30 days). */
    suspend fun trash(id: String) {
        val url = "$api/files/$id?fields=id".toHttpUrl()
        val body = JSONObject().put("trashed", true).toString().toRequestBody(jsonType)
        call({ authed(it, url).patch(body).build() }) { }
    }

    suspend fun download(id: String, dest: File) {
        val url = mediaUrl(id).toHttpUrl()
        call({ authed(it, url).build() }) { r ->
            dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
    }
}
