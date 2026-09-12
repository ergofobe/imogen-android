package com.imogen.android.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imogen.android.data.Session
import com.imogen.android.ui.common.trashedMessage
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
    /**
     * A reload asked for by hand, which keeps what is already on the screen.
     *
     * Distinct from [loading], which means there is nothing to show yet. The two are
     * different sentences — "wait, there is nothing here" and "what you are looking at is
     * being checked" — and a pull-to-refresh must say the second or it blanks the grid
     * somebody is holding.
     */
    val refreshing: Boolean = false,
    val error: String? = null,
    /**
     * Something to say about an edit that has already happened, or failed to.
     *
     * A bulk action confirmed against a count deserves an answer either way: the grid
     * putting the photographs back is honest, but on its own it does not say whether the
     * server refused or simply had nothing to do.
     */
    val notice: String? = null,
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

    /**
     * Which read of the library is the current one.
     *
     * [refresh] and [reload] ask the same endpoint and both install an index, so one can
     * answer after the other and undo it. The rule is that [refresh] wins and [reload]
     * stands down: a refresh is the authoritative reset, and it drops every loaded day, so
     * whatever index it installs the days are refetched against that same index. A reload
     * landing late is the harmful direction — it keeps the loaded days, so an index that
     * has gone backwards leaves cells the refetched days can never fill.
     */
    private var generation = 0

    init {
        refresh()
    }

    fun refresh() {
        generation++
        _state.update { it.copy(loading = true, error = null) }
        inFlight.values.forEach(Job::cancel)
        inFlight.clear()
        recency.clear()

        viewModelScope.launch {
            // A notice outlives the reload that follows the action it describes: a bulk
            // trash refreshes the day counts, and replacing the state wholesale would
            // throw away the sentence saying what had just happened before it was read.
            runCatching { session.client.assets.timeline(TimelineQuery(filter)) }
                .onSuccess { timeline ->
                    _state.update {
                        TimelineState(
                            index = TimelineIndex(timeline.buckets),
                            loading = false,
                            notice = it.notice,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        TimelineState(
                            loading = false,
                            error = describe(error),
                            notice = it.notice,
                        )
                    }
                }
        }
    }

    /**
     * Re-reads the library without taking it off the screen.
     *
     * Photographs arrive from elsewhere — the CLI, another device, the web — and nothing
     * pushes that news here, so somebody who knows the library has changed needs a gesture
     * that says so. [refresh] is the wrong one: it drops every loaded day, and a grid that
     * turns grey under the finger that pulled it looks like a fault rather than an answer.
     *
     * So the index is fetched, and only the days it no longer agrees with are thrown away
     * and asked for again. A pull that finds nothing new costs one request and changes
     * nothing visible except the spinner; a pull after an upload reloads the day or two
     * that grew. Either way the grid keeps its cells, and therefore its scroll position.
     *
     * A failure says so and leaves the library alone. There is a whole timeline on the
     * screen already, and replacing it with an error page because a refresh did not get
     * through would lose more than it explains.
     */
    fun reload() {
        if (_state.value.refreshing) return
        val mine = ++generation
        _state.update { it.copy(refreshing = true) }

        viewModelScope.launch {
            runCatching { session.client.assets.timeline(TimelineQuery(filter)) }
                .onSuccess { timeline ->
                    // Something has read the library since — a bulk trash, a failed
                    // archive — and it read it later than this did. Its answer stands.
                    if (mine != generation) return@onSuccess
                    val index = TimelineIndex(timeline.buckets)
                    val stale = index.staleDays(_state.value.days.mapValues { it.value.size })

                    // Cancelled first: a day still in flight is fetching against counts
                    // that have just been superseded, and letting it land would put a day
                    // back that is about to be asked for again.
                    stale.forEach { date -> inFlight.remove(date)?.cancel() }
                    recency.removeAll(stale)
                    _state.update {
                        it.copy(index = index, days = it.days - stale, refreshing = false)
                    }

                    // Days the index has dropped entirely are gone, not changed; asking
                    // for one would fetch an empty bucket to put nowhere.
                    stale.filter { index.countOf(it) != null }.forEach(::ensureLoaded)
                }
                .onFailure { error ->
                    if (mine != generation) return@onFailure
                    _state.update { it.copy(refreshing = false, notice = describe(error)) }
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

            // By identity, not by date. A reload cancels a stale day's fetch and starts
            // another for the same day in the same tick; the cancelled one then resumes to
            // tidy up, and removing by date alone would take its replacement off the books
            // — leaving a second fetch of that day free to start alongside it.
            if (inFlight[date] === coroutineContext[Job]) inFlight.remove(date)
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
        val accounted = assetIds?.let { removeLocally(it.toSet()) }

        viewModelScope.launch {
            runCatching { session.client.assets.trash(selection) }
                .onSuccess { count ->
                    if (assetIds == null) {
                        refresh()
                        _state.update { it.copy(notice = trashedMessage(count)) }
                    } else if (accounted != assetIds.size) {
                        // Some of them were in a day that had been evicted, so their
                        // buckets still claim them. Only the server can say what the counts
                        // are now.
                        refresh()
                    }
                }
                .onFailure { error ->
                    refresh()
                    _state.update { it.copy(notice = describe(error)) }
                }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

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
     *
     * That key is only recoverable while the day is loaded, so this returns how many of
     * [assetIds] it could actually account for. A photograph whose day was evicted between
     * the tick and the trash is not one of them, and its bucket would otherwise keep
     * claiming a count it no longer has — leaving a cell that stays grey for ever, because
     * the refetched day is one tile short of what the index still asks for.
     */
    private fun removeLocally(assetIds: Set<String>): Int {
        if (assetIds.isEmpty()) return 0

        val perDay = _state.value.days
            .mapValues { (_, tiles) -> tiles.count { it.id in assetIds } }
            .filterValues { it > 0 }

        _state.update { state ->
            state.copy(
                index = state.index.withoutPhotos(perDay),
                days = state.days.mapValues { (_, tiles) -> tiles.filterNot { it.id in assetIds } },
            )
        }
        return perDay.values.sum()
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
