package app.drivegallery

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.drivegallery.ui.AppTheme
import app.drivegallery.ui.GalleryScreen
import app.drivegallery.ui.docUri

/**
 * Appears when another app (WhatsApp, Telegram, Gmail, ...) asks the user to pick a photo or video.
 * The chosen files are handed back as read-only content links; the other app never needs to know
 * whether the file lives on the phone or on Drive, it is fetched on demand.
 */
class PickerActivity : ComponentActivity() {

    private val multi get() = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

    private val allowedTypes: List<String>
        get() = (intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            ?: listOfNotNull(intent.type)).ifEmpty { listOf("*/*") }

    private fun matches(mime: String) = allowedTypes.any { a ->
        a == "*/*" || (a.endsWith("/*") && mime.startsWith(a.dropLast(1))) || a.equals(mime, ignoreCase = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as App
        setResult(RESULT_CANCELED)

        setContent {
            AppTheme {
                val all by app.items.collectAsState()
                val list = remember(all) {
                    all.filter { (it.localPresent || it.driveId != null) && matches(it.mime) }
                }
                GalleryScreen(
                    app = app,
                    title = "Choose from Cloud Gallery",
                    items = list,
                    onBack = { finish() },
                    pickerMode = true,
                    multi = multi,
                    onPicked = { finishWith(it) },
                )
            }
        }
    }

    private fun finishWith(picked: List<Item>) {
        if (picked.isEmpty()) return
        val uris = picked.map { docUri(it) }
        val result = Intent()
            .setData(uris.first())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1 || multi) {
            val clip = ClipData.newUri(contentResolver, "media", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            result.clipData = clip
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
