package com.nostr.torinos.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

internal data class DecodedBlurHash(
    val width: Int,
    val height: Int,
    val colors: List<Color>,
)

@Composable
internal fun BlurHashPlaceholder(
    blurHash: String,
    modifier: Modifier = Modifier,
) {
    val decoded = remember(blurHash) { decodeBlurHash(blurHash) } ?: return
    Canvas(modifier = modifier) {
        val cellWidth = size.width / decoded.width
        val cellHeight = size.height / decoded.height
        decoded.colors.forEachIndexed { index, color ->
            val x = index % decoded.width
            val y = index / decoded.width
            drawRect(
                color = color,
                topLeft = Offset(x * cellWidth, y * cellHeight),
                size = Size(cellWidth + 1f, cellHeight + 1f),
            )
        }
    }
}

/** Decodes a BlurHash into a deliberately small bitmap-like color grid. */
internal fun decodeBlurHash(
    blurHash: String,
    width: Int = 24,
    height: Int = 24,
): DecodedBlurHash? {
    if (width <= 0 || height <= 0 || blurHash.length < 6) return null
    val sizeFlag = decodeBase83(blurHash, 0, 1) ?: return null
    val componentCountX = sizeFlag % 9 + 1
    val componentCountY = sizeFlag / 9 + 1
    val expectedLength = 4 + 2 * componentCountX * componentCountY
    if (blurHash.length != expectedLength) return null

    val quantizedMaximumValue = decodeBase83(blurHash, 1, 2) ?: return null
    val maximumValue = (quantizedMaximumValue + 1) / 166f
    val components = Array(componentCountX * componentCountY) { index ->
        if (index == 0) {
            decodeBase83(blurHash, 2, 6)?.let(::decodeDc) ?: return null
        } else {
            val start = 4 + index * 2
            decodeBase83(blurHash, start, start + 2)?.let { decodeAc(it, maximumValue) } ?: return null
        }
    }

    val basisX = Array(componentCountX) { componentX ->
        FloatArray(width) { x -> cos(PI * x * componentX / width).toFloat() }
    }
    val basisY = Array(componentCountY) { componentY ->
        FloatArray(height) { y -> cos(PI * y * componentY / height).toFloat() }
    }
    val colors = ArrayList<Color>(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var red = 0f
            var green = 0f
            var blue = 0f
            for (componentY in 0 until componentCountY) {
                for (componentX in 0 until componentCountX) {
                    val basis = basisX[componentX][x] * basisY[componentY][y]
                    val component = components[componentY * componentCountX + componentX]
                    red += component.red * basis
                    green += component.green * basis
                    blue += component.blue * basis
                }
            }
            colors += Color(linearToSrgb(red), linearToSrgb(green), linearToSrgb(blue))
        }
    }
    return DecodedBlurHash(width, height, colors)
}

private data class LinearColor(val red: Float, val green: Float, val blue: Float)

private fun decodeDc(value: Int): LinearColor = LinearColor(
    red = srgbToLinear(value shr 16),
    green = srgbToLinear((value shr 8) and 255),
    blue = srgbToLinear(value and 255),
)

private fun decodeAc(value: Int, maximumValue: Float): LinearColor {
    val quantizedRed = value / (19 * 19)
    val quantizedGreen = value / 19 % 19
    val quantizedBlue = value % 19
    return LinearColor(
        red = signedPow((quantizedRed - 9) / 9f, 2f) * maximumValue,
        green = signedPow((quantizedGreen - 9) / 9f, 2f) * maximumValue,
        blue = signedPow((quantizedBlue - 9) / 9f, 2f) * maximumValue,
    )
}

private fun decodeBase83(value: String, start: Int, end: Int): Int? {
    var result = 0
    for (index in start until end) {
        val digit = BlurHashAlphabet.indexOf(value.getOrNull(index) ?: return null)
        if (digit < 0) return null
        result = result * 83 + digit
    }
    return result
}

private fun srgbToLinear(value: Int): Float {
    val normalized = value / 255f
    return if (normalized <= 0.04045f) normalized / 12.92f
    else ((normalized + 0.055f) / 1.055f).pow(2.4f)
}

private fun linearToSrgb(value: Float): Int {
    val clamped = value.coerceIn(0f, 1f)
    val normalized = if (clamped <= 0.0031308f) clamped * 12.92f
    else 1.055f * clamped.pow(1f / 2.4f) - 0.055f
    return (normalized * 255f + 0.5f).toInt().coerceIn(0, 255)
}

private fun signedPow(value: Float, exponent: Float): Float =
    (if (value < 0f) -1f else 1f) * abs(value).pow(exponent)

private const val BlurHashAlphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
