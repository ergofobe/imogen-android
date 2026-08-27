package com.imogen.android.ui.people

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imogen.android.data.Session
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.Loading
import com.imogen.android.ui.timeline.PhotoGrid
import com.imogen.android.ui.viewer.DetailsSheet
import com.imogen.android.ui.viewer.Viewer
import com.imogen.android.ui.viewer.ViewerMode
import com.imogen.sdk.Asset

/**
 * One person's photographs.
 *
 * Not a `PhotoBrowser`: the people endpoint hands back a person with their photographs
 * attached rather than a cursor-paged query, so there is nothing here to page through and
 * pretending otherwise would mean an `AssetQuery` filter the API does not have.
 */
@Composable
fun PersonDetailScreen(
    session: Session,
    personId: String,
    columns: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var photos by remember(personId) { mutableStateOf<List<Asset>?>(null) }
    var openedAt by remember { mutableStateOf<Int?>(null) }
    var details by remember { mutableStateOf<Asset?>(null) }

    LaunchedEffect(personId) {
        photos = runCatching { session.client.people.get(personId).photos }.getOrDefault(emptyList())
    }

    Box(modifier.fillMaxSize()) {
        when (val loaded = photos) {
            null -> Loading()
            else -> if (loaded.isEmpty()) {
                EmptyState("No photographs", "Nothing here is grouped under this person.")
            } else {
                PhotoGrid(
                    session = session,
                    assets = loaded,
                    selection = emptySet(),
                    columns = columns,
                    contentPadding = contentPadding,
                    onOpen = { openedAt = it },
                    onToggleSelection = {},
                )
            }
        }
    }

    val loaded = photos
    if (loaded != null) {
        openedAt?.let { index ->
            Viewer(
                session = session,
                assets = loaded,
                initialIndex = index,
                mode = ViewerMode.Library,
                onClose = { openedAt = null },
                // Editing from here would need the list refetching to stay honest, and a
                // person's page is somewhere you look rather than somewhere you tidy.
                onFavorite = { _, _ -> },
                onArchive = { _, _ -> },
                onTrash = {},
                onRestore = {},
                onDetails = { details = it },
            )
        }
    }

    details?.let { asset ->
        DetailsSheet(asset = asset, onDismiss = { details = null }, onDescriptionChanged = {})
    }
}
