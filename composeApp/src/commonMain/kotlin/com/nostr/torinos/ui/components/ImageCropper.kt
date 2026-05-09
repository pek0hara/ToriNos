package com.nostr.torinos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlin.math.max

data class EditableImage(
    val bytes: ByteArray,
    val mimeType: String,
)

data class ImageDimensions(
    val width: Int,
    val height: Int,
)

data class ImageCropRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

@Composable
fun ImageCropperDialog(
    image: EditableImage,
    title: String,
    aspectRatio: Float,
    circularMask: Boolean,
    onDismiss: () -> Unit,
    onCropped: (ByteArray, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dimensions by remember(image) { mutableStateOf<ImageDimensions?>(null) }
    var frameSize by remember(image) { mutableStateOf(IntSize.Zero) }
    var zoom by remember(image) { mutableStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var isCropping by remember(image) { mutableStateOf(false) }
    var error by remember(image) { mutableStateOf<String?>(null) }

    LaunchedEffect(image) {
        dimensions = readImageDimensions(image.bytes)
    }

    Dialog(onDismissRequest = { if (!isCropping) onDismiss() }) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)

                val imageSize = dimensions
                if (imageSize == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                } else {
                    CropFrame(
                        image = image,
                        imageSize = imageSize,
                        aspectRatio = aspectRatio,
                        circularMask = circularMask,
                        zoom = zoom,
                        offset = offset,
                        onZoomChange = { nextZoom ->
                            zoom = nextZoom
                            offset = clampCropOffset(offset, imageSize, frameSize, aspectRatio, zoom)
                        },
                        onOffsetChange = { nextOffset ->
                            offset = clampCropOffset(nextOffset, imageSize, frameSize, aspectRatio, zoom)
                        },
                        onFrameSizeChange = { frameSize = it },
                    )

                    Slider(
                        value = zoom,
                        onValueChange = { nextZoom ->
                            zoom = nextZoom
                            offset = clampCropOffset(offset, imageSize, frameSize, aspectRatio, nextZoom)
                        },
                        valueRange = 1f..4f,
                        enabled = !isCropping,
                    )

                    val offsetRange = cropOffsetRange(imageSize, frameSize, aspectRatio, zoom)
                    if (offsetRange.x > 0f) {
                        Text("横位置", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = offset.x.coerceIn(-offsetRange.x, offsetRange.x),
                            onValueChange = { nextX ->
                                offset = clampCropOffset(
                                    offset.copy(x = nextX),
                                    imageSize,
                                    frameSize,
                                    aspectRatio,
                                    zoom,
                                )
                            },
                            valueRange = -offsetRange.x..offsetRange.x,
                            enabled = !isCropping,
                        )
                    }
                    if (offsetRange.y > 0f) {
                        Text("縦位置", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = offset.y.coerceIn(-offsetRange.y, offsetRange.y),
                            onValueChange = { nextY ->
                                offset = clampCropOffset(
                                    offset.copy(y = nextY),
                                    imageSize,
                                    frameSize,
                                    aspectRatio,
                                    zoom,
                                )
                            },
                            valueRange = -offsetRange.y..offsetRange.y,
                            enabled = !isCropping,
                        )
                    }
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isCropping) { Text("キャンセル") }
                    Button(
                        onClick = {
                            val imageSize = dimensions ?: return@Button
                            val cropRect = calculateCropRect(imageSize, frameSize, aspectRatio, zoom, offset)
                            isCropping = true
                            error = null
                            scope.launch {
                                val cropped = cropImageForUpload(image.bytes, cropRect, aspectRatio)
                                isCropping = false
                                if (cropped == null) {
                                    error = "画像の切り取りに失敗しました"
                                } else {
                                    onCropped(cropped, "image/jpeg")
                                }
                            }
                        },
                        enabled = !isCropping && dimensions != null && frameSize.width > 0 && frameSize.height > 0,
                    ) {
                        if (isCropping) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("アップロード")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CropFrame(
    image: EditableImage,
    imageSize: ImageDimensions,
    aspectRatio: Float,
    circularMask: Boolean,
    zoom: Float,
    offset: Offset,
    onZoomChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    onFrameSizeChange: (IntSize) -> Unit,
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged(onFrameSizeChange)
            .pointerInput(image, imageSize) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val nextZoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                    onZoomChange(nextZoom)
                    onOffsetChange(offset + pan)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val frameWidthPx = with(density) { maxWidth.toPx() }
        val frameHeightPx = frameWidthPx / aspectRatio
        val baseScale = max(
            frameWidthPx / imageSize.width.toFloat(),
            frameHeightPx / imageSize.height.toFloat(),
        )
        val imageWidth = with(density) { (imageSize.width * baseScale).toDp() }
        val imageHeight = with(density) { (imageSize.height * baseScale).toDp() }

        PreviewImage(
            data = image.bytes,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .size(width = imageWidth, height = imageHeight)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (circularMask) {
                        Modifier
                            .padding(1.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                    },
                ),
        )
    }
}

private fun clampCropOffset(
    offset: Offset,
    imageSize: ImageDimensions,
    frameSize: IntSize,
    aspectRatio: Float,
    zoom: Float,
): Offset {
    val range = cropOffsetRange(imageSize, frameSize, aspectRatio, zoom)
    return Offset(
        x = offset.x.coerceIn(-range.x, range.x),
        y = offset.y.coerceIn(-range.y, range.y),
    )
}

private fun cropOffsetRange(
    imageSize: ImageDimensions,
    frameSize: IntSize,
    aspectRatio: Float,
    zoom: Float,
): Offset {
    if (frameSize.width <= 0 || frameSize.height <= 0 || imageSize.width <= 0 || imageSize.height <= 0) {
        return Offset.Zero
    }
    val baseScale = max(
        frameSize.width / imageSize.width.toFloat(),
        frameSize.height / imageSize.height.toFloat(),
    )
    val displayWidth = imageSize.width * baseScale * zoom
    val displayHeight = imageSize.height * baseScale * zoom
    val maxX = max(0f, (displayWidth - frameSize.width) / 2f)
    val maxY = max(0f, (displayHeight - frameSize.width / aspectRatio) / 2f)
    return Offset(maxX, maxY)
}

private fun calculateCropRect(
    imageSize: ImageDimensions,
    frameSize: IntSize,
    aspectRatio: Float,
    zoom: Float,
    offset: Offset,
): ImageCropRect {
    val frameWidth = frameSize.width.toFloat()
    val frameHeight = frameWidth / aspectRatio
    val baseScale = max(frameWidth / imageSize.width, frameHeight / imageSize.height)
    val displayWidth = imageSize.width * baseScale * zoom
    val displayHeight = imageSize.height * baseScale * zoom
    val imageLeft = (frameWidth - displayWidth) / 2f + offset.x
    val imageTop = (frameHeight - displayHeight) / 2f + offset.y
    val left = ((-imageLeft) / displayWidth).coerceIn(0f, 1f)
    val top = ((-imageTop) / displayHeight).coerceIn(0f, 1f)
    val width = (frameWidth / displayWidth).coerceIn(0f, 1f - left)
    val height = (frameHeight / displayHeight).coerceIn(0f, 1f - top)
    return ImageCropRect(left = left, top = top, width = width, height = height)
}

expect suspend fun readImageDimensions(bytes: ByteArray): ImageDimensions?

expect suspend fun cropImageForUpload(
    bytes: ByteArray,
    cropRect: ImageCropRect,
    aspectRatio: Float,
): ByteArray?
