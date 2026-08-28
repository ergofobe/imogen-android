package com.imogen.android.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.imogen.android.data.Session
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * A video, played from the server rather than downloaded first.
 *
 * ExoPlayer streams it, which for a phone-shot 4K clip is the difference between playing
 * in a second and playing in a minute. The bearer token goes on the request the same way
 * it does for a thumbnail: the media endpoints are ordinary API routes and want ordinary
 * authentication.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPage(session: Session, assetId: String, playing: Boolean) {
    val context = LocalContext.current

    val player = remember(assetId) {
        val http = OkHttpClient()
        val factory = OkHttpDataSource.Factory(http).apply {
            // Read once, when the player is built. A token that expires mid-video is
            // rarer than a video long enough for it to matter, and the retry that
            // follows a 401 will pick up a fresh one.
            runBlocking { session.accessToken() }?.let {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $it"))
            }
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, factory)),
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(session.assetUrl(assetId, "original")))
                prepare()
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
            }
    }

    // Only the page in front plays. Without this, swiping through a folder of videos
    // starts every one it passes and leaves them all running.
    LaunchedEffect(playing) { player.playWhenReady = playing }

    DisposableEffect(assetId) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { PlayerView(it).apply { useController = true; this.player = player } },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.player = null },
    )
}
