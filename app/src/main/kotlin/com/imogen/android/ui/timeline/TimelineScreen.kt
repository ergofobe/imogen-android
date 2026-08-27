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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.ErrorState
import com.imogen.android.ui.common.Loading
import com.imogen.android.ui.common.SelectionBar
import com.imogen.android.ui.viewer.DetailsSheet
import com.imogen.android.ui.viewer.Viewer
import com.imogen.android.ui.viewer.ViewerMode
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetType
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onAddToAlbum: ((List<String>) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var selection by remember { mutableStateOf(emptySet<String>()) }
    var opened by remember { mutableStateOf<Asset?>(null) }
    var details by remember { mutableStateOf<Asset?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var settle by remember { mutableStateOf<Job?>(null) }
    /** The day at the top of the viewport, which is what the thumb draws itself against. */
    var topDay by remember { mutableIntStateOf(0) }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    if (state.loading) {
        Loading(modifier)
        return
    }
    state.error?.let {
        ErrorState(it, modifier, onRetry = model::refresh)
        return
    }
    if (state.index.isEmpty) {
        EmptyState(
            "Your library is empty",
            "Turn on backup, or add photographs from another device — they will appear " +
                "here, newest first.",
            modifier,
        )
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
                    val asset = state.days[date]?.getOrNull(index.offsetInBucket(entry))
                    if (asset == null) {
                        PendingTile()
                    } else {
                        TimelineTile(
                            session = session,
                            asset = asset,
                            selected = asset.id in selection,
                            selecting = selection.isNotEmpty(),
                            onClick = {
                                if (selection.isNotEmpty()) {
                                    selection = selection.toggled(asset.id)
                                } else {
                                    opened = asset
                                }
                            },
                            onLongClick = { selection = selection.toggled(asset.id) },
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
            visible = selection.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                onFavourite = {
                    selectedAssets(state, selection).forEach { model.setFavorite(it, true) }
                    selection = emptySet()
                },
                onTrash = {
                    model.trash(selectedAssets(state, selection))
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

    opened?.let { asset ->
        // The viewer pages through the day it was opened from. Handing it fifty thousand
        // assets is not possible — most of them are not loaded — and a day is the unit
        // somebody is looking through anyway.
        val day = state.days[asset.capturedAt.take(10)].orEmpty()
        Viewer(
            session = session,
            assets = day,
            initialIndex = day.indexOfFirst { it.id == asset.id }.coerceAtLeast(0),
            mode = ViewerMode.Library,
            onClose = { opened = null },
            onFavorite = model::setFavorite,
            onArchive = model::setArchived,
            onTrash = { model.trash(listOf(it)) },
            onRestore = {},
            onDetails = { details = it },
        )
    }

    details?.let { asset ->
        DetailsSheet(
            asset = asset,
            onDismiss = { details = null },
            onDescriptionChanged = {
                model.setDescription(asset, it)
                details = null
            },
        )
    }
}

private fun Set<String>.toggled(id: String): Set<String> =
    if (id in this) this - id else this + id

private fun selectedAssets(state: TimelineState, selection: Set<String>): List<Asset> =
    state.days.values.flatten().filter { it.id in selection }

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
private fun TimelineTile(
    session: Session,
    asset: Asset,
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
        AssetImage(session, asset, "thumbnail", Modifier.fillMaxSize())

        if (asset.type == AssetType.VIDEO) {
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (asset.favorite && !selecting) {
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
