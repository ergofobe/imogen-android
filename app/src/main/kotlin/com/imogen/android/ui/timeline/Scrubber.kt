package com.imogen.android.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The fast way down a very long timeline.
 *
 * Fifty thousand photographs is roughly twelve thousand swipes. Nobody is going to find a
 * holiday from 2014 that way, and a scrollbar that only reports position does not help
 * either — what is needed is a control that says *when* the thumb is, before letting go,
 * and that shows what is above and below without being dragged at all.
 *
 * So the rail is marked with years, positioned by how much of the library each one holds
 * rather than by how long ago it was: a year of nine thousand frames takes more rail than a
 * year of two hundred, because that is where its photographs are.
 *
 * The thumb is driven by the segment table, not by a photograph's position in the list.
 * Those are different measurements — a day holding one photograph and a day holding
 * twenty-five are adjacent in the list and nine rows apart on screen — and using the wrong
 * one is what makes a scrubber jump while the content scrolls smoothly.
 */
@Composable
fun Scrubber(
    layout: TimelineLayout,
    /** Where the grid is now, as a day, for drawing the thumb at rest. */
    day: Int,
    onScrubbing: (Boolean) -> Unit,
    onSeek: (day: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.index.isEmpty) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragDay by remember { mutableIntStateOf(0) }
    var trackHeight by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val fraction = if (dragging) dragFraction else layout.fractionOfDay(day)
    // Animated only at rest: following a finger through a spring lags behind it.
    val settled by animateFloatAsState(fraction, label = "scrubber")
    val position = if (dragging) fraction else settled

    val thumbHeight = 48.dp
    val thumbPx = with(density) { thumbHeight.toPx() }

    Box(
        modifier
            .fillMaxHeight()
            .width(96.dp)
            .semantics {
                contentDescription = "Scroll through time"
            }
            // Measured on layout rather than when a gesture starts: the year marks are
            // thinned against this, and a rail one pixel tall keeps exactly one of them.
            .onSizeChanged { trackHeight = (it.height - thumbPx).coerceAtLeast(1f) }
            .pointerInput(layout) {
                fun seekTo(y: Float) {
                    dragFraction = ((y - thumbPx / 2) / trackHeight).coerceIn(0f, 1f)
                    val landing = layout.dayAtFraction(dragFraction)
                    if (landing != dragDay) {
                        // One tick per day crossed would buzz continuously across a
                        // decade; per month is enough to feel the rail moving.
                        if (monthOf(layout.index.buckets[landing].date) !=
                            monthOf(layout.index.buckets[dragDay].date)
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        dragDay = landing
                    }
                    onSeek(landing)
                }

                detectVerticalDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        onScrubbing(true)
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        seekTo(start.y)
                    },
                    onDragEnd = {
                        dragging = false
                        onScrubbing(false)
                        // Seek once more on release: the grid only fetches days when the
                        // drag stops, so this is the request that actually matters.
                        onSeek(dragDay)
                    },
                    onDragCancel = {
                        dragging = false
                        onScrubbing(false)
                    },
                ) { change, amount ->
                    change.consume()
                    seekTo(change.position.y.coerceIn(0f, size.height.toFloat()) + amount * 0)
                }
            },
    ) {
        val trackDp = with(density) { trackHeight.toDp() }
        val marks = remember(layout, trackHeight) {
            layout.yearMarks(with(density) { 34.dp.toPx() }, trackHeight)
        }

        // The years appear only while the rail is held.
        //
        // Marks drawn over the grid at rest sit on top of the photographs, which are the
        // one thing this screen is for — and a permanent row of ticks down the edge reads
        // as chrome rather than as a control. So the rail is a thumb until somebody takes
        // hold of it, and then it is a ruler.
        val railAlpha by animateFloatAsState(
            targetValue = if (dragging) 1f else 0f,
            label = "rail",
        )
        if (railAlpha > 0f) {
            marks.forEach { mark ->
                // Against the edge of the screen, with nothing after them. A tick was
                // pointing at the rail the year is already on, and the thumb passing over
                // a year now and then costs less than a column of punctuation.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp)
                        .offset(y = trackDp * mark.fraction + thumbHeight / 2 - 10.dp)
                        .graphicsLayer { alpha = railAlpha },
                ) {
                    Text(
                        mark.year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // The month under the thumb, drawn on its own rather than beside it: measured
        // against the rail's width it broke "December 2024" across two lines, and measured
        // unbounded inside a row it pushed the thumb off the screen. So it hangs to the
        // left, from the same offset, and the rail stays narrow enough not to swallow taps
        // meant for the photographs.
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = trackDp * position + 4.dp)
                .padding(end = 46.dp)
                .wrapContentWidth(align = Alignment.End, unbounded = true),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 6.dp,
            ) {
                Text(
                    monthLabel(layout.index.buckets[dragDay].date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = if (dragging) 8.dp else 3.dp,
            color = if (dragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = trackDp * position)
                .padding(end = 6.dp)
                .size(width = 32.dp, height = thumbHeight - 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = if (dragging) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private val utc: TimeZone = TimeZone.getTimeZone("UTC")

private fun isoDay() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }

/** The year and month, which is what the bucket key is filed under. */
private fun monthOf(date: String): String = date.take(7)

/** "August 2014" — the granularity somebody actually remembers a photograph by. */
private fun monthLabel(date: String): String {
    val parsed = runCatching { isoDay().parse(date) }.getOrNull() ?: return date
    return SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        .apply { timeZone = utc }
        .format(parsed)
}
