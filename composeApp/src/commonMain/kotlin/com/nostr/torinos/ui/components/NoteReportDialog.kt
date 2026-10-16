package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

internal enum class ReportReason(val label: String, val nip56Value: String) {
    Spam("スパム", "spam"),
    Impersonation("なりすまし", "impersonation"),
    Illegal("違法な内容", "illegal"),
    Malware("マルウェア", "malware"),
    Nudity("露骨な画像", "nudity"),
    Profanity("攻撃的な内容", "profanity"),
    Other("その他", "other"),
}

@Composable
internal fun ReportDialog(
    onDismiss: () -> Unit,
    onReport: (reason: String, detail: String) -> Unit,
) {
    DismissKeyboardOnLeave()
    var selectedReason by remember { mutableStateOf(ReportReason.Spam) }
    var detail by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("投稿を通報") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportReason.entries.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReason = reason }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedReason == reason,
                            onClick = { selectedReason = reason },
                        )
                        Text(
                            text = reason.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it.take(500) },
                    label = { Text("補足（任意・通報後このユーザーをブロックします）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onReport(selectedReason.nip56Value, detail.trim()) },
            ) {
                Text("通報")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

