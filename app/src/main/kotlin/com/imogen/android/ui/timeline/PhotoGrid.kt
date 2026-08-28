package com.imogen.android.ui.timeline

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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imogen.android.data.Session
import com.imogen.android.ui.common.AssetImage
import com.imogen.android.ui.common.Selection
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The grid, grouped by the day the photographs were taken.
 *
 * Square cells rather than the web interface's justified rows. A justified layout is
 * lovely on a wide screen and wasteful on a narrow one, where every row of two or three
 * photographs leaves a ragged edge and the eye has nothing to follow down the page.
 *
 * Column count comes from the width in dp rather than from a size class, so a phone in
 * landscape and a small tablet — which are nearly the same number of millimetres — get
 * nearly the same grid.
 */
@Composable
fun PhotoGrid(
    session: Session,
    assets: List<Asset>,
    selection: Selection,
    /**
     * Whether a selection is under way, decided by whoever owns it rather than derived
     * here. A by-query selection with everything unticked is empty in a way this cannot
     * see, and a grid that disagreed with its own selection bar left every tile ticking
     * instead of opening with no visible way back out.
     */
    selecting: Boolean,
    onOpen: (Int) -> Unit,
    onToggleSelection: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    columns: Int,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onNearEnd: () -> Unit = {},
) {

    // Grouping is recomputed only when the list actually changes, not on every frame of
    // a scroll — this walks every asset, and a timeline is not a short list.
    val days = remember(assets) { groupByDay(assets) }

    // "Near the end" rather than "at the end": asking for the next page when the last row
    // is already on screen means an empty gap while it loads.
    val nearEnd by remember(state, assets.size) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - columns * 4
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) onNearEnd()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        days.forEach { (day, photos) ->
            item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = day,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
                )
            }

            items(photos, key = { it.id }) { asset ->
                PhotoTile(
                    session = session,
                    asset = asset,
                    selected = selection.holds(asset.id),
                    selecting = selecting,
                    onClick = {
                        if (selecting) onToggleSelection(asset) else onOpen(assets.indexOf(asset))
                    },
                    onLongClick = { onToggleSelection(asset) },
                )
            }
        }
    }
}

@Composable
private fun PhotoTile(
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
        AssetImage(
            session = session,
            asset = asset,
            variant = "thumbnail",
            modifier = Modifier.fillMaxSize(),
        )

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
            // The dimming is the point: it says which photographs are chosen from across
            // the room, where a small tick in a corner says nothing at all.
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

/**
 * Day headings, in the order the assets already have.
 *
 * A `LinkedHashMap` rather than sorting: the server returns the timeline in order, and
 * re-deriving that order from parsed dates would be slower and would disagree with it
 * whenever two photographs share a second.
 */
private fun groupByDay(assets: List<Asset>): Map<String, List<Asset>> {
    val grouped = LinkedHashMap<String, MutableList<Asset>>()
    for (asset in assets) {
        grouped.getOrPut(timelineDayLabel(asset.capturedAt)) { mutableListOf() }.add(asset)
    }
    return grouped
}

/**
 * Everything here works in UTC.
 *
 * The day strings being rendered are the server's bucket keys, and the server groups by the
 * UTC calendar date. Parse one in local time and the reading is still right; compare it
 * against a local "today" and it is not, so a photograph taken this evening in California
 * is filed under a date that will not be today for another seven hours.
 */
private val utc: TimeZone = TimeZone.getTimeZone("UTC")

private fun isoDay(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }

/**
 * Rebuilt when the locale changes rather than cached once.
 *
 * A formatter held in a `val` freezes whatever language the app started in, and somebody
 * who switches their phone to French then sees English day names until they force-stop it.
 */
private fun dayFormat(pattern: String): SimpleDateFormat =
    SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = utc }

/**
 * The heading for one day. The year is dropped for this year, because a timeline of
 * mostly-recent photographs repeating the same four digits down the page is noise.
 */
fun timelineDayLabel(capturedAt: String, now: Date = Date()): String {
    val date = runCatching { isoDay().parse(capturedAt.take(10)) }.getOrNull()
        ?: return capturedAt.take(10)

    val calendar = Calendar.getInstance(utc).apply { time = date }
    val today = Calendar.getInstance(utc).apply { time = now }

    if (calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
        val days = calendar.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
        if (days == 0) return "Today"
        if (days == -1) return "Yesterday"
        return dayFormat("EEEE d MMMM").format(date)
    }
    return dayFormat("d MMMM yyyy").format(date)
}

/** How many columns fit, given the width somebody actually has. */
fun columnsFor(widthDp: Int, dense: Boolean = false): Int {
    val target = if (dense) 96 else 116
    return (widthDp / target).coerceIn(3, 10)
}
