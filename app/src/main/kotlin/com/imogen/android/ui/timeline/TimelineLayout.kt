package com.imogen.android.ui.timeline

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What the grid is drawn with. Enough to estimate a day's height without measuring it. */
data class TimelineMetrics(
    val columns: Int,
    /** One row of cells, in pixels, including the gap under it. */
    val rowHeight: Float,
    /** A day heading, including its padding. */
    val headerHeight: Float,
    /** How much of the timeline is on screen, so the thumb can reach the bottom. */
    val viewportHeight: Float,
)

/** Where a year begins, as a fraction of the scrollable extent. */
data class YearMark(val year: Int, val fraction: Float)

/**
 * Where every day sits, in pixels, before a single photograph is fetched.
 *
 * A scrubber that maps its thumb from a photograph's *position in the list* is a scrubber
 * that jumps: a day holding one photograph and a day holding twenty-five are two list
 * entries apart at the top of each, but nine rows apart on screen. Dragging feels like the
 * timeline is fighting back, because it is — the thumb and the content are measuring
 * different things.
 *
 * So this measures the same thing the grid does. Each day is a heading plus however many
 * rows its count needs, which is exact for a loaded day and a good estimate for one that
 * has not arrived — good enough that the extent is right from the first frame and does not
 * lurch as days load.
 *
 * This is the mobile half of the segment table in the timeline scrubbing design; the web
 * grid computes the same thing and replaces the estimate with a measured height once its
 * justified layout has run. There is nothing to replace here: the grid is uniform squares,
 * so the estimate *is* the measurement.
 */
class TimelineLayout(val index: TimelineIndex, val metrics: TimelineMetrics) {

    /** The top edge of each day. One extra entry holds the total height. */
    private val tops: FloatArray = FloatArray(index.buckets.size + 1).also { tops ->
        var running = 0f
        val columns = max(metrics.columns, 1)
        index.buckets.forEachIndexed { day, bucket ->
            tops[day] = running
            val rows = ceil(bucket.count.toFloat() / columns).toInt()
            running += metrics.headerHeight + rows * metrics.rowHeight
        }
        tops[index.buckets.size] = running
    }

    val totalHeight: Float get() = tops.last()

    /**
     * How far the content can actually scroll. The last screenful is not scrolled past, so
     * a thumb driven by [totalHeight] alone stops short of the bottom.
     */
    val scrollableHeight: Float get() = max(totalHeight - metrics.viewportHeight, 1f)

    fun topOfDay(day: Int): Float {
        if (index.buckets.isEmpty()) return 0f
        return tops[day.coerceIn(0, index.buckets.lastIndex)]
    }

    fun dayAtOffset(offset: Float): Int {
        if (index.buckets.isEmpty()) return 0
        var low = 0
        var high = index.buckets.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (tops[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }

    /** Where a day sits on the rail. */
    fun fractionOfDay(day: Int): Float {
        if (index.buckets.isEmpty()) return 0f
        return min(max(topOfDay(day) / scrollableHeight, 0f), 1f)
    }

    /** What the rail is pointing at. */
    fun dayAtFraction(fraction: Float): Int {
        if (index.buckets.isEmpty()) return 0
        return dayAtOffset(min(max(fraction, 0f), 1f) * scrollableHeight)
    }

    /**
     * Where each year starts, for labelling the rail.
     *
     * Proportional to height rather than to time, which is the point: a year of nine
     * thousand photographs takes more rail than a year of two hundred, so the labels sit
     * where that year's photographs actually are.
     */
    fun yearMarks(): List<YearMark> {
        val marks = mutableListOf<YearMark>()
        var seen: Int? = null
        index.buckets.forEachIndexed { day, bucket ->
            val year = bucket.date.take(4).toIntOrNull() ?: return@forEachIndexed
            if (year != seen) {
                marks += YearMark(year, fractionOfDay(day))
                seen = year
            }
        }
        return marks
    }

    /**
     * Year marks thinned so their labels do not overlap on a rail this tall.
     *
     * A twenty-year library has twenty labels and a phone has room for about eight, so the
     * rest are dropped rather than drawn on top of each other. The first is always kept,
     * because a rail whose top is unlabelled reads as broken.
     */
    fun yearMarks(minimumGapPx: Float, railHeightPx: Float): List<YearMark> {
        val all = yearMarks()
        if (railHeightPx <= 0f || minimumGapPx <= 0f) return all

        val kept = mutableListOf<YearMark>()
        var last = Float.NEGATIVE_INFINITY
        for (mark in all) {
            val position = mark.fraction * railHeightPx
            if (position - last >= minimumGapPx) {
                kept += mark
                last = position
            }
        }
        return kept
    }
}
