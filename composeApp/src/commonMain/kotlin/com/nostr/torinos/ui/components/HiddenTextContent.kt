package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.decodeHiddenTexts

@Composable
internal fun HiddenTextContent(eventId: String, text: String, depth: Int = 0) {
    // Match the source tool's maximum nesting depth; decode deeper levels only on demand.
    if (depth >= 20) return
    val messages = remember(text) { decodeHiddenTexts(text) }
    if (messages.isEmpty()) return
    var revealed by remember(eventId, text) { mutableStateOf(false) }

    Column {
        TextButton(onClick = { revealed = !revealed }) {
            Text(if (revealed) "隠し文字を閉じる" else "隠し文字を表示")
        }
        if (revealed) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = if (depth == 0) 12.dp else 0.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    messages.forEachIndexed { index, message ->
                        Column {
                            if (messages.size > 1) {
                                Text(
                                    text = "隠し文字 ${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SelectionContainer {
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                            HiddenTextContent(eventId, message, depth + 1)
                        }
                    }
                }
            }
        }
    }
}
