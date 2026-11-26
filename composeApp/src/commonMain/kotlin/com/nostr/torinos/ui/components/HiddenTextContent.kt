package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.decodeHiddenTexts

@Composable
internal fun HiddenTextContent(text: String, depth: Int = 0) {
    // Match the source tool's maximum nesting depth.
    if (depth >= 20) return
    val messages = remember(text) { decodeHiddenTexts(text) }
    if (messages.isEmpty()) return

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        messages.forEach { message ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dashedBorder(MaterialTheme.colorScheme.outline)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                SelectionContainer {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                HiddenTextContent(message, depth + 1)
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val inset = strokeWidth / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        ),
    )
}
