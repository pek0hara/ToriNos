package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * 編集シートの上部（左にキャンセル、中央に見出し、右に確定ボタン）。
 * 見出しは画面の中央に置き、左右のボタンと近づくときだけずらす。入りきらないときは「…」で切る。
 * 文字を大きくしていても、ボタンと見出しの間に一定のすき間を残す。
 */
@Composable
fun SheetHeader(
    title: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "保存",
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    isConfirming: Boolean = false,
) {
    val gap = 12.dp
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            TextButton(onClick = onCancel, enabled = cancelEnabled) {
                Text("キャンセル", maxLines = 1)
            }
            Button(onClick = onConfirm, enabled = confirmEnabled) {
                if (isConfirming) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmText, maxLines = 1)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val cancel = measurables[0].measure(loose)
        val confirm = measurables[1].measure(loose)
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val titleMax = (width - cancel.width - confirm.width - gapPx * 2).coerceAtLeast(0)
        val title = measurables[2].measure(Constraints(maxWidth = titleMax))
        val height = maxOf(cancel.height, confirm.height, title.height)
        layout(width, height) {
            cancel.placeRelative(0, (height - cancel.height) / 2)
            confirm.placeRelative(width - confirm.width, (height - confirm.height) / 2)
            val minX = cancel.width + gapPx
            val maxX = (width - confirm.width - gapPx - title.width).coerceAtLeast(minX)
            val titleX = ((width - title.width) / 2).coerceIn(minX, maxX)
            title.placeRelative(titleX, (height - title.height) / 2)
        }
    }
}
