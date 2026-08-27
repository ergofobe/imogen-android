package com.imogen.android.ui.people

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imogen.android.data.Session
import com.imogen.android.ui.timeline.TimelineScreen
import com.imogen.android.ui.timeline.TimelineViewModel
import com.imogen.sdk.AssetFilter
import com.imogen.sdk.AssetSelection

/**
 * One person's photographs — an ordinary timeline with a filter on it.
 *
 * `people.get(id).photos` was the obvious way to do this and the wrong one: that endpoint
 * caps at five hundred and selects with no ordering, so somebody with three thousand
 * photographs saw an arbitrary five hundred of them in uuid order. Grouped into days it
 * read as scattered noise rather than as a life.
 *
 * `AssetFilter(personId = …)` scopes the day counts and the tiles alike, which makes this
 * the same screen as the library: day headings, the segment-table rail, windowing, and
 * eviction, none of it written twice.
 */
@Composable
fun PersonDetailScreen(
    session: Session,
    personId: String,
    columns: Int,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onAddToAlbum: ((AssetSelection) -> Unit)? = null,
) {
    val model: TimelineViewModel = viewModel(
        key = "person:${session.accountId}:$personId",
        factory = TimelineViewModel.factory(session, AssetFilter(personId = personId)),
    )

    TimelineScreen(
        session = session,
        model = model,
        columns = columns,
        snackbar = snackbar,
        modifier = modifier,
        contentPadding = contentPadding,
        emptyHeadline = "No photographs",
        emptyBody = "Nothing here is grouped under this person.",
        onAddToAlbum = onAddToAlbum,
    )
}
