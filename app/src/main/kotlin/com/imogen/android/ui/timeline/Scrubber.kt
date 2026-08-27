package com.imogen.android.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The fast way down a very long timeline.
 *
 * Fifty thousand photographs is roughly twelve thousand swipes. Nobody is going to find
 * a holiday from 2014 that way, and a scrollbar that only reports position does not help
 * either — what is needed is a control that says *when* the thumb is, before letting go.
 *
 * So dragging shows the month under the thumb and jumps as it moves, and the grid does
 * not fetch anything while the drag is happening: a flick from top to bottom would
 * otherwise ask the server for four hundred days it passes through and wants none of.
 */
@Composable
fun Scrubber(
    index: TimelineIndex,
    /** Where the grid is now, as an entry index, for drawing the thumb at rest. */
    firstVisibleEntry: Int,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (entry: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (index.entryCount <= 0) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeight by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    val restFraction = firstVisibleEntry.toFloat() / index.entryCount.coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else restFraction

    Box(modifier.fillMaxHeight().width(48.dp)) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(48.dp)
                .align(Alignment.CenterEnd)
                .pointerInput(index) {
                    trackHeight = size.height.toFloat()
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            onScrubbing(true)
                            dragFraction = (start.y / trackHeight).coerceIn(0f, 1f)
                            onSeek(index.entryAtFraction(dragFraction))
                        },
                        onDragEnd = {
                            dragging = false
                            onScrubbing(false)
                        },
                        onDragCancel = {
                            dragging = false
                            onScrubbing(false)
                        },
                    ) { change, amount ->
                        change.consume()
                        dragFraction = (dragFraction + amount / trackHeight).coerceIn(0f, 1f)
                        onSeek(index.entryAtFraction(dragFraction))
                    }
                },
        )

        val thumbOffset = with(density) {
            ((trackHeight - THUMB_PX) * fraction).coerceAtLeast(0f).toDp()
        }

        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = 4.dp,
            color = if (dragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp)
                .padding(top = thumbOffset)
                .size(width = 32.dp, height = 40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Scroll through time",
                    tint = if (dragging) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 46.dp)
                .padding(top = thumbOffset),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        monthLabel(index.dateOf(index.entryAtFraction(fraction))),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/** Roughly the thumb's height in pixels, so it does not run off the end of the track. */
private const val THUMB_PX = 120f

private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** "August 2014" — the granularity somebody actually remembers a photograph by. */
private fun monthLabel(date: String): String {
    val parsed = runCatching { isoDate.parse(date) }.getOrNull() ?: return date
    return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(parsed)
}
