package app.drivegallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.drivegallery.App
import app.drivegallery.DeleteMode
import app.drivegallery.Item
import app.drivegallery.Scheduler
import app.drivegallery.State

@Composable
private fun StepRow(label: String, done: Boolean, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Text(label, Modifier.weight(1f))
        if (!done) OutlinedButton(onClick = onClick) { Text(button) }
    }
}

/** Shown on top of the library until Drive access and photo access are both granted. */
@Composable
fun SetupCard(perms: Perms, host: Host) {
    if (perms.drive && perms.media) return
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("راه‌اندازی", style = MaterialTheme.typography.titleMedium)
            Text(
                "برای شروع، این دو مرحله را انجام بده. بعد از آن هر عکس و ویدیو خودکار آپلود می‌شود.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            StepRow("ورود به Google Drive", perms.drive, "اتصال", host.requestDrive)
            StepRow("دسترسی کامل به عکس‌ها و ویدیوها", perms.media, "اجازه", host.requestMedia)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: App,
    items: List<Item>,
    perms: Perms,
    host: Host,
    askDeleteNow: Boolean,
    onAskHandled: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = app.prefs
    var mode by remember { mutableStateOf(prefs.deleteMode) }
    var wifi by remember { mutableStateOf(prefs.wifiOnly) }
    var charging by remember { mutableStateOf(prefs.chargingOnly) }
    var videos by remember { mutableStateOf(prefs.includeVideos) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(askDeleteNow) {
        if (askDeleteNow) {
            confirmDelete = true
            onAskHandled()
        }
    }

    val inDrive = remember(items) { items.count { it.state == State.UPLOADED } }
    val queued = remember(items) { items.count { it.state == State.PENDING } }
    val reclaimable = remember(items) { items.count { it.state == State.UPLOADED && it.localPresent } }

    Scaffold(topBar = { TopAppBar(title = { Text("تنظیمات") }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("وضعیت", style = MaterialTheme.typography.titleMedium)
            Text("در Drive: $inDrive    در صف آپلود: $queued")
            Text("آپلودشده و هنوز روی گوشی: $reclaimable")
            Button(onClick = { Scheduler.syncNow(ctx) }, enabled = perms.drive && perms.media) {
                Text("همگام‌سازی همین حالا")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("دسترسی‌ها", style = MaterialTheme.typography.titleMedium)
            StepRow("ورود به Google Drive", perms.drive, "اتصال", host.requestDrive)
            StepRow("دسترسی کامل به عکس‌ها و ویدیوها", perms.media, "اجازه", host.requestMedia)
            StepRow("اعلان‌ها (پیشرفت آپلود)", perms.notifications, "اجازه", host.requestNotifications)
            StepRow("مدیریت رسانه (حذف بی‌صدا بعد از آپلود)", perms.manageMedia, "باز کردن", host.openManageMedia)
            StepRow("بدون محدودیت باتری (آپلود پایدار)", perms.battery, "اجازه", host.requestBattery)

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("بعد از آپلود، فایل روی گوشی", style = MaterialTheme.typography.titleMedium)
            ModeRow(
                mode == DeleteMode.AUTO, "خودکار پاک شود",
                "بعد از آپلود و تطبیق کامل با نسخه Drive، فایل بلافاصله از گوشی حذف می‌شود."
            ) {
                mode = DeleteMode.AUTO
                prefs.deleteMode = DeleteMode.AUTO
            }
            if (mode == DeleteMode.AUTO && !perms.manageMedia) {
                Text(
                    "بدون دسترسی «مدیریت رسانه» اندروید هر بار برای حذف تأیید می‌خواهد و فقط یک اعلان می‌بینی.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ModeRow(
                mode == DeleteMode.MANUAL, "دستی، خودم پاک می‌کنم",
                "برنامه هیچ فایلی را پاک نمی‌کند. خودت از داخل گوشی پاک می‌کنی."
            ) {
                mode = DeleteMode.MANUAL
                prefs.deleteMode = DeleteMode.MANUAL
            }
            ModeRow(
                mode == DeleteMode.ON_DEMAND, "با دکمه",
                "هر وقت خواستی دکمه پایین را بزن تا همه فایل‌های آپلودشده یک‌جا پاک شوند."
            ) {
                mode = DeleteMode.ON_DEMAND
                prefs.deleteMode = DeleteMode.ON_DEMAND
            }
            Button(onClick = { confirmDelete = true }, enabled = reclaimable > 0) {
                Text("پاک کردن همه فایل‌های آپلودشده از گوشی ($reclaimable)")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("آپلود", style = MaterialTheme.typography.titleMedium)
            SwitchRow("فقط با Wi‑Fi", wifi) {
                wifi = it
                prefs.wifiOnly = it
                Scheduler.ensureScheduled(ctx, replace = true)
            }
            SwitchRow("فقط وقتی در شارژ است", charging) {
                charging = it
                prefs.chargingOnly = it
                Scheduler.ensureScheduled(ctx, replace = true)
            }
            SwitchRow("ویدیوها هم آپلود شوند", videos) {
                videos = it
                prefs.includeVideos = it
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("حساب", style = MaterialTheme.typography.titleMedium)
            Text("پوشه در Drive: ${prefs.rootName}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "ساختار پوشه‌ها در Drive دقیقاً همان ساختار گوشی است.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmSignOut = true }) { Text("قطع اتصال و توقف آپلود") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("پاک کردن از گوشی؟") },
            text = {
                Text(
                    "$reclaimable فایل که قبلاً آپلود شده‌اند از حافظه گوشی پاک می‌شوند. " +
                        "فقط فایل‌هایی پاک می‌شوند که نسخه Drive آنها با نسخه گوشی کاملاً یکی بودنش تأیید شده باشد."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    host.deleteUploaded()
                }) { Text("پاک کن") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("انصراف") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("قطع اتصال؟") },
            text = { Text("آپلود خودکار متوقف می‌شود. چیزی از Drive یا گوشی پاک نمی‌شود.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    host.signOut()
                }) { Text("قطع کن") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(horizontal = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
