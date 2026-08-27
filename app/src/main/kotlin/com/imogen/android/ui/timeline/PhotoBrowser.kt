package com.imogen.android.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imogen.android.data.Session
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.SelectionBar
import com.imogen.android.ui.common.ErrorState
import com.imogen.android.ui.common.Loading
import com.imogen.android.ui.viewer.DetailsSheet
import com.imogen.android.ui.viewer.Viewer
import com.imogen.android.ui.viewer.ViewerMode
import com.imogen.sdk.Asset

/**
 * A set of photographs, however they were chosen: the whole library, one album, one
 * search, the favourites, the trash.
 *
 * Every one of those is a grid you can select in and a viewer you can swipe through, and
 * having five copies of that would mean five places for the selection logic to go subtly
 * wrong. What differs between them is the query and which actions make sense, and both
 * are arguments.
 */
@Composable
fun PhotoBrowser(
    session: Session,
    feed: AssetFeed,
    columns: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    mode: ViewerMode = ViewerMode.Library,
    emptyHeadline: String = "Nothing here yet",
    emptyBody: String = "Photographs will appear here once there are some.",
    onAddToAlbum: ((List<String>) -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
) {
    val state by feed.state.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var openedAt by remember { mutableStateOf<Int?>(null) }
    var details by remember { mutableStateOf<Asset?>(null) }
    val gridState = rememberLazyGridState()

    // Escaping a selection is the commonest thing somebody wants back out of, so it takes
    // the back gesture before the navigation does.
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.items.isEmpty() -> Loading()
            state.error != null && state.items.isEmpty() ->
                ErrorState(state.error!!, onRetry = feed::refresh)
            state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize()) {
                    header?.invoke()
                    EmptyState(emptyHeadline, emptyBody)
                }
            }
            else -> {
                PhotoGrid(
                    session = session,
                    assets = state.items,
                    selection = selection,
                    columns = columns,
                    state = gridState,
                    contentPadding = contentPadding,
                    onOpen = { openedAt = it },
                    onToggleSelection = { asset ->
                        selection = if (asset.id in selection) selection - asset.id
                        else selection + asset.id
                    },
                    onNearEnd = feed::loadMore,
                )
            }
        }

        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = selection.size,
                trash = mode == ViewerMode.Trash,
                onClear = { selection = emptySet() },
                onFavourite = {
                    state.items.filter { it.id in selection }
                        .forEach { feed.setFavorite(it, true) }
                    selection = emptySet()
                },
                onTrash = {
                    feed.trash(selection.toList())
                    selection = emptySet()
                },
                onRestore = {
                    feed.restore(selection.toList())
                    selection = emptySet()
                },
                onAddToAlbum = onAddToAlbum?.let {
                    {
                        it(selection.toList())
                        selection = emptySet()
                    }
                },
            )
        }
    }

    openedAt?.let { index ->
        Viewer(
            session = session,
            assets = state.items,
            initialIndex = index,
            mode = mode,
            onClose = { openedAt = null },
            onFavorite = feed::setFavorite,
            onArchive = feed::setArchived,
            onTrash = { feed.trash(listOf(it.id)) },
            onRestore = { feed.restore(listOf(it.id)) },
            onDetails = { details = it },
        )
    }

    details?.let { asset ->
        DetailsSheet(
            asset = asset,
            onDismiss = { details = null },
            onDescriptionChanged = {
                feed.setDescription(asset, it)
                details = null
            },
        )
    }
}
