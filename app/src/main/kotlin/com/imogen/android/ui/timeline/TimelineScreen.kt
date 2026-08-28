package com.imogen.android.ui.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imogen.android.data.Session
import com.imogen.android.ui.common.AssetImage
import com.imogen.android.ui.common.ConfirmTrashDialog
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.ErrorState
import com.imogen.android.ui.common.Loading
import com.imogen.android.ui.common.Selection
import com.imogen.android.ui.common.SelectionBar
import com.imogen.android.ui.common.countMatching
import com.imogen.android.ui.common.resolvedCount
import com.imogen.android.ui.common.ticked
import com.imogen.android.ui.viewer.DetailsSheet
import com.imogen.android.ui.viewer.Viewer
import com.imogen.android.ui.viewer.ViewerMode
import com.imogen.android.ui.viewer.asViewerItem
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetSelection
import com.imogen.sdk.AssetType
import com.imogen.sdk.TimelineTile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The library, in one grid, however large it is.
 *
 * The grid is built from the day index rather than from the photographs, so it is the
 * right length immediately and every cell has a fixed place from the first frame. Cells
 * whose day has not been fetched draw a plain rectangle; the day arrives and they fill
 * in. Nothing reflows, because nothing changes size.
 *
 * That is what makes the scrubber honest. A grid that grows as pages arrive has a
 * scrollbar that means something different every second.
 */
