package com.imogen.android.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What can be done to a selection.
 *
 * The count is stated rather than implied. Selecting across a long scroll is easy to lose
 * track of, and "move 340 photographs to the trash" is a different decision from "move 3".
 */
@Composable
fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
    trash: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onAddToAlbum: (() -> Unit)? = null,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear selection") }
            Text(
                "$count selected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (trash) {
                onRestore?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.Restore, "Put back") }
                }
            } else {
                onAddToAlbum?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.LibraryAdd, "Add to album") }
                }
                onFavourite?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.Favorite, "Favourite") }
                }
                IconButton(onClick = onTrash) { Icon(Icons.Filled.Delete, "Move to trash") }
            }
        }
    }
}
