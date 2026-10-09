package com.nostr.torinos.ui.post

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun PendingPostInputDialog(
    inputs: List<PendingPostInput>,
    busy: Boolean,
    error: String?,
    onApply: (PendingPostInput) -> Unit,
    onDiscard: (PendingPostInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember(inputs) { mutableStateOf(inputs.first().id) }
    val selected = inputs.firstOrNull { it.id == selectedId } ?: inputs.first()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("保留中の入力があります") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保留入力を適用しますか？現在の入力がある場合は一時退避します。")
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(inputs, key = { it.id }) { input ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = input.id == selectedId,
                                enabled = !busy, role = Role.RadioButton, onClick = { selectedId = input.id }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (inputs.size > 1) RadioButton(selected = input.id == selectedId, onClick = null, enabled = !busy)
                            Text(input.memo.text.take(160).ifBlank {
                                val quotes = input.memo.quoteInputs()
                                when {
                                    quotes.isNotEmpty() -> "引用${quotes.size}件: ${quotes.first().displayPreview}"
                                    input.memo.imageUrls.isNotEmpty() -> "画像付きの入力"
                                    else -> "本文なしの入力"
                                }
                            }, maxLines = 5)
                        }
                    }
                }
                Text("破棄すると、選んだ保留入力を削除します。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onApply(selected) }, enabled = !busy) { Text("適用") } },
        dismissButton = { TextButton(onClick = { onDiscard(selected) }, enabled = !busy) { Text("破棄") } },
    )
}