@Composable
fun TimelineScreen(
    session: Session,
    model: TimelineViewModel,
    columns: Int,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    emptyHeadline: String = "Your library is empty",
    emptyBody: String = "Turn on backup, or add photographs from another device — they " +
        "will appear here, newest first.",
    onAddToAlbum: ((AssetSelection) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Keyed on the model. This composable is re-invoked in the same slot with a different
    // one — one person's photographs, then another's — and a selection that survived that
    // would be a filter aimed at the library somebody has just left.
    var selection by remember(model) { mutableStateOf<Selection>(Selection.Ids()) }
    /** How many photographs a "select all" holds, once the server has said. */
    var matchedTotal by remember(model) { mutableStateOf<Long?>(null) }
    /** Which count is the current one, so a slow answer cannot overwrite a newer question. */
    var countRequest by remember(model) { mutableIntStateOf(0) }
    var confirmingTrash by remember(model) { mutableStateOf<Long?>(null) }
    /** The day a photograph was opened from, and the photograph. */
    var opened by remember(model) { mutableStateOf<Pair<String, TimelineTile>?>(null) }
    /**
     * Whole assets fetched for the viewer, by id.
     *
     * A tile carries no exif, no filename and no size, so the photograph in front of
     * somebody is fetched entire — and kept, so paging back and forth through an afternoon
     * asks for each one once rather than once per visit. Emptied when the viewer closes.
     */
    val fetched = remember(model) { mutableStateMapOf<String, Asset>() }
    var details by remember(model) { mutableStateOf<Asset?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    /** The day at the top of the viewport, which is what the thumb draws itself against. */
    var topDay by remember { mutableIntStateOf(0) }

    val selectedCount = selection.resolvedCount(matchedTotal)
    // A by-query selection with everything unticked is empty in every sense that matters,
    // and offering to trash nought photographs is not an offer.
    val selecting = !selection.isEmpty && selectedCount != 0L

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        // Cleared even if the wait is cut short. `showSnackbar` suspends for as long as the
        // snackbar is up, and leaving the screen inside that window used to leave the notice
        // sitting in a retained view model — so it fired again on the way back, and again.
        try {
            snackbar.showSnackbar(notice)
        } finally {
            model.clearNotice()
        }
    }

    BackHandler(enabled = selecting) { selection = Selection.Ids() }

    // Only while there is nothing to show. A reload with an index already in hand is what
    // follows every bulk action and every failed edit, and blanking the screen for it takes
    // the grid, the scrubber and — because they are composed below — the open photograph
    // and its details sheet with it. A failed archive would put somebody back at the
    // photograph they opened, pages from where they had swiped to.
    if (state.loading && state.index.isEmpty) {
        Loading(modifier)
        return
    }
    state.error?.let {
        ErrorState(it, modifier, onRetry = model::refresh)
        return
    }
    if (state.index.isEmpty) {
        EmptyState(emptyHeadline, emptyBody, modifier)
        return
    }

    val index = state.index
    val density = LocalDensity.current

    // Measured from the layout rather than read off the grid's `layoutInfo`, which changes
    // on every scroll frame — reading that during composition recomposes the whole screen
    // sixty times a second to learn a number that only changes on rotation.
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // What the grid is actually drawn with, so the rail measures the same thing it does.
    val layout = remember(index, columns, viewport) {
        val cell = if (columns > 0 && viewport.width > 0) {
            viewport.width.toFloat() / columns
        } else {
            with(density) { 116.dp.toPx() }
        }
        TimelineLayout(
            index,
            TimelineMetrics(
                columns = columns,
                rowHeight = cell + with(density) { 2.dp.toPx() },
                headerHeight = with(density) { 44.dp.toPx() },
                viewportHeight = viewport.height.toFloat(),
            ),
        )
    }

    // The topmost visible cell, as a day. Read from the grid rather than tracked by hand
    // so an ordinary scroll moves the thumb too, not just a drag on the rail.
    val firstVisibleDay by remember(grid, index) {
        derivedStateOf { index.bucketOf(grid.firstVisibleItemIndex) }
    }
    LaunchedEffect(firstVisibleDay) {
        if (!scrubbing) topDay = firstVisibleDay
    }

    /**
     * Days are fetched from what the grid says is on screen, rather than from each cell as
     * it composes: one watcher for the whole screen instead of a side effect in every one
     * of fifty thousand items, and it can see the range as a range and read ahead.
     *
     * Nothing is fetched during a scrub. A flick from one end of a decade to the other
     * passes through hundreds of days it has no intention of stopping at.
     */
    LaunchedEffect(grid, index, scrubbing) {
        if (scrubbing) return@LaunchedEffect
        snapshotFlow {
            val visible = grid.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val last = visible.lastOrNull()?.index ?: first
            index.bucketOf(first) to index.bucketOf(last)
        }
            .distinctUntilChanged()
            .collect { (firstBucket, lastBucket) ->
                // One day either side, so scrolling on lands on photographs rather than
                // on a screen of placeholders that fill in a moment later.
                val from = (firstBucket - 1).coerceAtLeast(0)
                val to = (lastBucket + 1).coerceAtMost(index.buckets.lastIndex)
                for (bucket in from..to) model.ensureLoaded(index.buckets[bucket].date)
            }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = index.entryCount,
                // Keyed by position rather than by asset id: an id is not known for a day
                // that has not loaded, and a key that changes on arrival would make every
                // cell in that day a new item and throw away its place.
                key = { it },
                span = { entry ->
                    if (index.isHeader(entry)) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
            ) { entry ->
                val bucket = index.bucketOf(entry)
                val date = index.buckets[bucket].date

                if (index.isHeader(entry)) {
                    DayHeading(date, index.buckets[bucket].count)
                } else {
                    val tile = state.days[date]?.getOrNull(index.offsetInBucket(entry))
                    if (tile == null) {
                        PendingTile()
                    } else {
                        PhotoTile(
                            session = session,
                            tile = tile,
                            selected = selection.holds(tile.id),
                            selecting = selecting,
                            onClick = {
                                if (selecting) {
                                    selection = selection.toggled(tile.id)
                                } else {
                                    // The bucket the server filed it under, carried rather
                                    // than re-derived: the viewer pages through this day.
                                    opened = date to tile
                                }
                            },
                            onLongClick = { selection = selection.ticked(tile.id, selecting) },
                        )
                    }
                }
            }
        }

        Scrubber(
            layout = layout,
            day = topDay,
            onScrubbing = { scrubbing = it },
            onSeek = { day ->
                topDay = day
                scope.launch { grid.scrollToItem(index.entryOfBucket(day)) }
                // The design's rule: suspend fetching while the rail is moving and resume
                // shortly after it settles, so a drag across fifteen years issues a couple
                // of requests rather than forty.
                settle?.cancel()
                settle = scope.launch {
                    delay(150)
                    model.ensureLoaded(index.buckets[day].date)
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(contentPadding),
        )

        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = selectedCount,
                onClear = { selection = Selection.Ids() },
                // There is no bulk favourite in the API — it is one PATCH per photograph —
                // so this is offered for a list and withheld for a query rather than
                // quietly doing it to the few hundred that happen to be loaded.
                onFavourite = (selection as? Selection.Ids)?.let { ids ->
                    {
                        ids.ids.forEach { model.setFavorite(it, true) }
                        selection = Selection.Ids()
                    }
                },
                onTrash = {
                    when (val current = selection) {
                        is Selection.Ids -> {
                            model.trash(current.asAssetSelection())
                            selection = Selection.Ids()
                        }
                        // Never without a number, and never a number this screen guessed.
                        is Selection.Matching -> selectedCount?.let { confirmingTrash = it }
                    }
                },
                onAddToAlbum = onAddToAlbum?.let {
                    {
                        it(selection.asAssetSelection())
                        selection = Selection.Ids()
                    }
                },
                onSelectAll = {
                    selection = Selection.Matching(model.filter)
                    matchedTotal = null
                    // Which count this is. Identity of the selection object cannot answer
                    // that: unticking one photograph replaces it, so a guard comparing
                    // instances would decline to clean up after its own failure and leave
                    // the bar counting for ever.
                    val request = ++countRequest
                    scope.launch {
                        runCatching { countMatching(session, model.filter) }
                            .onSuccess { if (request == countRequest) matchedTotal = it }
                            // Without a count there is nothing honest to offer, so a
                            // by-query selection goes away — and says so, rather than
                            // vanishing from under the finger that asked for it. A
                            // selection ticked by hand since is somebody else's and stays.
                            .onFailure {
                                if (request == countRequest && selection is Selection.Matching) {
                                    selection = Selection.Ids()
                                    snackbar.showSnackbar("Could not count that selection")
                                }
                            }
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
                model.trash(selection.asAssetSelection())
                selection = Selection.Ids()
                confirmingTrash = null
            },
        )
    }

    opened?.let { (date, tile) ->
        // The viewer pages through the day it was opened from. Handing it fifty thousand
        // photographs is not possible — most of them are not loaded — and a day is the
        // unit somebody is looking through anyway.
        val day = remember(state.days, date) {
            state.days[date].orEmpty().map { it.asViewerItem() }
        }

        var currentId by remember(tile.id) { mutableStateOf(tile.id) }
        // Not cached: a photograph whose details failed once should try again the next
        // time somebody swipes back to it, rather than being permanently actionless.
        var failedId by remember(tile.id) { mutableStateOf<String?>(null) }
        val current = fetched[currentId]

        LaunchedEffect(currentId) {
            if (currentId !in fetched) {
                runCatching { session.client.assets.get(currentId) }
                    .onSuccess { fetched[currentId] = it }
                    .onFailure { failedId = currentId }
            }
        }

        Viewer(
            session = session,
            items = day,
            initialIndex = day.indexOfFirst { it.id == tile.id }.coerceAtLeast(0),
            current = current,
            detailsUnavailable = failedId == currentId,
            mode = ViewerMode.Library,
            onPage = { currentId = it },
            onClose = {
                opened = null
                fetched.clear()
            },
            // The heart is drawn from the tile, which is what the view model puts back if
            // the server refuses — so there is one optimistic copy of this, not two that
            // can disagree.
            onFavorite = { asset, favorite -> model.setFavorite(asset.id, favorite) },
            onArchive = { asset, archived ->
                model.setArchived(asset.id, archived)
                fetched[asset.id] = asset.copy(archived = archived)
            },
            onTrash = { model.trash(AssetSelection(assetIds = listOf(it.id))) },
            onRestore = {},
            onDetails = { details = it },
        )
    }

    details?.let { asset ->
        DetailsSheet(
            asset = asset,
            onDismiss = { details = null },
            onDescriptionChanged = { description ->
                model.setDescription(asset.id, description)
                // So reopening the sheet shows what was just typed rather than what the
                // server said before it was.
                fetched[asset.id] = asset.copy(description = description)
                details = null
            },
        )
    }
}

@Composable
private fun DayHeading(date: String, count: Long) {
    Text(
        text = timelineDayLabel(date) + "  ·  $count",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * A cell whose day has not arrived.
 *
 * Deliberately flat and unanimated. A shimmer across four hundred cells is a lot of
 * frames spent telling somebody that something they can already see is loading.
 */
@Composable
private fun PendingTile() {
    Box(
        Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun PhotoTile(
    session: Session,
    tile: TimelineTile,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(RoundedCornerShape(2.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // A tile carries no filename to read out, so the cell says what it is and the play
        // badge below says whether it moves.
        AssetImage(
            session = session,
            assetId = tile.id,
            placeholderColor = tile.placeholderColor,
            variant = "thumbnail",
            modifier = Modifier.fillMaxSize(),
            contentDescription = "Photograph",
        )

        if (tile.type == AssetType.VIDEO) {
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (tile.favorite && !selecting) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favourite",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp),
            )
        }
        if (selecting) {
            if (selected) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape),
            )
        }
    }
}
