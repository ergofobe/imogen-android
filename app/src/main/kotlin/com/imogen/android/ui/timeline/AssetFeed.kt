package com.imogen.android.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imogen.android.data.Session
import com.imogen.android.ui.common.toFilter
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetFilter
import com.imogen.sdk.AssetQuery
import com.imogen.sdk.AssetSelection
import com.imogen.sdk.AssetUpdate
import com.imogen.sdk.ImogenException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedState(
    val items: List<Asset> = emptyList(),
    val loading: Boolean = true,
    val appending: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
)

/**
 * One query's worth of the library, a page at a time.
 *
 * Cursors rather than offsets, because the server says so and it is right to: a timeline
 * that grows while somebody scrolls shifts every later page by one, and an offset-paged
 * grid duplicates and skips photographs in front of them as it happens.
 *
 * Edits are applied here first and sent afterwards. Pressing the heart should colour it
 * in immediately; waiting for a round trip to a server in somebody's cupboard makes a
 * responsive gesture feel broken.
 */
class AssetFeed(
    private val session: Session,
    private val query: AssetQuery,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    /** What this feed is showing, as a filter a bulk mutation can be aimed at. */
    val filter: AssetFilter = query.toFilter()

    private var cursor: String? = null

    init {
        refresh()
    }

    fun refresh() {
        cursor = null
        _state.update { it.copy(loading = true, error = null, exhausted = false) }
        viewModelScope.launch {
            runCatching { session.client.assets.list(query.copy(limit = PAGE)) }
                .onSuccess { page ->
                    cursor = page.nextCursor
                    _state.value = FeedState(
                        items = page.items,
                        loading = false,
                        exhausted = page.nextCursor == null,
                    )
                }
                .onFailure { error ->
                    _state.value = FeedState(loading = false, error = describe(error))
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.appending || current.exhausted) return
        val next = cursor ?: return

        _state.update { it.copy(appending = true) }
        viewModelScope.launch {
            runCatching { session.client.assets.list(query.copy(cursor = next, limit = PAGE)) }
                .onSuccess { page ->
                    cursor = page.nextCursor
                    _state.update {
                        // Guard against a page arriving twice, which a fast scroll and a
                        // slow network can otherwise arrange between them.
                        val known = it.items.mapTo(mutableSetOf()) { asset -> asset.id }
                        it.copy(
                            items = it.items + page.items.filterNot { asset -> asset.id in known },
                            appending = false,
                            exhausted = page.nextCursor == null,
                        )
                    }
                }
                .onFailure { _state.update { state -> state.copy(appending = false) } }
        }
    }

    fun setFavorite(asset: Asset, favorite: Boolean) =
        edit(asset, AssetUpdate(favorite = favorite)) { it.copy(favorite = favorite) }

    fun setArchived(asset: Asset, archived: Boolean) =
        edit(asset, AssetUpdate(archived = archived)) { it.copy(archived = archived) }

    fun setDescription(asset: Asset, description: String) =
        edit(asset, AssetUpdate(description = description)) { it.copy(description = description) }

    /** Moves photographs to the trash, where they wait rather than being destroyed. */
    fun trash(selection: AssetSelection) =
        mutate(selection) { session.client.assets.trash(it) }

    fun restore(selection: AssetSelection) =
        mutate(selection) { session.client.assets.restore(it) }

    /**
     * A list of ids leaves the feed at once and comes back if the server refuses. A query
     * cannot be applied here — what it matches is mostly unfetched — so the feed is simply
     * loaded again once the server has acted.
     */
    private fun mutate(selection: AssetSelection, act: suspend (AssetSelection) -> Long) {
        val ids = selection.assetIds?.toSet()
        if (ids != null && ids.isEmpty()) return

        val removed = if (ids == null) emptyList() else _state.value.items.filter { it.id in ids }
        if (ids != null) {
            _state.update { it.copy(items = it.items.filterNot { asset -> asset.id in ids }) }
        }

        viewModelScope.launch {
            runCatching { act(selection) }
                .onSuccess { if (ids == null) refresh() }
                .onFailure { putBack(removed) }
        }
    }

    private fun edit(asset: Asset, patch: AssetUpdate, optimistic: (Asset) -> Asset) {
        val before = asset
        _state.update { state ->
            state.copy(items = state.items.map { if (it.id == asset.id) optimistic(it) else it })
        }
        viewModelScope.launch {
            runCatching { session.client.assets.update(asset.id, patch) }
                .onSuccess { updated ->
                    _state.update { state ->
                        state.copy(items = state.items.map { if (it.id == updated.id) updated else it })
                    }
                }
                // Put it back the way it was. A heart that stays filled on a server that
                // refused the change is a lie the interface keeps telling.
                .onFailure {
                    _state.update { state ->
                        state.copy(items = state.items.map { if (it.id == before.id) before else it })
                    }
                }
        }
    }

    private fun putBack(assets: List<Asset>) {
        if (assets.isEmpty()) return
        _state.update { state ->
            state.copy(items = (state.items + assets).sortedByDescending { it.capturedAt })
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is ImogenException -> error.message
        else -> "Could not reach the server."
    }

    companion object {
        private const val PAGE = 120

        fun factory(session: Session, query: AssetQuery) = viewModelFactory {
            initializer { AssetFeed(session, query) }
        }
    }
}
