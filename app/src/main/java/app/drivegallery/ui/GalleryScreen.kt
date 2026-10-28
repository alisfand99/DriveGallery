package app.drivegallery.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.drivegallery.App
import app.drivegallery.Item
import app.drivegallery.Scheduler
import app.drivegallery.State
import kotlinx.coroutines.launch

private sealed interface Cell {
    class Header(val label: String, val id: String) : Cell
    class Media(val item: Item, val index: Int) : Cell
}

private fun buildCells(items: List<Item>): List<Cell> {
    val out = ArrayList<Cell>(items.size + 128)
    var lastDay = Long.MIN_VALUE
    items.forEachIndexed { i, item ->
        val d = dayNumber(item.dateTaken)
        if (d != lastDay) {
            lastDay = d
            out.add(Cell.Header(dayLabel(item.dateTaken), "h$d"))
        }
        out.add(Cell.Media(item, i))
    }
    return out
}

/**
 * The photo grid, grouped by day. Used for the main library, for a single album, and (with
 * [pickerMode]) as the chooser that appears when another app asks for a photo.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    app: App,
    title: String,
    items: List<Item>,
    onBack: (() -> Unit)? = null,
    pickerMode: Boolean = false,
    multi: Boolean = true,
    onOpen: (Int) -> Unit = {},
    onPicked: (List<Item>) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val cells = remember(items) { buildCells(items) }

    BackHandler(enabled = selecting) { selected = emptySet() }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} انتخاب شد" else title) },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, null) }
                    } else if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    }
                },
                actions = {
                    if (selecting && pickerMode) {
                        TextButton(onClick = { onPicked(items.filter { it.key in selected }) }) { Text("تأیید") }
                    } else if (selecting) {
                        IconButton(onClick = { shareItems(ctx, items.filter { it.key in selected }) }) {
                            Icon(Icons.Default.Share, null)
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, null) }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty() && header == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("اینجا هنوز چیزی نیست", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (header != null) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                }
                items(
                    cells,
                    key = { c -> if (c is Cell.Header) c.id else (c as Cell.Media).item.key },
                    span = { c -> if (c is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                ) { c ->
                    when (c) {
                        is Cell.Header -> Text(
                            c.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        )

                        is Cell.Media -> {
                            val item = c.item
                            val isSelected = item.key in selected
                            Box(
                                Modifier.aspectRatio(1f).combinedClickable(
                                    onClick = {
                                        when {
                                            pickerMode && !multi -> onPicked(listOf(item))
                                            pickerMode || selecting -> toggle(item.key)
                                            else -> onOpen(c.index)
                                        }
                                    },
                                    onLongClick = { toggle(item.key) },
                                )
                            ) {
                                Thumb(item)
                                if (item.isVideo) {
                                    Icon(
                                        Icons.Default.PlayCircle, null, tint = Color.White,
                                        modifier = Modifier.align(Alignment.Center).size(30.dp)
                                    )
                                }
                                if (!pickerMode) StatusBadge(item)
                                if (isSelected) {
                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                                    Icon(
                                        Icons.Default.CheckCircle, null, tint = Color.White,
                                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val chosen = items.filter { it.key in selected }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف ${chosen.size} مورد؟") },
            text = {
                Text(
                    "از گالری ابری حذف می‌شوند و به سطل زباله Google Drive می‌روند (تا ۳۰ روز قابل بازیابی). " +
                        "نسخه‌ای که هنوز روی گوشی است دست نمی‌خورد."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            removeFromLibrary(app, chosen)
                            selected = emptySet()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "حذف انجام نشد: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun BoxScope.StatusBadge(item: Item) {
    when {
        item.state == State.FAILED -> Icon(
            Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp)
        )

        item.state == State.PENDING -> Icon(
            Icons.Default.CloudUpload, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp)
        )

        item.localPresent -> Icon(
            Icons.Default.CloudDone, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp)
        )
    }
}

/** Upload progress and problems, shown above the grid. Disappears when everything is in sync. */
@Composable
fun SyncBanner(app: App, items: List<Item>) {
    val ctx = LocalContext.current
    val running by app.engine.running.collectAsState()
    val done by app.engine.done.collectAsState()
    val total by app.engine.total.collectAsState()
    val pending = remember(items) { items.count { it.state == State.PENDING } }
    val failed = remember(items) { items.count { it.state == State.FAILED } }
    if (!running && pending == 0 && failed == 0) return

    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            if (running && total > 0) {
                Text("در حال آپلود: $done از $total")
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else if (pending > 0) {
                Text("$pending فایل در صف آپلود است")
            }
            if (failed > 0) {
                Text("$failed فایل آپلود نشد", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    app.db.resetFailed()
                    Scheduler.syncNow(ctx)
                }) { Text("تلاش دوباره") }
            }
        }
    }
}
