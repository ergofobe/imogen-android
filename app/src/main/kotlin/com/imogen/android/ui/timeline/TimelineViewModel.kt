package com.imogen.android.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imogen.android.data.Session
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetQuery
import com.imogen.sdk.AssetSort
import com.imogen.sdk.AssetUpdate
import com.imogen.sdk.ImogenException
import com.imogen.sdk.SortOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimelineState(
    val index: TimelineIndex = TimelineIndex(emptyList()),
    /** Loaded photographs, by day. Days nobody has looked at are simply absent. */
    val days: Map<String, List<Asset>> = emptyMap(),
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
 * Loaded days are capped and evicted oldest-touched-first. Scrolling a fifty-thousand
 * photograph library from end to end must not end with all fifty thousand in memory.
 */
class TimelineViewModel(private val session: Session) : ViewModel() {

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
            runCatching { session.client.assets.timeline() }
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
            val (after, before) = dayBounds(date)
            val collected = mutableListOf<Asset>()
            var cursor: String? = null

            runCatching {
                // A day usually fits in one request. A wedding does not, so the loop is
                // here — but it runs once for almost every day in almost every library.
                do {
                    val page = session.client.assets.list(
                        AssetQuery(
                            takenAfter = after,
                            takenBefore = before,
                            cursor = cursor,
                            limit = PAGE,
                            sort = AssetSort.CAPTURED_AT,
                            order = SortOrder.DESC,
                        ),
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

    fun setFavorite(asset: Asset, favorite: Boolean) =
        edit(asset, AssetUpdate(favorite = favorite)) { it.copy(favorite = favorite) }

    fun setArchived(asset: Asset, archived: Boolean) {
        // Archiving takes it out of the timeline entirely — the server leaves archived
        // photographs out of the buckets, so the grid has to lose the cell as well.
        removeLocally(listOf(asset))
        viewModelScope.launch {
            runCatching { session.client.assets.update(asset.id, AssetUpdate(archived = archived)) }
                .onFailure { refresh() }
        }
    }

    fun setDescription(asset: Asset, description: String) =
        edit(asset, AssetUpdate(description = description)) { it.copy(description = description) }

    fun trash(assets: List<Asset>) {
        if (assets.isEmpty()) return
        removeLocally(assets)
        viewModelScope.launch {
            runCatching { session.client.assets.trash(assets.map { it.id }) }
                .onFailure { refresh() }
        }
    }

    private fun edit(asset: Asset, patch: AssetUpdate, optimistic: (Asset) -> Asset) {
        replaceLocally(asset.id) { optimistic(it) }
        viewModelScope.launch {
            runCatching { session.client.assets.update(asset.id, patch) }
                .onSuccess { updated -> replaceLocally(updated.id) { updated } }
                .onFailure { replaceLocally(asset.id) { asset } }
        }
    }

    private fun replaceLocally(assetId: String, change: (Asset) -> Asset) {
        _state.update { state ->
            state.copy(
                days = state.days.mapValues { (_, assets) ->
                    if (assets.none { it.id == assetId }) {
                        assets
                    } else {
                        assets.map { if (it.id == assetId) change(it) else it }
                    }
                },
            )
        }
    }

    private fun removeLocally(assets: List<Asset>) {
        val ids = assets.mapTo(mutableSetOf()) { it.id }
        val perDay = assets.groupingBy { it.capturedAt.take(10) }.eachCount()

        _state.update { state ->
            state.copy(
                index = state.index.withoutPhotos(perDay),
                days = state.days.mapValues { (_, day) -> day.filterNot { it.id in ids } },
            )
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is ImogenException -> error.message
        else -> "Could not reach the server."
    }

    companion object {
        /** The API's own ceiling. Asking for more is refused rather than truncated. */
        private const val PAGE = 500

        /**
         * Roughly two thousand photographs on a typical library, which is more than any
         * screen shows and few enough to hold without thinking about it.
         */
        private const val MAX_LOADED_DAYS = 60

        fun factory(session: Session) = viewModelFactory {
            initializer { TimelineViewModel(session) }
        }
    }
}
