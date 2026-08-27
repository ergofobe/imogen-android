package com.imogen.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.imogen.android.data.Session
import com.imogen.sdk.Asset

/**
 * A photograph from the server, with something in its place until it arrives.
 *
 * The placeholder is the asset's own dominant colour, which the server computed when it
 * made the thumbnail. A grid of grey rectangles resolving into photographs looks like a
 * page failing to load; a grid of the right colours looks like the photographs arriving.
 */
@Composable
fun AssetImage(
    session: Session,
    asset: Asset,
    variant: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val placeholder = asset.placeholderColor?.let(::parseHexColor)
        ?: MaterialTheme.colorScheme.surfaceVariant

    Box(modifier.background(placeholder)) {
        AsyncImage(
            model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                .data(session.assetUrl(asset.id, variant))
                // Two accounts can hold the same asset id. Without the account in the key
                // the cache would answer one server's request with the other's photograph.
                .memoryCacheKey("${session.accountId}:${asset.id}:$variant")
                .diskCacheKey("${session.accountId}:${asset.id}:$variant")
                .build(),
            imageLoader = session.imageLoader,
            contentDescription = contentDescription ?: asset.description ?: asset.originalFilename,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** `#rrggbb`, which is what the server sends. Anything else falls back to the theme. */
fun parseHexColor(value: String): Color? {
    val hex = value.removePrefix("#")
    if (hex.length != 6) return null
    return runCatching { Color(hex.toLong(16) or 0xFF000000L) }.getOrNull()
}
