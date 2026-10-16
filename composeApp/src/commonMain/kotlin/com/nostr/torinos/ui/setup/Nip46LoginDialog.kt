package com.nostr.torinos.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountLogin
import com.nostr.torinos.account.nip46.Nip46Login
import com.nostr.torinos.account.nip46.parseBunkerUri
import com.nostr.torinos.ui.settings.setPlainText
import com.nostr.torinos.util.logException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * リモート署名（NIP-46）で接続する。
 * - 署名器が出した `bunker://` を貼り付ける
 * - ToriNos が出した `nostrconnect://` を署名アプリで開く（またはコピーして渡す）
 * 接続できたら [onConnected] に保存処理を渡し、セッションの開始は呼び出し元が行う。
 */
@Composable
internal fun Nip46LoginDialog(
    onDismiss: () -> Unit,
    onConnected: (save: suspend () -> String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    var bunkerInput by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var approvalUrl by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Nip46Login.PendingNostrConnect?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            pending?.cancel()
        }
    }

    fun finish(connected: Nip46Login.Connected) {
        onConnected {
            AccountLogin.saveExternalAccount(connected.pubkey, connected.credential)
            connected.pubkey
        }
    }

    fun runConnect(block: suspend () -> Nip46Login.Connected) {
        error = null
        approvalUrl = null
        connecting = true
        job = scope.launch {
            try {
                finish(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logException("Nip46LoginDialog", e, "Failed to connect remote signer")
                error = "接続できませんでした: ${e.message}"
            } finally {
                connecting = false
                pending = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リモート署名でログイン") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "nsec.app や Amber などの署名器が、秘密鍵を持ったまま署名します。ToriNos には通信用の鍵だけを保存します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("署名器の接続用の文字列を貼り付ける", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = bunkerInput,
                    onValueChange = { bunkerInput = it.trim(); error = null },
                    placeholder = { Text("bunker://…") },
                    singleLine = true,
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val uri = parseBunkerUri(bunkerInput).getOrElse {
                            error = it.message
                            return@Button
                        }
                        runConnect { Nip46Login.connectBunker(uri, onAuthUrl = { approvalUrl = it }) }
                    },
                    enabled = bunkerInput.isNotBlank() && !connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("接続する") }

                HorizontalDivider()

                Text("署名アプリから接続する", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "同じ端末の署名アプリでは、承認している間に ToriNos の通信が止まり、接続できないことがあります。" +
                        "その場合は、署名アプリが出す bunker:// を上に貼り付けてください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val current = pending
                if (current == null) {
                    OutlinedButton(
                        onClick = {
                            val started = Nip46Login.startNostrConnect(onAuthUrl = { approvalUrl = it })
                            pending = started
                            runConnect { started.await() }
                        },
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("接続用のリンクを作る") }
                } else {
                    Text(
                        text = "署名アプリでこのリンクを開くか、コピーして署名アプリに貼り付けてください。接続されるまで待ちます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SelectionContainer {
                        Text(text = current.uri, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                    // 文字を大きくしても切れないよう、縦に並べる。
                    OutlinedButton(
                        onClick = {
                            runCatching { uriHandler.openUri(current.uri) }.onFailure {
                                error = "署名アプリが見つかりません。コピーして署名アプリに貼り付けてください"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("署名アプリで開く") }
                    OutlinedButton(
                        onClick = { scope.launch { runCatching { clipboard.setPlainText(current.uri) } } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("コピー") }
                }

                if (connecting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                approvalUrl?.let { url ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("署名器で承認する") }
                }
                error?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
