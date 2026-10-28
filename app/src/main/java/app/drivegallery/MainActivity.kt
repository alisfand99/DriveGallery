package app.drivegallery

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.drivegallery.ui.AlbumsScreen
import app.drivegallery.ui.AppTheme
import app.drivegallery.ui.GalleryScreen
import app.drivegallery.ui.Host
import app.drivegallery.ui.Perms
import app.drivegallery.ui.SettingsScreen
import app.drivegallery.ui.SetupCard
import app.drivegallery.ui.SyncBanner
import app.drivegallery.ui.ViewerScreen
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ViewerRequest(val list: List<Item>, val index: Int)

class MainActivity : ComponentActivity() {

    private val app get() = application as App

    private var perms by mutableStateOf(Perms(false, false, false, false, false))
    private var tab by mutableIntStateOf(0)
    private var askDelete by mutableStateOf(false)
    private val consentQueue = ArrayDeque<PendingIntent>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPerms()
        // Denied for good, or only "selected photos" granted: the only way left is the app's settings page.
        if (!perms.media && !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)) {
            toast("در تنظیمات برنامه، دسترسی «همه عکس‌ها و ویدیوها» را مجاز کن.")
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            try {
                val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
                acceptAuth(r.accessToken)
            } catch (e: Exception) {
                toast("ورود انجام نشد: ${e.message}")
            }
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { launchNextConsent() }

    private val host by lazy {
        Host(
            requestMedia = {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.ACCESS_MEDIA_LOCATION,
                    )
                )
            },
            requestNotifications = { permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
            requestDrive = { requestDrive() },
            openManageMedia = {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName"))
                    )
                }
            },
            requestBattery = {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            },
            deleteUploaded = { deleteUploaded() },
            signOut = { signOut() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        refreshPerms()
        setContent { AppTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        if (app.prefs.authorized) {
            lifecycleScope.launch { runCatching { Auth.silentToken(this@MainActivity) } }
            if (perms.media) Scheduler.syncSoon(this)
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra(Notifier.EXTRA_ACTION) == Notifier.ACTION_DELETE_UPLOADED) {
            tab = 2
            askDelete = true
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun refreshPerms() {
        perms = Perms(
            media = app.scanner.hasFullAccess(),
            notifications = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            manageMedia = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this),
            battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            drive = app.prefs.authorized,
        )
        if (perms.drive && perms.media) Scheduler.ensureScheduled(this)
    }

    // ---- Google Drive access ----

    private fun requestDrive() {
        Identity.getAuthorizationClient(this).authorize(Auth.request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    acceptAuth(r.accessToken)
                }
            }
            .addOnFailureListener { toast("اتصال به Google انجام نشد: ${it.message}") }
    }

    private fun acceptAuth(token: String?) {
        Auth.cachedToken = token
        app.prefs.authorized = true
        Scheduler.ensureScheduled(this, replace = true)
        refreshPerms()
        if (perms.media) Scheduler.syncNow(this)
    }

    private fun signOut() {
        app.prefs.authorized = false
        Auth.cachedToken = null
        Scheduler.cancelAll(this)
        refreshPerms()
    }

    // ---- removing uploaded files from the phone ----

    private fun deleteUploaded() {
        lifecycleScope.launch {
            val out = app.deleter.deleteUploaded()
            if (out.deleted > 0) toast("${out.deleted} فایل از گوشی پاک شد")
            if (out.unverified > 0) {
                toast("${out.unverified} فایل پاک نشد چون با نسخه Drive تطبیق داده نشد")
            }
            if (out.needConsent.isNotEmpty()) {
                consentQueue.clear()
                consentQueue.addAll(app.deleter.consentRequests(out.needConsent))
                launchNextConsent()
            }
        }
    }

    private fun launchNextConsent() {
        val pi = consentQueue.removeFirstOrNull()
        if (pi != null) {
            consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            lifecycleScope.launch { withContext(Dispatchers.IO) { app.scanner.scan() } }
        }
    }

    // ---- UI ----

    @Composable
    private fun Root() {
        val items by app.items.collectAsState()
        var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
        var album by remember { mutableStateOf<String?>(null) }

        BackHandler(enabled = album != null && viewer == null) { album = null }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.PhotoLibrary, null) }, label = { Text("گالری") }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Folder, null) }, label = { Text("آلبوم‌ها") }
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Settings, null) }, label = { Text("تنظیمات") }
                        )
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                    when (tab) {
                        0 -> GalleryScreen(
                            app = app, title = "گالری", items = items,
                            onOpen = { viewer = ViewerRequest(items, it) },
                            header = {
                                Column {
                                    SetupCard(perms, host)
                                    SyncBanner(app, items)
                                }
                            },
                        )

                        1 -> AlbumsScreen(items) { album = it }

                        else -> SettingsScreen(
                            app = app, items = items, perms = perms, host = host,
                            askDeleteNow = askDelete, onAskHandled = { askDelete = false },
                        )
                    }
                }
            }

            album?.let { path ->
                val inAlbum = remember(items, path) { items.filter { it.relPath == path } }
                Surface(Modifier.fillMaxSize()) {
                    GalleryScreen(
                        app = app, title = path.substringAfterLast('/'), items = inAlbum,
                        onBack = { album = null },
                        onOpen = { viewer = ViewerRequest(inAlbum, it) },
                    )
                }
            }

            viewer?.let { v ->
                ViewerScreen(app, v.list, v.index, onClose = { viewer = null })
            }
        }
    }
}
