package com.imogen.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.imogen.android.data.Account
import com.imogen.android.data.Session
import com.imogen.android.ui.albums.AlbumsScreen
import com.imogen.android.ui.albums.CollectionShortcut
import com.imogen.android.ui.albums.AlbumsViewModel
import com.imogen.android.ui.onboarding.AddAccountScreen
import com.imogen.android.ui.people.PeopleScreen
import com.imogen.android.ui.people.PeopleViewModel
import com.imogen.android.ui.people.PersonDetailScreen
import com.imogen.android.ui.search.SearchScreen
import com.imogen.android.ui.timeline.AssetFeed
import com.imogen.android.ui.timeline.PhotoBrowser
import com.imogen.android.ui.timeline.TimelineScreen
import com.imogen.android.ui.timeline.TimelineViewModel
import com.imogen.android.ui.timeline.columnsFor
import com.imogen.android.ui.viewer.ViewerMode
import com.imogen.sdk.AssetQuery
import com.imogen.sdk.AssetSelection

/**
 * The whole application, above the individual screens.
 *
 * There is no account until somebody adds one, and every screen below here needs a session
 * to be worth drawing — so the decision about which of those two worlds we are in is made
 * once, here, rather than by each screen guarding itself.
 */
@Composable
fun ImogenApp(
    model: RootViewModel,
    deepLink: String?,
    onDeepLinkHandled: () -> Unit,
) {
    val book by model.book.collectAsStateWithLifecycle()

    // A pairing link can arrive at any moment, including while somebody is looking at a
    // photograph. It is handled at the top so it works from wherever they were.
    LaunchedEffect(deepLink) {
        val url = deepLink ?: return@LaunchedEffect
        model.consumeDeepLink(url)
        onDeepLinkHandled()
    }

    val current = book ?: return
    val active = current.active

    if (active == null) {
        AddAccountScreen(
            model = model,
            canGoBack = false,
            onBack = {},
            onLinked = {},
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Library(model = model, account = active)
}

private enum class Destination(val label: String, val icon: ImageVector) {
    Photos("Photos", Icons.Filled.Photo),
    Search("Search", Icons.Filled.Search),
    Albums("Albums", Icons.Filled.Album),
    People("People", Icons.Filled.People),
    Favourites("Favourites", Icons.Filled.Favorite),
    Trash("Trash", Icons.Filled.Delete),
    Settings("Settings", Icons.Filled.Settings);

    companion object {
        /**
         * What a phone shows along the bottom.
         *
         * Material puts the ceiling at five and means it: seven destinations on a
         * navigation bar is labels wrapping onto two lines and targets too narrow to hit.
         * The three left out are at the top of the Albums screen, which is where Apple and
         * Google both put them.
         */
        val onABar = listOf(Photos, Search, Albums, Settings)
    }
}

/**
 * What is on top of the current tab.
 *
 * Kept as state rather than as navigation routes because every one of these is a detail
 * of the tab it belongs to: backing out of an album should land in the album list, on the
 * tab it was opened from, with the tab's own scroll position intact.
 */
private sealed interface Overlay {
    data object None : Overlay

    /** One of the destinations a phone has no room for along the bottom. */
    data class Collection(val destination: Destination) : Overlay

    data class AlbumDetail(val id: String, val name: String) : Overlay
    data class PersonDetail(val id: String, val name: String) : Overlay
    data object AddAccount : Overlay
    data object Backup : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Library(model: RootViewModel, account: Account) {
    val session: Session = remember(account.id) { model.sessionFor(account) }
    var destination by remember { mutableStateOf(Destination.Photos) }
    var overlay by remember(account.id) { mutableStateOf<Overlay>(Overlay.None) }
    val snackbar = remember { SnackbarHostState() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value.toInt()
        // A rail from roughly the width of a small tablet. The number is the layout's,
        // not a size class's: what matters is whether a rail leaves room for the grid.
        val wide = widthDp >= 640
        val columns = columnsFor(if (wide) widthDp - 80 else widthDp)

        NavigationSuiteScaffold(
            navigationSuiteItems = {
                // A rail has room for all of them; a bar does not.
                val shown = if (wide) Destination.entries else Destination.onABar
                shown.forEach { entry ->
                    item(
                        selected = destination == entry,
                        onClick = {
                            destination = entry
                            overlay = Overlay.None
                        },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            },
            layoutType = if (wide) {
                NavigationSuiteType.NavigationRail
            } else {
                NavigationSuiteType.NavigationBar
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(titleFor(destination, overlay)) },
                        navigationIcon = {
                            if (overlay != Overlay.None) {
                                IconButton(onClick = { overlay = Overlay.None }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Content(
                    model = model,
                    session = session,
                    account = account,
                    destination = destination,
                    overlay = overlay,
                    onOverlay = { overlay = it },
                    columns = columns,
                    wide = wide,
                    contentPadding = padding,
                    snackbar = snackbar,
                )
            }
        }
    }
}

@Composable
private fun Content(
    model: RootViewModel,
    session: Session,
    account: Account,
    destination: Destination,
    overlay: Overlay,
    onOverlay: (Overlay) -> Unit,
    columns: Int,
    wide: Boolean,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
) {
    val albums: AlbumsViewModel = viewModel(
        key = "albums:${account.id}",
        factory = AlbumsViewModel.factory(session),
    )
    val albumsState by albums.state.collectAsStateWithLifecycle()

    // Adding to an album from a selection needs somewhere to say what happened, and the
    // selection is gone by the time the server answers.
    LaunchedEffect(albumsState.notice) {
        val notice = albumsState.notice ?: return@LaunchedEffect
        // Cleared even if the wait is cut short, so a notice cannot outlive the screen that
        // was showing it and fire again on the way back.
        try {
            snackbar.showSnackbar(notice)
        } finally {
            albums.clearNotice()
        }
    }

    var pickingAlbumFor by remember { mutableStateOf<AssetSelection?>(null) }
    val addToAlbum: (AssetSelection) -> Unit = { pickingAlbumFor = it }

    when (overlay) {
        is Overlay.AddAccount -> {
            AddAccountScreen(
                model = model,
                canGoBack = true,
                onBack = { onOverlay(Overlay.None) },
                onLinked = { onOverlay(Overlay.None) },
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            return
        }

        is Overlay.Backup -> {
            BackupPane(model, contentPadding)
            return
        }

        is Overlay.AlbumDetail -> {
            val feed: AssetFeed = viewModel(
                key = "album:${account.id}:${overlay.id}",
                factory = AssetFeed.factory(session, AssetQuery(albumId = overlay.id)),
            )
            PhotoBrowser(
                session = session,
                feed = feed,
                columns = columns,
                snackbar = snackbar,
                contentPadding = contentPadding,
                emptyHeadline = "This album is empty",
                emptyBody = "Select photographs anywhere in the library and add them here.",
                onAddToAlbum = addToAlbum,
            )
            return
        }

        is Overlay.PersonDetail -> {
            PersonDetailScreen(
                session = session,
                personId = overlay.id,
                columns = columns,
                snackbar = snackbar,
                contentPadding = contentPadding,
                onAddToAlbum = addToAlbum,
            )
            return
        }

        is Overlay.Collection -> {
            // Rendered by the same branch that draws it as a destination on a tablet, so
            // there is one implementation of each screen rather than two.
            Content(
                model = model,
                session = session,
                account = account,
                destination = overlay.destination,
                overlay = Overlay.None,
                onOverlay = onOverlay,
                columns = columns,
                wide = wide,
                contentPadding = contentPadding,
                snackbar = snackbar,
            )
            return
        }

        Overlay.None -> Unit
    }

    when (destination) {
        Destination.Photos -> {
            val timeline: TimelineViewModel = viewModel(
                key = "timeline:${account.id}",
                factory = TimelineViewModel.factory(session),
            )
            TimelineScreen(
                session = session,
                model = timeline,
                columns = columns,
                snackbar = snackbar,
                contentPadding = contentPadding,
                onAddToAlbum = addToAlbum,
            )
        }

        Destination.Search -> SearchScreen(
            session = session,
            columns = columns,
            snackbar = snackbar,
            onAddToAlbum = addToAlbum,
            contentPadding = contentPadding,
        )

        Destination.Albums -> AlbumsPane(
            session = session,
            albums = albums,
            columns = columns,
            wide = wide,
            contentPadding = contentPadding,
            snackbar = snackbar,
            onOpen = { onOverlay(Overlay.AlbumDetail(it.id, it.name)) },
            onAddToAlbum = addToAlbum,
            accountId = account.id,
            // Only where the rail is absent: on a tablet these are already in it, and
            // offering the same three things twice on one screen is clutter.
            shortcuts = if (wide) {
                emptyList()
            } else {
                listOf(
                    CollectionShortcut("People", Icons.Filled.People) {
                        onOverlay(Overlay.Collection(Destination.People))
                    },
                    CollectionShortcut("Favourites", Icons.Filled.Favorite) {
                        onOverlay(Overlay.Collection(Destination.Favourites))
                    },
                    CollectionShortcut("Trash", Icons.Filled.Delete) {
                        onOverlay(Overlay.Collection(Destination.Trash))
                    },
                )
            },
        )

        Destination.People -> {
            val people: PeopleViewModel = viewModel(
                key = "people:${account.id}",
                factory = PeopleViewModel.factory(session),
            )
            PeopleScreen(
                session = session,
                model = people,
                columns = columns,
                contentPadding = contentPadding,
                onOpen = { onOverlay(Overlay.PersonDetail(it.id, it.name ?: "Unnamed")) },
            )
        }

        Destination.Favourites -> {
            val feed: AssetFeed = viewModel(
                key = "favourites:${account.id}",
                factory = AssetFeed.factory(session, AssetQuery(favorite = true)),
            )
            PhotoBrowser(
                session = session,
                feed = feed,
                columns = columns,
                snackbar = snackbar,
                contentPadding = contentPadding,
                emptyHeadline = "No favourites yet",
                emptyBody = "Tap the heart while looking at a photograph to keep it here.",
                onAddToAlbum = addToAlbum,
            )
        }

        Destination.Trash -> {
            val feed: AssetFeed = viewModel(
                key = "trash:${account.id}",
                factory = AssetFeed.factory(session, AssetQuery(trashed = true)),
            )
            PhotoBrowser(
                session = session,
                feed = feed,
                columns = columns,
                snackbar = snackbar,
                contentPadding = contentPadding,
                mode = ViewerMode.Trash,
                emptyHeadline = "Trash is empty",
                emptyBody = "Deleted photographs wait here before the server removes them.",
            )
        }

        Destination.Settings -> com.imogen.android.ui.settings.SettingsScreen(
            model = model,
            activeAccountId = account.id,
            contentPadding = contentPadding,
            onAddAccount = { onOverlay(Overlay.AddAccount) },
            onOpenBackup = { onOverlay(Overlay.Backup) },
        )
    }

    pickingAlbumFor?.let { selection ->
        com.imogen.android.ui.albums.AlbumPicker(
            albums = albumsState.albums,
            onDismiss = { pickingAlbumFor = null },
            onChoose = { album ->
                albums.addAssets(album.id, selection)
                pickingAlbumFor = null
            },
            // A list of ids lands in the creation itself, so the album and its contents
            // arrive together or not at all. Only a by-query selection has to be created
            // and then filled, because that is the one the endpoint cannot express.
            onCreate = { name ->
                val ids = selection.assetIds
                if (ids != null) {
                    albums.create(name, ids)
                } else {
                    albums.create(name) { album -> albums.addAssets(album.id, selection) }
                }
                pickingAlbumFor = null
            },
        )
    }
}

/**
 * Albums on a wide screen show the list and the album side by side; on a phone the album
 * takes the whole screen. Both are the same two composables, arranged differently.
 */
@Composable
private fun AlbumsPane(
    session: Session,
    albums: AlbumsViewModel,
    columns: Int,
    wide: Boolean,
    contentPadding: PaddingValues,
    accountId: String,
    snackbar: SnackbarHostState,
    onOpen: (com.imogen.sdk.Album) -> Unit,
    onAddToAlbum: (AssetSelection) -> Unit,
    shortcuts: List<CollectionShortcut> = emptyList(),
) {
    if (!wide) {
        AlbumsScreen(
            session = session,
            model = albums,
            columns = columns,
            contentPadding = contentPadding,
            onOpen = onOpen,
            shortcuts = shortcuts,
        )
        return
    }

    var selected by remember(accountId) { mutableStateOf<com.imogen.sdk.Album?>(null) }

    Row(Modifier.fillMaxSize()) {
        AlbumsScreen(
            session = session,
            model = albums,
            columns = 2,
            contentPadding = contentPadding,
            onOpen = { selected = it },
            modifier = Modifier.width(320.dp),
        )
        VerticalDivider()
        Box(Modifier.weight(1f)) {
            val album = selected
            if (album == null) {
                com.imogen.android.ui.common.EmptyState(
                    "Pick an album",
                    "Its photographs will show here.",
                )
            } else {
                val feed: AssetFeed = viewModel(
                    key = "album:$accountId:${album.id}",
                    factory = AssetFeed.factory(session, AssetQuery(albumId = album.id)),
                )
                PhotoBrowser(
                    session = session,
                    feed = feed,
                    columns = (columns - 2).coerceAtLeast(3),
                    snackbar = snackbar,
                    contentPadding = contentPadding,
                    emptyHeadline = "This album is empty",
                    emptyBody = "Select photographs anywhere in the library and add them here.",
                    onAddToAlbum = onAddToAlbum,
                )
            }
        }
    }
}

@Composable
private fun BackupPane(model: RootViewModel, contentPadding: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.imogen.android.ImogenApplication
    val preferences by app.backupSettings.preferences.collectAsStateWithLifecycle(
        initialValue = com.imogen.android.backup.BackupPreferences(),
    )
    val progress by com.imogen.android.backup.BackupScheduler.progress(context)
        .collectAsStateWithLifecycle(initialValue = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    com.imogen.android.ui.settings.BackupScreen(
        model = model,
        preferences = preferences,
        progress = progress,
        contentPadding = contentPadding,
        onPreferencesChanged = { next ->
            scope.launch {
                app.backupSettings.update { next }
                com.imogen.android.backup.BackupScheduler.sync(context, next)
            }
        },
        onRunNow = {
            scope.launch {
                com.imogen.android.backup.BackupScheduler.runNow(context, app.backupSettings.current())
            }
        },
    )
}

private fun titleFor(destination: Destination, overlay: Overlay): String = when (overlay) {
    is Overlay.Collection -> overlay.destination.label
    is Overlay.AlbumDetail -> overlay.name
    is Overlay.PersonDetail -> overlay.name
    Overlay.AddAccount -> "Add an account"
    Overlay.Backup -> "Photo backup"
    Overlay.None -> destination.label
}
