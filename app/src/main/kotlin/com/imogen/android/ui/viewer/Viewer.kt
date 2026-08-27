package com.imogen.android.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.imogen.android.data.Session
import com.imogen.android.ui.common.AssetImage
import com.imogen.sdk.Asset
import com.imogen.sdk.AssetType
import com.imogen.sdk.TimelineTile

/**
 * One page of the viewer: what it takes to draw the photograph and nothing more.
 *
 * The timeline pages through a day it holds as tiles, so the pager cannot be handed a list
 * of assets — most of them have never been fetched. It is handed this instead, and the one
 * photograph on screen is resolved to a full [Asset] by whoever opened the viewer.
 */
data class ViewerItem(
    val id: String,
    val type: AssetType,
    val placeholderColor: String? = null,
)

fun Asset.asViewerItem(): ViewerItem = ViewerItem(id, type, placeholderColor)

fun TimelineTile.asViewerItem(): ViewerItem = ViewerItem(id, type, placeholderColor)

/**
 * One photograph, full screen, with the rest of them a swipe away.
 *
 * Black rather than the app's paper background, and the chrome hides on a tap. A
 * photograph shown inside a frame of interface is a photograph you are looking at; a
 * photograph filling the screen is one you are looking into, and that is the difference
 * this screen exists to make.
 *
 * [current] is the fetched asset for the page on screen, and [onPage] says which page that
 * is. The actions need a whole asset — its favourite and archived flags decide which icon
 * to draw — so they appear as it arrives rather than acting on something half known.
 */
@Composable
fun Viewer(
    session: Session,
    items: List<ViewerItem>,
    initialIndex: Int,
    current: Asset?,
    mode: ViewerMode,
    onPage: (String) -> Unit,
    onClose: () -> Unit,
    onFavorite: (Asset, Boolean) -> Unit,
    onArchive: (Asset, Boolean) -> Unit,
    onTrash: (Asset) -> Unit,
    onRestore: (Asset) -> Unit,
    onDetails: (Asset) -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val pager = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var chromeVisible by remember { mutableStateOf(true) }

    // Every photograph deleted from underneath the pager shortens the list. Closing when
    // it empties is the only sensible end to that.
    LaunchedEffect(items.size) { if (items.isEmpty()) onClose() }

    val currentId = items.getOrNull(pager.currentPage)?.id
    LaunchedEffect(currentId) { currentId?.let(onPage) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // One page either side, because a swipe should show a photograph rather than
            // a black rectangle that becomes one.
            beyondViewportPageCount = 1,
            // Zooming in takes the horizontal drag away from the pager; without this a
            // pan across a zoomed photograph turns the page instead.
            userScrollEnabled = true,
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.type == AssetType.VIDEO) {
                VideoPage(session, item.id, playing = pager.currentPage == page)
            } else {
                ZoomablePage(
                    session = session,
                    item = item,
                    onTap = { chromeVisible = !chromeVisible },
                )
            }
        }

        // Only while it is the asset for the page actually on screen. A swipe outruns the
        // fetch, and a filename left over from the previous photograph is worse than none.
        val current = current?.takeIf { it.id == currentId }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
                }
                Text(
                    text = current?.originalFilename.orEmpty(),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (current != null) {
                    IconButton(onClick = { onDetails(current) }) {
                        Icon(Icons.Filled.Info, "Details", tint = Color.White)
                    }
                }
            }
        }

        if (current != null) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    if (mode == ViewerMode.Trash) {
                        IconButton(onClick = { onRestore(current) }) {
                            Icon(Icons.Filled.Restore, "Put back", tint = Color.White)
                        }
                    } else {
                        IconButton(onClick = { onFavorite(current, !current.favorite) }) {
                            Icon(
                                if (current.favorite) Icons.Filled.Favorite
                                else Icons.Filled.FavoriteBorder,
                                "Favourite",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { onArchive(current, !current.archived) }) {
                            Icon(
                                if (current.archived) Icons.Filled.Unarchive
                                else Icons.Filled.Archive,
                                "Archive",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { onTrash(current) }) {
                            Icon(Icons.Filled.Delete, "Move to trash", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

enum class ViewerMode { Library, Trash }

/**
 * Pinch to zoom, drag to pan, double-tap to do both at once.
 *
 * The pan is clamped so the photograph cannot be dragged off the screen and abandoned
 * there, which is the failure mode of every zoom implementation that forgets to.
 */
@Composable
private fun ZoomablePage(session: Session, item: ViewerItem, onTap: () -> Unit) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) {
                        val maxX = size.width * (scale - 1f) / 2f
                        val maxY = size.height * (scale - 1f) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AssetImage(
            session = session,
            assetId = item.id,
            placeholderColor = item.placeholderColor,
            variant = "preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}
