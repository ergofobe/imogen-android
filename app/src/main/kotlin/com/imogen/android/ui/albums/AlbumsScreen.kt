package com.imogen.android.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.imogen.android.data.Session
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.ErrorState
import com.imogen.android.ui.common.Loading
import com.imogen.sdk.Album

/**
 * The albums, as covers.
 *
 * A list of names would be smaller and would tell you almost nothing: people remember an
 * album by the photograph on the front of it, which is why the cover is the control and
 * the name sits underneath.
 */
@Composable
fun AlbumsScreen(
    session: Session,
    model: AlbumsViewModel,
    columns: Int,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by model.state.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.albums.isEmpty() -> Loading()
            state.error != null && state.albums.isEmpty() ->
                ErrorState(state.error!!, onRetry = model::refresh)
            state.albums.isEmpty() -> EmptyState(
                "No albums yet",
                "An album is a way to keep a set of photographs together — a trip, a person, a year.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns.coerceAtMost(6)),
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.albums, key = { it.id }) { album ->
                    AlbumCover(session, album, onClick = { onOpen(album) })
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { naming = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("New album") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (naming) {
        NameAlbumDialog(
            title = "New album",
            initial = "",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                model.create(name)
            },
        )
    }
}

@Composable
private fun AlbumCover(session: Session, album: Album, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            album.coverAssetId?.let { cover ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(session.assetUrl(cover, "thumbnail"))
                        .memoryCacheKey("${session.accountId}:$cover:thumbnail")
                        .diskCacheKey("${session.accountId}:$cover:thumbnail")
                        .build(),
                    imageLoader = session.imageLoader,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        Text(
            "${album.assetCount} " + if (album.assetCount == 1L) "photo" else "photos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )
    }
}

@Composable
fun NameAlbumDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
