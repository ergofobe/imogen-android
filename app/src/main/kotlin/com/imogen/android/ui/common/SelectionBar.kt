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
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What can be done to a selection.
 *
 * The count is stated rather than implied. Selecting across a long scroll is easy to lose
 * track of, and "move 340 photographs to the trash" is a different decision from "move 3".
 *
 * A null [count] means one is on its way: "select all" is a filter rather than a list of
 * ids, so how many photographs it holds is a question for the server. The bar says so
 * rather than guessing, because a guess here is the number somebody acts on.
 */
@Composable
fun SelectionBar(
    count: Long?,
    onClear: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
    trash: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onAddToAlbum: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
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
                count?.let { "${formatCount(it)} selected" } ?: "Counting…",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            onSelectAll?.let {
                IconButton(onClick = it) { Icon(Icons.Filled.SelectAll, "Select all") }
            }
            // Nothing bulk while the number is still coming. A selection nobody can put a
            // figure to is not one to act on, whichever way the action points.
            if (trash) {
                onRestore?.let {
                    IconButton(onClick = it, enabled = count != null) {
                        Icon(Icons.Filled.Restore, "Put back")
                    }
                }
            } else {
                onAddToAlbum?.let {
                    IconButton(onClick = it, enabled = count != null) {
                        Icon(Icons.Filled.LibraryAdd, "Add to album")
                    }
                }
                onFavourite?.let {
                    IconButton(onClick = it) { Icon(Icons.Filled.Favorite, "Favourite") }
                }
                // Nothing destructive while the number is still coming. A live button that
                // does nothing teaches people to press it twice.
                IconButton(onClick = onTrash, enabled = count != null) {
                    Icon(Icons.Filled.Delete, "Move to trash")
                }
            }
        }
    }
}

/**
 * Asks before a trash that acts on a query rather than on a list.
 *
 * The number is the whole point of the dialog. A selection made in one tap can hold the
 * entire library, and "all photos" is not a quantity anybody can weigh — so the count is
 * resolved with the server first and stated here.
 */
@Composable
fun ConfirmTrashDialog(count: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Move ${formatCount(count)} " +
                    (if (count == 1L) "photo" else "photos") + " to the trash?",
            )
        },
        text = { Text("They wait there until the server removes them.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Move to trash") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Grouped, because twelve thousand and twelve hundred look alike at a glance otherwise. */
private fun formatCount(count: Long): String = "%,d".format(count)
