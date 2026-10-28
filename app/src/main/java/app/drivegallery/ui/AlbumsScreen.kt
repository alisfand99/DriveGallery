package app.drivegallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.drivegallery.Item

/** One card per phone folder (DCIM/Camera, Pictures/Screenshots, WhatsApp Images, ...), same names as on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(items: List<Item>, onOpenAlbum: (String) -> Unit) {
    val albums = remember(items) { items.groupBy { it.relPath }.entries.sortedBy { it.key.lowercase() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Albums") }) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { it.key }) { entry ->
                Card(onClick = { onOpenAlbum(entry.key) }) {
                    Thumb(entry.value.first(), Modifier.aspectRatio(1f))
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            entry.key.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.key.contains('/')) {
                            Text(
                                entry.key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "${entry.value.size} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
