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
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "To get started, complete these two steps. After that every photo and video uploads automatically.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            StepRow("Sign in to Google Drive", perms.drive, "Connect", host.requestDrive)
            StepRow("Full access to photos and videos", perms.media, "Allow", host.requestMedia)
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

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text("In Drive: $inDrive    Waiting to upload: $queued")
            Text("Uploaded and still on the phone: $reclaimable")
            Button(onClick = { Scheduler.syncNow(ctx) }, enabled = perms.drive && perms.media) {
                Text("Sync now")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            StepRow("Sign in to Google Drive", perms.drive, "Connect", host.requestDrive)
            StepRow("Full access to photos and videos", perms.media, "Allow", host.requestMedia)
            StepRow("Notifications (upload progress)", perms.notifications, "Allow", host.requestNotifications)
            StepRow("Media management (silent removal after upload)", perms.manageMedia, "Open", host.openManageMedia)
            StepRow("Unrestricted battery (reliable uploads)", perms.battery, "Allow", host.requestBattery)

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("After upload, the file on the phone", style = MaterialTheme.typography.titleMedium)
            ModeRow(
                mode == DeleteMode.AUTO, "Is removed automatically",
                "After upload and a full match with the Drive copy, the file is removed from the phone right away."
            ) {
                mode = DeleteMode.AUTO
                prefs.deleteMode = DeleteMode.AUTO
            }
            if (mode == DeleteMode.AUTO && !perms.manageMedia) {
                Text(
                    "Without the Media management permission, Android asks for confirmation before each removal and you only see a notification.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ModeRow(
                mode == DeleteMode.MANUAL, "Manual, I delete it myself",
                "The app never deletes any file. You delete them yourself on the phone."
            ) {
                mode = DeleteMode.MANUAL
                prefs.deleteMode = DeleteMode.MANUAL
            }
            ModeRow(
                mode == DeleteMode.ON_DEMAND, "With a button",
                "Whenever you want, tap the button below to remove all uploaded files at once."
            ) {
                mode = DeleteMode.ON_DEMAND
                prefs.deleteMode = DeleteMode.ON_DEMAND
            }
            Button(onClick = { confirmDelete = true }, enabled = reclaimable > 0) {
                Text("Remove all uploaded files from the phone ($reclaimable)")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Upload", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Wi-Fi only", wifi) {
                wifi = it
                prefs.wifiOnly = it
                Scheduler.ensureScheduled(ctx, replace = true)
            }
            SwitchRow("Only while charging", charging) {
                charging = it
                prefs.chargingOnly = it
                Scheduler.ensureScheduled(ctx, replace = true)
            }
            SwitchRow("Upload videos too", videos) {
                videos = it
                prefs.includeVideos = it
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Account", style = MaterialTheme.typography.titleMedium)
            Text("Folder in Drive: ${prefs.rootName}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "The folder structure in Drive is exactly the same as on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmSignOut = true }) { Text("Disconnect and stop uploading") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove from the phone?") },
            text = {
                Text(
                    "$reclaimable files that were already uploaded will be removed from the phone storage. " +
                        "Only files whose Drive copy is verified to be identical to the phone copy are removed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    host.deleteUploaded()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Disconnect?") },
            text = { Text("Automatic uploading stops. Nothing is deleted from Drive or the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    host.signOut()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
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
