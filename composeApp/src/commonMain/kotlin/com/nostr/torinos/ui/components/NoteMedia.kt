package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.PlatformContext
import coil3.request.ImageRequest
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.model.MediaMetadata
import com.nostr.torinos.model.timelinePreviewUrl
import kotlin.math.max

internal data class ExpandedImageState(
    val urls: List<String>,
    val initialIndex: Int,
)

internal const val TimelineImageMaxDecodeSizePx = 720
internal const val TimelineGridImageMaxDecodeSizePx = 360
internal val TimelineImageGridSpacing = 4.dp
internal val TimelineImageGridShape = RoundedCornerShape(6.dp)

/**
 * [ImagePreviewGrid] が実際に発行するリクエストと同じ URL・decode サイズ・scale になるよう、
 * プリフェッチ側もこの関数を通す（値がずれると別キャッシュエントリになり先読みが無駄になる）。
 */
internal fun firstImagePreviewRequest(context: PlatformContext, images: List<MediaMetadata>): ImageRequest? {
    val first = images.firstOrNull() ?: return null
    val previewUrl = first.timelinePreviewUrl(TimelineImageMaxDecodeSizePx)
    return if (images.size == 1) {
        buildNetworkImageRequest(
            context = context,
            url = previewUrl,
            contentScale = ContentScale.Fit,
            maxDecodeSizePx = TimelineImageMaxDecodeSizePx,
            animate = false,
        )
    } else {
        buildNetworkImageRequest(
            context = context,
            url = previewUrl,
            contentScale = ContentScale.Crop,
            maxDecodeSizePx = TimelineGridImageMaxDecodeSizePx,
            animate = false,
        )
    }
}

@Composable
internal fun ImagePreviewGrid(
    images: List<MediaMetadata>,
    singleImageMaxHeight: Dp = 400.dp,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val imageUrls = images.map { it.url }
    val previewUrls = images.map { it.timelinePreviewUrl(TimelineImageMaxDecodeSizePx) }
    if (imageUrls.size == 1) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val media = images.first()
            val declaredAspectRatio = if (media.width != null && media.height != null) {
                media.width.toFloat() / media.height.toFloat()
            } else {
                null
            }
            // メタデータの縦横比は欠落・不正確なことがあるため、実際にデコードされた画像の
            // 縦横比が分かればそちらで当たり判定を測り直す。ずれたままだと空白部分までタップ可能になる。
            var measuredAspectRatio by remember(media.url) { mutableStateOf<Float?>(null) }
            val aspectRatio = measuredAspectRatio ?: declaredAspectRatio ?: (16f / 9f)
            val imageHeight = minOf(maxWidth / aspectRatio, singleImageMaxHeight)
            val imageWidth = minOf(imageHeight * aspectRatio, maxWidth)
            NetworkImage(
                url = previewUrls.first(),
                contentDescription = images.first().alt,
                blurHash = images.first().blurhash,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                maxDecodeSizePx = TimelineImageMaxDecodeSizePx,
                filterQuality = FilterQuality.Low,
                animate = false,
                onSuccessSize = { size ->
                    if (size.isSpecified && size.width > 0f && size.height > 0f) {
                        measuredAspectRatio = size.width / size.height
                    }
                },
                modifier = Modifier
                    .height(imageHeight)
                    .width(imageWidth)
                    .clickable { onImageClick(imageUrls, 0) },
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(TimelineImageGridShape),
    ) {
        when (imageUrls.size) {
            2 -> TwoImageGrid(imageUrls, previewUrls, images.map { it.blurhash }, onImageClick)
            3 -> ThreeImageGrid(imageUrls, previewUrls, images.map { it.blurhash }, onImageClick)
            else -> FourImageGrid(imageUrls, previewUrls, images.map { it.blurhash }, onImageClick)
        }
    }
}

@Composable
internal fun TwoImageGrid(
    imageUrls: List<String>,
    previewUrls: List<String>,
    blurHashes: List<String?>,
    onImageClick: (List<String>, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(TimelineImageGridSpacing),
    ) {
        previewUrls.take(2).forEachIndexed { index, url ->
            GridImage(
                url = url,
                blurHash = blurHashes.getOrNull(index),
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls, index) },
            )
        }
    }
}

@Composable
internal fun ThreeImageGrid(
    imageUrls: List<String>,
    previewUrls: List<String>,
    blurHashes: List<String?>,
    onImageClick: (List<String>, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(TimelineImageGridSpacing),
    ) {
        GridImage(
            url = previewUrls[0],
            blurHash = blurHashes.getOrNull(0),
            modifier = Modifier.weight(1f),
            onClick = { onImageClick(imageUrls, 0) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TimelineImageGridSpacing),
        ) {
            GridImage(
                url = previewUrls[1],
                blurHash = blurHashes.getOrNull(1),
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls, 1) },
            )
            GridImage(
                url = previewUrls[2],
                blurHash = blurHashes.getOrNull(2),
                modifier = Modifier.weight(1f),
                onClick = { onImageClick(imageUrls, 2) },
            )
        }
    }
}

@Composable
internal fun FourImageGrid(
    imageUrls: List<String>,
    previewUrls: List<String>,
    blurHashes: List<String?>,
    onImageClick: (List<String>, Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(TimelineImageGridSpacing),
    ) {
        previewUrls.take(4).chunked(2).forEachIndexed { rowIndex, rowUrls ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(TimelineImageGridSpacing),
            ) {
                rowUrls.forEachIndexed { index, url ->
                    val imageIndex = rowIndex * 2 + index
                    Box(modifier = Modifier.weight(1f)) {
                        GridImage(
                            url = url,
                            blurHash = blurHashes.getOrNull(imageIndex),
                            onClick = { onImageClick(imageUrls, imageIndex) },
                        )
                        if (imageIndex == 3 && imageUrls.size > 4) {
                            MoreImagesOverlay(extraCount = imageUrls.size - 4)
                        }
                    }
                    if (rowUrls.size == 1 && index == 0) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun GridImage(
    url: String,
    blurHash: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NetworkImage(
        url = url,
        contentDescription = null,
        blurHash = blurHash,
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center,
        maxDecodeSizePx = TimelineGridImageMaxDecodeSizePx,
        filterQuality = FilterQuality.Low,
        animate = false,
        modifier = modifier
            .fillMaxSize()
            .clickable { onClick() },
    )
}

@Composable
internal fun ExpandedImageDialog(
    imageUrls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imageUrls.size },
    )
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = zoomedPage == null,
            ) { page ->
                var scale by remember(imageUrls[page]) { mutableStateOf(1f) }
                var offset by remember(imageUrls[page]) { mutableStateOf(Offset.Zero) }
                val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                    val wasZoomed = scale > 1f
                    val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                    scale = nextScale
                    zoomedPage = if (nextScale > 1f) page else null
                    offset = if (nextScale == 1f) {
                        Offset.Zero
                    } else if (!wasZoomed) {
                        offset
                    } else {
                        val maxOffset = 2400f * max(1f, nextScale - 1f)
                        Offset(
                            x = (offset.x + panChange.x).coerceIn(-maxOffset, maxOffset),
                            y = (offset.y + panChange.y).coerceIn(-maxOffset, maxOffset),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    NetworkImage(
                        url = imageUrls[page],
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .transformable(
                                state = transformableState,
                                canPan = { scale > 1f },
                            )
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "閉じる",
                    tint = Color.White,
                )
            }
            if (imageUrls.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    imageUrls.indices.forEach { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MoreImagesOverlay(extraCount: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        Text(
            text = "+$extraCount",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}


