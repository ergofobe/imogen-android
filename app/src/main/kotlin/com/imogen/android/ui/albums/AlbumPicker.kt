package com.imogen.android.ui.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imogen.sdk.Album

/**
 * Where a selection goes.
 *
 * "New album…" sits at the top rather than the bottom: somebody who has just selected
 * fifteen photographs of one afternoon usually wants a new album for them, and putting
 * that below a scrolling list of the existing ones hides the likeliest answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPicker(
    albums: List<Album>,
    onDismiss: () -> Unit,
    onChoose: (Album) -> Unit,
    onCreate: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var naming by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Add to album",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )

            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { naming = true }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.padding(end = 16.dp))
                Text("New album…", style = MaterialTheme.typography.bodyLarge)
            }

            HorizontalDivider()

            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(albums, key = { it.id }) { album ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(album) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(album.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${album.assetCount} " +
                                if (album.assetCount == 1L) "photo" else "photos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (naming) {
        NameAlbumDialog(
            title = "New album",
            initial = "",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                onCreate(name)
            },
        )
    }
}
