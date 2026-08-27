package com.imogen.android.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imogen.android.data.Session
import com.imogen.sdk.AssetFilter
import com.imogen.sdk.AssetSelection
import com.imogen.sdk.AssetUpdate
import com.imogen.sdk.ImogenException
import com.imogen.sdk.TimelineBucketQuery
import com.imogen.sdk.TimelineQuery
import com.imogen.sdk.TimelineTile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimelineState(
    val index: TimelineIndex = TimelineIndex(emptyList()),
    /** Loaded tiles, by day. Days nobody has looked at are simply absent. */
    val days: Map<String, List<TimelineTile>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * The main timeline, fetched a day at a time.
 *
 * The index says how many photographs each day holds, so the grid is the right length
 * before a single one is fetched. Days are then loaded as they come into view, which
 * means jumping to a date five years back costs one request rather than four hundred.
 *
 * What a day fetches is tiles, not assets. A cell draws an id, a colour, a play badge and
 * a heart; an `Asset` would additionally carry a checksum, exif, filenames, mime types and
 * both captured-at corrections, none of which a grid reads. Over a heavy month that is the
 * difference between one round trip and several. The full asset is fetched for the one
 * photograph somebody actually opens.
 *
 * [filter] scopes the whole screen — the day counts and the tiles both. An empty filter is
 * the library; `AssetFilter(personId = …)` is one person's photographs, with the same day
 * headings, the same rail and the same windowing.
 *
 * Loaded days are capped and evicted oldest-touched-first. Scrolling a fifty-thousand
 * photograph library from end to end must not end with all fifty thousand in memory.
 */
class TimelineViewModel(
    private val session: Session,
    val filter: AssetFilter = AssetFilter(),
) : ViewModel() {

    private val _state = MutableStateFlow(TimelineState())
    val state: StateFlow<TimelineState> = _state.asStateFlow()

    /** Days being fetched, so a fast scroll does not ask for the same one ten times. */
    private val inFlight = mutableMapOf<String, Job>()

    /** Touch order, oldest first. Plain list: it is bounded by [MAX_LOADED_DAYS]. */
    private val recency = ArrayDeque<String>()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        inFlight.values.forEach(Job::cancel)
        inFlight.clear()
        recency.clear()

        viewModelScope.launch {
            runCatching { session.client.assets.timeline(TimelineQuery(filter)) }
                .onSuccess { timeline ->
                    _state.value = TimelineState(
                        index = TimelineIndex(timeline.buckets),
                        loading = false,
                    )
                }
                .onFailure { error ->
                    _state.value = TimelineState(loading = false, error = describe(error))
                }
        }
    }

    /**
     * Asks for a day if it is not already here or on its way.
     *
     * Called from composition as cells appear, so it has to be cheap and idempotent —
     * a scroll fires this hundreds of times a second.
     */
    fun ensureLoaded(date: String) {
        if (date in _state.value.days || date in inFlight) return

        inFlight[date] = viewModelScope.launch {
            val collected = mutableListOf<TimelineTile>()
            var cursor: String? = null

            runCatching {
                // A day usually fits in one request. A scanned archive landing forty
                // thousand photographs on one date does not, so the loop is here — but it
                // runs once for almost every day in almost every library.
                do {
                    val page = session.client.assets.timelineBucket(
                        TimelineBucketQuery(period = date, filter = filter, cursor = cursor),
                    )
                    collected += page.items
                    cursor = page.nextCursor
                } while (cursor != null)
            }.onSuccess {
                _state.update { state -> state.copy(days = state.days + (date to collected)) }
                remember(date)
            }

            inFlight.remove(date)
        }
    }

    private fun remember(date: String) {
        recency.remove(date)
        recency.addLast(date)
        if (recency.size <= MAX_LOADED_DAYS) return

        val dropped = mutableSetOf<String>()
        while (recency.size > MAX_LOADED_DAYS) dropped += recency.removeFirst()
        _state.update { state -> state.copy(days = state.days - dropped) }
    }

    // --- edits ---

    fun setFavorite(assetId: String, favorite: Boolean) {
        replaceLocally(assetId) { it.copy(favorite = favorite) }
        viewModelScope.launch {
            runCatching { session.client.assets.update(assetId, AssetUpdate(favorite = favorite)) }
                // A heart that stays filled on a server that refused the change is a lie
                // the interface keeps telling.
                .onFailure { replaceLocally(assetId) { it.copy(favorite = !favorite) } }
        }
    }

    fun setArchived(assetId: String, archived: Boolean) {
        // Archiving takes it out of the timeline entirely — the server leaves archived
        // photographs out of the buckets, so the grid has to lose the cell as well.
        removeLocally(setOf(assetId))
        viewModelScope.launch {
            runCatching { session.client.assets.update(assetId, AssetUpdate(archived = archived)) }
                .onFailure { refresh() }
        }
    }

    /**
     * A tile carries no description, so there is nothing here to change optimistically —
     * the sheet that edited it has closed by the time this is sent.
     */
    fun setDescription(assetId: String, description: String) {
        viewModelScope.launch {
            runCatching {
                session.client.assets.update(assetId, AssetUpdate(description = description))
            }
        }
    }

    /**
     * Moves photographs to the trash, by id or by query.
     *
     * A list of ids can leave the grid at once, because the cells it names are known. A
     * query cannot — most of what it matches was never loaded — so the day counts are
     * fetched again once the server has acted, which is the only honest way to shorten a
     * grid by a number nobody here knows.
     */
    fun trash(selection: AssetSelection) {
        val assetIds = selection.assetIds
        if (assetIds != null && assetIds.isEmpty()) return
        assetIds?.let { removeLocally(it.toSet()) }

        viewModelScope.launch {
            runCatching { session.client.assets.trash(selection) }
                .onSuccess { if (assetIds == null) refresh() }
                .onFailure { refresh() }
        }
    }

    private fun replaceLocally(assetId: String, change: (TimelineTile) -> TimelineTile) {
        _state.update { state ->
            state.copy(
                days = state.days.mapValues { (_, tiles) ->
                    if (tiles.none { it.id == assetId }) {
                        tiles
                    } else {
                        tiles.map { if (it.id == assetId) change(it) else it }
                    }
                },
            )
        }
    }

    /**
     * Takes photographs out of the grid at once, shortening the days they came from.
     *
     * The counts come from the loaded days rather than from the tiles' own dates: a day is
     * keyed by the bucket the server filed it under, and re-deriving that key from an
     * instant is how a photograph taken near midnight ends up decrementing the wrong one.
     */
    private fun removeLocally(assetIds: Set<String>) {
        if (assetIds.isEmpty()) return
        _state.update { state ->
            val perDay = state.days
                .mapValues { (_, tiles) -> tiles.count { it.id in assetIds } }
                .filterValues { it > 0 }

            state.copy(
                index = state.index.withoutPhotos(perDay),
                days = state.days.mapValues { (_, tiles) -> tiles.filterNot { it.id in assetIds } },
            )
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is ImogenException -> error.message
        else -> "Could not reach the server."
    }

    companion object {
        /**
         * Roughly two thousand photographs on a typical library, which is more than any
         * screen shows and few enough to hold without thinking about it.
         */
        private const val MAX_LOADED_DAYS = 60

        fun factory(session: Session, filter: AssetFilter = AssetFilter()) = viewModelFactory {
            initializer { TimelineViewModel(session, filter) }
        }
    }
}
