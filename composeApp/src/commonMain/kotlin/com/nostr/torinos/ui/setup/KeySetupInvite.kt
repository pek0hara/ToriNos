package com.nostr.torinos.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.pendingInvites
import com.nostr.torinos.ui.settings.getPlainText
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * 鍵設定の画面で、預かっている招待を見せる。無ければ招待リンクを貼る入口を出す
 * （アプリを入れた直後に、入口ページでコピーした招待リンクを貼ってもらう）。
 * 招待はログインが終わるまで預かり、ログイン後にチャットタブの参加シートで確かめてから参加する。
 */
@Composable
internal fun KeySetupInviteSection(modifier: Modifier = Modifier) {
    val invite by pendingInvites.invite.collectAsState()
    val current = invite
    if (current != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("招待を受け取りました", style = MaterialTheme.typography.titleSmall)
                Text(current.host, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "鍵を用意すると、このコミュニティへの参加の確認に進みます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { pendingInvites.clear() }, modifier = Modifier.padding(0.dp)) {
                    Text("招待を使わない")
                }
            }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun submit(value: String) {
        if (pendingInvites.offer(value, Clock.System.now().toEpochMilliseconds())) {
            text = ""
            error = null
            open = false
        } else {
            error = "ToriNos か Buzz の招待リンクを、リンク全体で貼ってください"
        }
    }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = modifier) { Text("招待リンクを持っている") }
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim(); error = null },
            label = { Text("招待リンク") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val pasted = clipboard.getPlainText()?.trim().orEmpty()
                        if (pasted.isNotEmpty()) {
                            text = pasted
                            submit(pasted)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("貼り付け") }
            OutlinedButton(onClick = { submit(text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("この招待を使う")
            }
        }
        TextButton(onClick = { open = false; text = ""; error = null }) { Text("閉じる") }
    }
}
