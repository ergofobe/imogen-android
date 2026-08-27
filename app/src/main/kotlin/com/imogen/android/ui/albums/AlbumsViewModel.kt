package com.imogen.android.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imogen.android.data.Session
import com.imogen.sdk.Album
import com.imogen.sdk.AlbumCreate
import com.imogen.sdk.AlbumUpdate
import com.imogen.sdk.AssetSelection
import com.imogen.sdk.ImogenException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumsState(
    val albums: List<Album> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Set after adding photographs, so the screen can say how many actually landed. */
    val notice: String? = null,
)

class AlbumsViewModel(private val session: Session) : ViewModel() {

    private val _state = MutableStateFlow(AlbumsState())
    val state: StateFlow<AlbumsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { session.client.albums.list() }
                .onSuccess { albums -> _state.value = AlbumsState(albums = albums, loading = false) }
                .onFailure { error ->
                    _state.value = AlbumsState(loading = false, error = describe(error))
                }
        }
    }

    /**
     * A list of ids goes in the creation itself, so "new album with these forty" is one
     * request that either happens or does not. A by-query selection cannot — [AlbumCreate]
     * takes ids and nothing else — so that one creates and then fills, and [then] is where
     * the filling goes.
     */
    fun create(name: String, assetIds: List<String>? = null, then: (Album) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { session.client.albums.create(AlbumCreate(name = name, assetIds = assetIds)) }
                .onSuccess { album ->
                    _state.update { it.copy(albums = it.albums + album) }
                    then(album)
                }
                .onFailure { error -> _state.update { it.copy(error = describe(error)) } }
        }
    }

    fun rename(albumId: String, name: String) {
        viewModelScope.launch {
            runCatching { session.client.albums.update(albumId, AlbumUpdate(name = name)) }
                .onSuccess { updated ->
                    _state.update { state ->
                        state.copy(albums = state.albums.map { if (it.id == updated.id) updated else it })
                    }
                }
        }
    }

    fun delete(albumId: String) {
        // Gone from the list at once. Deleting an album does not delete its photographs,
        // so there is nothing here worth a confirmation round trip.
        _state.update { it.copy(albums = it.albums.filterNot { album -> album.id == albumId }) }
        viewModelScope.launch {
            runCatching { session.client.albums.remove(albumId) }.onFailure { refresh() }
        }
    }

    /**
     * Adding is idempotent server-side, and the result says what actually changed — so a
     * photograph already in the album is reported as skipped rather than as added twice.
     *
     * Takes a selection rather than a list, so "everything this search found" goes over as
     * the search rather than as however many thousand uuids it matched.
     */
    fun addAssets(albumId: String, selection: AssetSelection) {
        viewModelScope.launch {
            runCatching { session.client.albums.addAssets(albumId, selection) }
                .onSuccess { result ->
                    _state.update { state ->
                        state.copy(
                            albums = state.albums.map {
                                if (it.id == albumId) it.copy(assetCount = result.assetCount) else it
                            },
                            notice = when {
                                result.added == 0L -> "Already in that album"
                                result.skipped > 0L ->
                                    "Added ${result.added}, ${result.skipped} already there"
                                else -> "Added ${result.added}"
                            },
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(notice = describe(error)) } }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun describe(error: Throwable): String = when (error) {
        is ImogenException -> error.message
        else -> "Could not reach the server."
    }

    companion object {
        fun factory(session: Session) = viewModelFactory {
            initializer { AlbumsViewModel(session) }
        }
    }
}
