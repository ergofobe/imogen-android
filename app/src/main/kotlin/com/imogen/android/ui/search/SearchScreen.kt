package com.imogen.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imogen.android.data.Session
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.timeline.AssetFeed
import com.imogen.android.ui.timeline.PhotoBrowser
import com.imogen.sdk.AssetQuery
import com.imogen.sdk.AssetType

/**
 * Search.
 *
 * The query is only sent when somebody says so — on the enter key, or when a filter chip
 * changes. Searching on every keystroke would mean a request per letter to a server that
 * may be a Raspberry Pi at the end of a domestic uplink, and the results would flicker
 * through nonsense on the way to the word.
 */
@Composable
fun SearchScreen(
    session: Session,
    columns: Int,
    onAddToAlbum: ((List<String>) -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var text by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<AssetType?>(null) }
    var favouritesOnly by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<AssetQuery?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        keyboard?.hide()
        val trimmed = text.trim()
        submitted = if (trimmed.isEmpty() && type == null && !favouritesOnly) {
            null
        } else {
            AssetQuery(
                q = trimmed.ifEmpty { null },
                type = type,
                favorite = favouritesOnly.takeIf { it },
            )
        }
    }

    Column(modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Filename, description, camera, place") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = type == AssetType.IMAGE,
                onClick = {
                    type = if (type == AssetType.IMAGE) null else AssetType.IMAGE
                    submit()
                },
                label = { Text("Photos") },
            )
            FilterChip(
                selected = type == AssetType.VIDEO,
                onClick = {
                    type = if (type == AssetType.VIDEO) null else AssetType.VIDEO
                    submit()
                },
                label = { Text("Videos") },
            )
            FilterChip(
                selected = favouritesOnly,
                onClick = {
                    favouritesOnly = !favouritesOnly
                    submit()
                },
                label = { Text("Favourites") },
            )
        }

        val query = submitted
        if (query == null) {
            EmptyState(
                "Search the library",
                "imogen looks through filenames, descriptions, camera models and places.",
            )
        } else {
            // Keyed on the query so a new search builds a new feed rather than appending
            // to the last one's pages.
            val feed: AssetFeed = viewModel(
                key = "search:${query.q}:${query.type}:${query.favorite}",
                factory = AssetFeed.factory(session, query),
            )
            PhotoBrowser(
                session = session,
                feed = feed,
                columns = columns,
                emptyHeadline = "Nothing matched",
                emptyBody = "Try fewer words, or a different filter.",
                onAddToAlbum = onAddToAlbum,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
