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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imogen.android.data.Session
import com.imogen.android.ui.common.ConfirmTrashDialog
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.Selection
import com.imogen.android.ui.common.SelectionBar
import com.imogen.android.ui.common.ErrorState
import com.imogen.android.ui.common.Loading
import com.imogen.android.ui.common.countMatching
import com.imogen.android.ui.common.resolvedCount
import com.imogen.android.ui.viewer.DetailsSheet
import com.imogen.android.ui.viewer.Viewer
import com.imogen.android.ui.viewer.ViewerMode
import com.imogen.android.ui.viewer.asViewerItem
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetSelection

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
    onAddToAlbum: ((AssetSelection) -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
) {
    val state by feed.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<Selection>(Selection.Ids()) }
    /** How many photographs a "select all" holds, once the server has said. */
    var matchedTotal by remember { mutableStateOf<Long?>(null) }
    var confirmingTrash by remember { mutableStateOf<Long?>(null) }
    var openedAt by remember { mutableStateOf<Int?>(null) }
    var details by remember { mutableStateOf<Asset?>(null) }
    val gridState = rememberLazyGridState()

    val selectedCount = selection.resolvedCount(matchedTotal)

    // Escaping a selection is the commonest thing somebody wants back out of, so it takes
    // the back gesture before the navigation does.
    BackHandler(enabled = !selection.isEmpty) { selection = Selection.Ids() }

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
                    onToggleSelection = { asset -> selection = selection.toggled(asset.id) },
                    onNearEnd = feed::loadMore,
                )
            }
        }

        AnimatedVisibility(
            visible = !selection.isEmpty,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = selectedCount,
                trash = mode == ViewerMode.Trash,
                onClear = { selection = Selection.Ids() },
                // One PATCH per photograph, so this is offered for a list and withheld for
                // a query rather than quietly favouriting the page that happens to be here.
                onFavourite = (selection as? Selection.Ids)?.let { ids ->
                    {
                        state.items.filter { it.id in ids.ids }
                            .forEach { feed.setFavorite(it, true) }
                        selection = Selection.Ids()
                    }
                },
                onTrash = {
                    when (val current = selection) {
                        is Selection.Ids -> {
                            feed.trash(current.asAssetSelection())
                            selection = Selection.Ids()
                        }
                        // Never without a number, and never a number this screen guessed.
                        is Selection.Matching -> selectedCount?.let { confirmingTrash = it }
                    }
                },
                // Putting photographs back is not destructive, so it needs no confirming.
                onRestore = {
                    feed.restore(selection.asAssetSelection())
                    selection = Selection.Ids()
                },
                onAddToAlbum = onAddToAlbum?.let {
                    {
                        it(selection.asAssetSelection())
                        selection = Selection.Ids()
                    }
                },
                onSelectAll = {
                    selection = Selection.Matching(feed.filter)
                    matchedTotal = null
                    scope.launch {
                        runCatching { countMatching(session, feed.filter) }
                            .onSuccess { matchedTotal = it }
                            // Without a count there is nothing honest to offer, so the
                            // selection goes back to being the ones actually ticked.
                            .onFailure { selection = Selection.Ids() }
                    }
                },
            )
        }
    }

    confirmingTrash?.let { count ->
        ConfirmTrashDialog(
            count = count,
            onDismiss = { confirmingTrash = null },
            onConfirm = {
                feed.trash(selection.asAssetSelection())
                selection = Selection.Ids()
                confirmingTrash = null
            },
        )
    }

    openedAt?.let { index ->
        val items = remember(state.items) { state.items.map { it.asViewerItem() } }
        var currentId by remember { mutableStateOf(state.items.getOrNull(index)?.id) }

        Viewer(
            session = session,
            items = items,
            initialIndex = index,
            // Already fetched whole, and read from the feed rather than kept aside so an
            // edit shows in the chrome the moment the feed applies it.
            current = state.items.firstOrNull { it.id == currentId },
            mode = mode,
            onPage = { currentId = it },
            onClose = { openedAt = null },
            onFavorite = feed::setFavorite,
            onArchive = feed::setArchived,
            onTrash = { feed.trash(AssetSelection(assetIds = listOf(it.id))) },
            onRestore = { feed.restore(AssetSelection(assetIds = listOf(it.id))) },
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
