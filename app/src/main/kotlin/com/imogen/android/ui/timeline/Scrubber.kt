package com.imogen.android.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
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

    // Read by the gesture, which outlives any one composition.
    val currentDay by rememberUpdatedState(day)
    val currentPosition by rememberUpdatedState(position)
    val currentLayout by rememberUpdatedState(layout)
    val currentOnScrubbing by rememberUpdatedState(onScrubbing)
    val currentOnSeek by rememberUpdatedState(onSeek)

    // A refresh landing mid-drag can hand over a shorter library than the one the thumb
    // was last placed in.
    val labelDay = dragDay.coerceIn(0, layout.index.buckets.lastIndex)

    val thumbHeight = 48.dp
    val thumbPx = with(density) { thumbHeight.toPx() }

    // The strip is only a place to draw: nothing on it takes a touch except the thumb
    // below, so the photographs under the rest of it stay tappable and scrollable.
    Box(
        modifier
            .fillMaxHeight()
            .width(96.dp)
            // Measured on layout rather than when a gesture starts: the year marks are
            // thinned against this, and a rail one pixel tall keeps exactly one of them.
            .onSizeChanged { trackHeight = (it.height - thumbPx).coerceAtLeast(1f) },
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
                //
                // A plain background rather than a Surface: a Surface blocks touches
                // through it, and these are laid out over the photographs until the
                // fade-out ends.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp)
                        .offset(y = trackDp * mark.fraction + thumbHeight / 2 - 10.dp)
                        .graphicsLayer { alpha = railAlpha }
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            RoundedCornerShape(50),
                        ),
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
        // left, from the same offset.
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
            Box(
                Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            ) {
                Text(
                    monthLabel(layout.index.buckets[labelDay].date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        // The thumb is the whole gesture surface, padded out to a 48dp touch target. An
        // earlier rail took the drag across its full height and width, which read well as
        // "the thumb does not have to be hit exactly" and badly as "the last column of
        // photographs cannot be tapped": Compose routes a touch to the topmost sibling
        // under it, and the rail is drawn over the grid. Only the thumb is now that
        // sibling, so the touch either lands on it or falls through to a photograph.
        //
        // The drag is followed by its deltas rather than its position: this box moves with
        // the thumb, so a position in its own coordinates would chase itself. Stepping the
        // fraction directly, clamped, means a finger that ran off the end of the rail turns
        // the thumb round the moment it comes back.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = trackDp * position)
                .size(thumbHeight)
                .semantics {
                    contentDescription = "Scroll through time"
                }
                // Keyed on nothing, with the layout read through the snapshot above: a
                // key that changes cancels the gesture's coroutine, and
                // detectVerticalDragGestures calls neither onDragEnd nor onDragCancel on
                // cancellation. A refresh landing under a held thumb left the timeline
                // scrubbing for good.
                .pointerInput(Unit) {
                    fun seekTo(fraction: Float) {
                        val buckets = currentLayout.index.buckets
                        if (buckets.isEmpty()) return
                        dragFraction = fraction.coerceIn(0f, 1f)
                        val landing = currentLayout.dayAtFraction(dragFraction)
                        val previous = dragDay.coerceIn(0, buckets.lastIndex)
                        // One tick per day crossed would buzz continuously across a
                        // decade; per month is enough to feel the rail moving.
                        if (landing != previous &&
                            monthOf(buckets[landing].date) != monthOf(buckets[previous].date)
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        dragDay = landing
                        currentOnSeek(landing)
                    }

                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            currentOnScrubbing(true)
                            haptics.performHapticFeedback(
                                HapticFeedbackType.GestureThresholdActivate,
                            )
                            // Taken hold of where it is drawn — which mid-spring is not yet
                            // where the grid is — rather than snapped under the finger.
                            dragDay = currentDay.coerceIn(
                                0,
                                currentLayout.index.buckets.lastIndex,
                            )
                            dragFraction = currentPosition
                        },
                        onDragEnd = {
                            dragging = false
                            currentOnScrubbing(false)
                            // Seek once more on release: the grid only fetches days when
                            // the drag stops, so this is the request that actually matters.
                            // Read off the fraction rather than the held day, because that
                            // is what the thumb is drawn from: a refresh landing mid-drag
                            // restretches the rail, and the day it took hold of is no
                            // longer the day it is pointing at.
                            currentOnSeek(currentLayout.dayAtFraction(dragFraction))
                        },
                        onDragCancel = {
                            dragging = false
                            currentOnScrubbing(false)
                        },
                    ) { change, amount ->
                        change.consume()
                        seekTo(dragFraction + amount / trackHeight)
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                tonalElevation = if (dragging) 8.dp else 3.dp,
                color = if (dragging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
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
