package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun NetworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalPlatformContext.current
    val model = remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

@Composable
fun PreviewImage(
    data: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalPlatformContext.current
    val model = remember(context, data) {
        ImageRequest.Builder(context)
            .data(data)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

@Composable
fun AvatarImage(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val model = remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}

/** ノート本文から画像URLを抽出する */
private val imageUrlRegex = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp|bmp|svg)(\?\S*)?""",
    RegexOption.IGNORE_CASE,
)

fun extractImageUrls(content: String): List<String> =
    imageUrlRegex.findAll(content).map { it.value }.toList()

fun isImageUrl(url: String): Boolean = imageUrlRegex.matches(url)

fun stripImageUrls(content: String): String =
    imageUrlRegex.replace(content, "").trim()
