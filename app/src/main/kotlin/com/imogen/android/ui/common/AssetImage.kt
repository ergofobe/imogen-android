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
 *
 * An id and a colour rather than an [Asset], because a grid tile carries both and carries
 * nothing else this needs — asking for a whole asset here would be asking a caller to
 * fetch a checksum in order to draw a thumbnail.
 */
@Composable
fun AssetImage(
    session: Session,
    assetId: String,
    placeholderColor: String?,
    variant: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val placeholder = placeholderColor?.let(::parseHexColor)
        ?: MaterialTheme.colorScheme.surfaceVariant

    Box(modifier.background(placeholder)) {
        AsyncImage(
            model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                .data(session.assetUrl(assetId, variant))
                // Two accounts can hold the same asset id. Without the account in the key
                // the cache would answer one server's request with the other's photograph.
                .memoryCacheKey("${session.accountId}:$assetId:$variant")
                .diskCacheKey("${session.accountId}:$assetId:$variant")
                .build(),
            imageLoader = session.imageLoader,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AssetImage(
    session: Session,
    asset: Asset,
    variant: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) = AssetImage(
    session = session,
    assetId = asset.id,
    placeholderColor = asset.placeholderColor,
    variant = variant,
    modifier = modifier,
    contentScale = contentScale,
    contentDescription = contentDescription ?: asset.description ?: asset.originalFilename,
)

/** `#rrggbb`, which is what the server sends. Anything else falls back to the theme. */
fun parseHexColor(value: String): Color? {
    val hex = value.removePrefix("#")
    if (hex.length != 6) return null
    return runCatching { Color(hex.toLong(16) or 0xFF000000L) }.getOrNull()
}
