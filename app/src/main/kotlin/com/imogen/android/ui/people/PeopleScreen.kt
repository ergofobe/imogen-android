package com.imogen.android.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.imogen.android.data.Session
import com.imogen.android.ui.common.EmptyState
import com.imogen.android.ui.common.Loading
import com.imogen.sdk.Person
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PeopleState(
    val people: List<Person> = emptyList(),
    val loading: Boolean = true,
    /** False when the server has face grouping switched off, which is the default. */
    val available: Boolean = true,
    val message: String? = null,
)

/**
 * Face grouping is optional and off until a server administrator turns it on, so this has
 * to have something sensible to say when there is nothing to show — and that is not the
 * same as "no people found".
 */
class PeopleViewModel(private val session: Session) : ViewModel() {

    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val status = runCatching { session.client.people.status() }.getOrNull()
            if (status == null || !status.enabled) {
                _state.value = PeopleState(
                    loading = false,
                    available = false,
                    message = "This server does not have face grouping switched on.",
                )
                return@launch
            }
            runCatching { session.client.people.list() }
                .onSuccess { people ->
                    _state.value = PeopleState(
                        people = people,
                        loading = false,
                        message = if (people.isEmpty() && status.pending > 0) {
                            "Still looking. ${status.pending} photographs left to scan."
                        } else {
                            null
                        },
                    )
                }
                .onFailure { _state.value = PeopleState(loading = false, message = "Could not load people.") }
        }
    }

    fun rename(personId: String, name: String) {
        viewModelScope.launch {
            runCatching {
                session.client.people.update(personId, com.imogen.sdk.PersonUpdate(name = name))
            }.onSuccess { refresh() }
        }
    }

    companion object {
        fun factory(session: Session) = viewModelFactory {
            initializer { PeopleViewModel(session) }
        }
    }
}

@Composable
fun PeopleScreen(
    session: Session,
    model: PeopleViewModel,
    columns: Int,
    onOpen: (Person) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by model.state.collectAsStateWithLifecycle()

    when {
        state.loading -> Loading(modifier)
        !state.available -> EmptyState(
            "People are not switched on",
            state.message ?: "An administrator can enable face grouping on the server.",
            modifier,
        )
        state.people.isEmpty() -> EmptyState(
            "Nobody yet",
            state.message ?: "People appear here once the server has grouped some faces.",
            modifier,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtMost(8)),
            contentPadding = contentPadding,
            modifier = modifier.fillMaxSize(),
        ) {
            items(state.people, key = { it.id }) { person ->
                PersonFace(session, person, onClick = { onOpen(person) })
            }
        }
    }
}

@Composable
private fun PersonFace(session: Session, person: Person, onClick: () -> Unit) {
    Column(
        Modifier.padding(8.dp).clickable(onClick = onClick),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            person.coverFaceId?.let { face ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(session.faceThumbnailUrl(face))
                        .memoryCacheKey("${session.accountId}:face:$face")
                        .diskCacheKey("${session.accountId}:face:$face")
                        .build(),
                    imageLoader = session.imageLoader,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            // An unnamed cluster is still browsable, and calling it "Unnamed" is more
            // honest than leaving a blank where a name goes.
            text = person.name ?: "Unnamed",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
