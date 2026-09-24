package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * リレーの複数選択ダイアログ(第8.4節の `RelayMultiSelector`)。単一選択の [RelaySelector] とは別。
 *
 * [onAddCustomRelay] を渡すと手入力欄を出す。戻り値はエラー文言で、null なら追加できた。
 */
@Composable
internal fun RelayMultiSelectDialog(
    title: String,
    candidates: List<String>,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    emptyMessage: String = "リレーが設定されていません",
    note: String? = null,
    onAddCustomRelay: ((String) -> String?)? = null,
) {
    var customInput by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidates.isEmpty()) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(candidates, key = { it }) { url ->
                            RelayCheckRow(
                                url = url,
                                checked = url in selected,
                                onToggle = { enabled ->
                                    onSelectionChange(if (enabled) selected + url else selected - url)
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                if (onAddCustomRelay != null) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = {
                            customInput = it
                            customError = null
                        },
                        label = { Text("リレーを追加") },
                        placeholder = { Text("wss://") },
                        singleLine = true,
                        isError = customError != null,
                        supportingText = customError?.let { error -> { Text(error) } },
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val error = onAddCustomRelay(customInput)
                                    customError = error
                                    if (error == null) customInput = ""
                                },
                                enabled = customInput.isNotBlank(),
                            ) { Text("追加") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun RelayCheckRow(
    url: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
