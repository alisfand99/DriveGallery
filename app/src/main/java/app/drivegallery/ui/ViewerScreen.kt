package app.drivegallery.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.drivegallery.App
import app.drivegallery.Auth
import app.drivegallery.Item
import app.drivegallery.State
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(app: App, items: List<Item>, startIndex: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val frozen = remember { items }
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (frozen.size - 1).coerceAtLeast(0))
    ) { frozen.size }
    val live by app.items.collectAsState()
    var bars by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler { onClose() }
    // A fresh access token so originals stored on Drive can be loaded.
    LaunchedEffect(Unit) { runCatching { Auth.silentToken(ctx) } }

    fun current(): Item {
        val f = frozen[pager.currentPage]
        return live.firstOrNull { it.key == f.key } ?: f
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val f = frozen[page]
            val item = remember(live, page) { live.firstOrNull { it.key == f.key } ?: f }
            val active = pager.currentPage == page
            if (item.isVideo) {
                VideoPage(app, item, active)
            } else {
                ZoomableImage(fullModel(app, item), active) { bars = !bars }
            }
        }

        AnimatedVisibility(visible = bars, modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = {
                    Text(frozen[pager.currentPage].name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = { shareItems(ctx, listOf(current())) }) { Icon(Icons.Default.Share, null) }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, null) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        }
    }

    if (showInfo) {
        val item = current()
        val where = when {
            item.state == State.UPLOADED && item.localPresent -> "On phone and on Drive"
            item.state == State.UPLOADED -> "On Drive only"
            else -> "On phone only (waiting to upload)"
        }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            title = { Text(item.name) },
            text = {
                Text(
                    "Folder: ${item.relPath}\n" +
                        "Size: ${formatSize(item.size)}\n" +
                        "Date: ${dateTimeLabel(item.dateTaken)}\n" +
                        "Stored: $where"
                )
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            text = { Text("It goes to the Google Drive trash (recoverable for 30 days). The copy on the phone is not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val target = current()
                    scope.launch {
                        try {
                            removeFromLibrary(app, listOf(target))
                            onClose()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Pinch to zoom, double tap to toggle zoom, drag to pan only while zoomed so swiping between photos still works. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(model: Any?, active: Boolean, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .transformable(transform, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** Plays from the phone if the file is still there, otherwise streams straight from Drive. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(app: App, item: Item, active: Boolean) {
    val ctx = LocalContext.current
    if (!active) {
        Box(Modifier.fillMaxSize()) {
            Thumb(item, Modifier.fillMaxSize(), ContentScale.Fit)
            Icon(
                Icons.Default.PlayCircle, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(56.dp)
            )
        }
    } else {
        val exo = remember(item.key) {
            val http = OkHttpDataSource.Factory(app.http)
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${Auth.cachedToken}"))
            ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
                .build()
        }
        DisposableEffect(exo) { onDispose { exo.release() } }
        LaunchedEffect(exo) {
            val uri = when {
                item.localPresent && item.localUri != null -> Uri.parse(item.localUri)
                item.driveId != null -> Uri.parse(app.api.mediaUrl(item.driveId))
                else -> return@LaunchedEffect
            }
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = true
        }
        AndroidView(
            factory = { c -> PlayerView(c).apply { player = exo } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
