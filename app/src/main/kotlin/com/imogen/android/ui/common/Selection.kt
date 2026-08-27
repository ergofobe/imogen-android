package com.imogen.android.ui.common

import com.imogen.android.data.Session
import com.imogen.sdk.AssetFilter
import com.imogen.sdk.AssetQuery
import com.imogen.sdk.AssetSelection
import com.imogen.sdk.TimelineQuery

/**
 * What a selection acts on.
 *
 * Ticking photographs one at a time makes [Ids]. "Select all" makes [Matching] — the query
 * somebody was already looking at, minus whatever they unticked — because a hundred
 * thousand uuids do not belong in a request body, and the server can resolve the filter
 * itself in one statement.
 */
sealed interface Selection {

    val isEmpty: Boolean

    fun holds(assetId: String): Boolean

    fun toggled(assetId: String): Selection

    fun asAssetSelection(): AssetSelection

    data class Ids(val ids: Set<String> = emptySet()) : Selection {
        override val isEmpty: Boolean get() = ids.isEmpty()

        override fun holds(assetId: String): Boolean = assetId in ids

        override fun toggled(assetId: String): Selection =
            Ids(if (assetId in ids) ids - assetId else ids + assetId)

        override fun asAssetSelection(): AssetSelection = AssetSelection(assetIds = ids.toList())
    }

    /**
     * Everything [filter] matches, apart from [except].
     *
     * The server caps `except` at ten thousand — `validate()` does not check it, so past
     * that the refusal arrives as a 400 rather than as an exception here. It is not a cap
     * this reaches in practice: unticking ten thousand photographs one at a time is not
     * what anybody is doing.
     */
    data class Matching(
        val filter: AssetFilter,
        val except: Set<String> = emptySet(),
    ) : Selection {
        override val isEmpty: Boolean get() = false

        override fun holds(assetId: String): Boolean = assetId !in except

        override fun toggled(assetId: String): Selection =
            Matching(filter, if (assetId in except) except - assetId else except + assetId)

        override fun asAssetSelection(): AssetSelection = AssetSelection(
            query = filter,
            except = except.takeIf { it.isNotEmpty() }?.toList(),
        )
    }
}

/**
 * How many photographs a selection holds, or null while that is still being asked.
 *
 * A by-query selection knows its own subtractions but not its total, so [matchedTotal] is
 * whatever the server answered for the filter.
 */
fun Selection.resolvedCount(matchedTotal: Long?): Long? = when (this) {
    is Selection.Ids -> ids.size.toLong()
    is Selection.Matching -> matchedTotal?.let { (it - except.size).coerceAtLeast(0) }
}

/** The same constraints as an [AssetQuery], without the paging or the ordering. */
fun AssetQuery.toFilter(): AssetFilter = AssetFilter(
    q = q,
    type = type,
    albumId = albumId,
    personId = personId,
    favorite = favorite,
    archived = archived,
    trashed = trashed,
    takenAfter = takenAfter,
    takenBefore = takenBefore,
    bbox = bbox,
)

/**
 * How many photographs a filter matches, from the server's own day counts.
 *
 * Asked before a selection acts, rather than inferred from whatever is on screen. "Move
 * 12,431 photographs to the trash" is a decision somebody can weigh; "move all photos" is
 * one they cannot, and it is the one that gets made by accident.
 */
suspend fun countMatching(session: Session, filter: AssetFilter): Long =
    session.client.assets.timeline(TimelineQuery(filter)).buckets.sumOf { it.count }

/**
 * What actually happened, for a bulk action somebody agreed to a number for.
 *
 * The server's own count rather than the one in the dialog: a library moves underneath a
 * confirmation, and reporting back the figure we asked with would only ever confirm our
 * own arithmetic.
 */
fun trashedMessage(count: Long): String = when (count) {
    0L -> "Nothing to move"
    1L -> "1 photo moved to the trash"
    else -> "%,d photos moved to the trash".format(count)
}

fun restoredMessage(count: Long): String = when (count) {
    0L -> "Nothing to put back"
    1L -> "1 photo put back"
    else -> "%,d photos put back".format(count)
}
