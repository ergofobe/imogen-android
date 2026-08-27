package com.imogen.android.ui.timeline

import com.imogen.sdk.TimelineBucket

/**
 * The shape of the whole library, without any of it loaded.
 *
 * A library of fifty thousand photographs cannot be paged into a grid a hundred at a time
 * and still be scrollable: reaching 2011 means four hundred round trips, and the scrollbar
 * lies about how much there is until the last one lands.
 *
 * The server already answers a cheaper question. `/assets/timeline` returns one row per
 * day with a count — a few thousand rows for a lifetime of photographs, one request, no
 * images. From that the exact number of cells is known before anything is fetched, which
 * is what makes the grid the right length from the first frame and makes jumping to a
 * date arithmetic rather than a search.
 *
 * Entries are laid out as: one header per day, then that day's photographs.
 *
 * ```
 *   0  header  2026-08-27
 *   1  photo
 *   2  photo
 *   3  header  2026-08-26
 *   4  photo
 * ```
 *
 * Positions are computed by binary search over a prefix sum rather than by materialising
 * that list: fifty thousand entry objects is memory spent to avoid a handful of
 * comparisons.
 */
class TimelineIndex(val buckets: List<TimelineBucket>) {

    /** `starts[b]` is the entry index of bucket b's header. One extra holds the total. */
    private val starts = IntArray(buckets.size + 1).also { starts ->
        var running = 0
        buckets.forEachIndexed { index, bucket ->
            starts[index] = running
            running += 1 + bucket.count.toInt()
        }
        starts[buckets.size] = running
    }

    val entryCount: Int get() = starts[buckets.size]

    val photoCount: Long get() = buckets.sumOf { it.count }

    val isEmpty: Boolean get() = buckets.isEmpty()

    /** Which day an entry belongs to. */
    fun bucketOf(entry: Int): Int {
        var low = 0
        var high = buckets.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= entry) low = middle else high = middle - 1
        }
        return low
    }

    fun isHeader(entry: Int): Boolean = starts[bucketOf(entry)] == entry

    /** Where in its day a photograph sits, or -1 for a header. */
    fun offsetInBucket(entry: Int): Int {
        val bucket = bucketOf(entry)
        val offset = entry - starts[bucket] - 1
        return offset
    }

    fun entryOfBucket(bucket: Int): Int = starts[bucket.coerceIn(0, buckets.size - 1)]

    fun dateOf(entry: Int): String = buckets[bucketOf(entry)].date

    /**
     * The bucket a fraction of the way down, for a scrubber.
     *
     * Weighted by photographs rather than by days, so dragging halfway down lands halfway
     * through the library — not halfway through the list of dates, which on a library with
     * one holiday and ten years of Tuesdays is somewhere quite different.
     */
    fun entryAtFraction(fraction: Float): Int =
        (entryCount * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, (entryCount - 1).coerceAtLeast(0))

    /**
     * Removes one photograph from a day's count, and the day itself when it empties.
     *
     * Trashing something has to shorten the grid immediately. Refetching the buckets would
     * be a round trip in the middle of a gesture, and would put the scroll position
     * somewhere else while somebody was looking at it.
     */
    fun withoutPhotos(dateCounts: Map<String, Int>): TimelineIndex {
        if (dateCounts.isEmpty()) return this
        val updated = buckets.mapNotNull { bucket ->
            val removed = dateCounts[bucket.date] ?: return@mapNotNull bucket
            val remaining = bucket.count - removed
            if (remaining > 0) TimelineBucket(bucket.date, remaining) else null
        }
        return TimelineIndex(updated)
    }
}

/** The instants bounding one UTC day, as the API wants them. */
fun dayBounds(date: String): Pair<String, String> =
    "${date}T00:00:00.000Z" to "${date}T23:59:59.999Z"
