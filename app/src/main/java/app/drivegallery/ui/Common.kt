package app.drivegallery.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.drivegallery.App
import app.drivegallery.DriveException
import app.drivegallery.GalleryDocumentsProvider
import app.drivegallery.Item
import app.drivegallery.State
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything is right to left, and colours follow the system (dynamic colour on Android 12+). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** What the permission and setup screens need to know about the current state of the phone. */
data class Perms(
    val media: Boolean,
    val notifications: Boolean,
    val manageMedia: Boolean,
    val battery: Boolean,
    val drive: Boolean,
)

/** Things only an Activity can do, handed to the screens as plain lambdas. */
class Host(
    val requestMedia: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestDrive: () -> Unit,
    val openManageMedia: () -> Unit,
    val requestBattery: () -> Unit,
    val deleteUploaded: () -> Unit,
    val signOut: () -> Unit,
)

/** Best preview available without touching the network: saved thumbnail, the original, then Drive's thumbnail. */
fun thumbModel(item: Item): Any? {
    item.thumbPath?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
    if (item.localPresent && item.localUri != null) return Uri.parse(item.localUri)
    return item.thumbUrl?.replace(Regex("=s\\d+$"), "=s480")
}

fun fullModel(app: App, item: Item): Any? =
    if (item.localPresent && item.localUri != null) Uri.parse(item.localUri)
    else item.driveId?.let { app.api.mediaUrl(it) } ?: thumbModel(item)

@Composable
fun Thumb(item: Item, modifier: Modifier = Modifier.fillMaxSize(), scale: ContentScale = ContentScale.Crop) {
    val model = remember(item.key, item.thumbPath, item.localPresent, item.thumbUrl) { thumbModel(item) }
    AsyncImage(model = model, contentDescription = null, contentScale = scale, modifier = modifier)
}

private val persian: Locale = Locale.forLanguageTag("fa-IR-u-ca-persian")

fun dayLabel(ms: Long): String =
    SimpleDateFormat("EEEE d MMMM yyyy", persian).format(Date(ms))

fun dateTimeLabel(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd  HH:mm", persian).format(Date(ms))

/** Local calendar day number, used to group photos without formatting every single one. */
fun dayNumber(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%d KB".format(bytes / 1024)
}

fun docUri(item: Item): Uri =
    DocumentsContract.buildDocumentUri(
        GalleryDocumentsProvider.AUTHORITY, GalleryDocumentsProvider.fileDocId(item.key)
    )

/** Shares through Android's share sheet, so it works with WhatsApp, Telegram, Gmail and everything else. */
fun shareItems(ctx: Context, items: List<Item>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { docUri(it) })
    val kinds = items.map { if (it.isVideo) "video" else "image" }.toSet()
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = if (kinds.size == 1) "${kinds.first()}/*" else "*/*"
    val clip = ClipData.newUri(ctx.contentResolver, "media", uris[0])
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری"))
}

/**
 * Removes files from the library: they go to the Drive trash (Drive keeps them there for 30 days).
 * A copy that is still on the phone is not touched; it is only remembered so it is not uploaded again.
 */
suspend fun removeFromLibrary(app: App, items: List<Item>) {
    for (item in items) {
        val id = item.driveId ?: continue
        try {
            app.api.trash(id)
        } catch (e: DriveException) {
            if (e.code != 404) throw e
        }
    }
    val (onPhone, cloudOnly) = items.partition { it.localPresent }
    app.db.upsertAll(onPhone.map { it.copy(state = State.HIDDEN, driveId = null, md5 = null) })
    app.db.delete(cloudOnly.map { it.key })
}
