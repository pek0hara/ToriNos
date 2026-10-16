package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountLogin
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.InteractiveSigner
import com.nostr.torinos.account.SignerAttention
import com.nostr.torinos.account.SignerMethod
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.util.logException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.launch

private const val PLAINTEXT_KEY_NOTICE_DISMISSED_UNTIL = "web_plaintext_key_notice_dismissed_until"
private const val PLAINTEXT_KEY_NOTICE_SNOOZE_SECONDS = 7L * 24 * 60 * 60

/**
 * 署名器が利用者の操作を待っているときに、画面上部へ出す帯。
 * - パスキーで保護した鍵の解錠
 * - リモート署名器での承認・再接続
 * - Web で暗号化せずに保存している秘密鍵の、パスキーでの保護
 */
@Composable
fun SignerStatusBanner(session: AccountSession, modifier: Modifier = Modifier) {
    val interactive = session.signer as? InteractiveSigner
    val attention = interactive?.attention?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var busy by remember(session.sessionId) { mutableStateOf(false) }
    var error by remember(session.sessionId) { mutableStateOf<String?>(null) }

    // Web で秘密鍵を暗号化せずに保存しているアカウント。保護し終えたら、このセッションでは出さない。
    val needsPlaintextNotice = AccountLogin.protectsPrivateKeyWithPasskey && session.signer.method == SignerMethod.PrivateKey
    var plaintextNoticeVisible by remember(session.sessionId) { mutableStateOf(false) }
    LaunchedEffect(session.sessionId, needsPlaintextNotice) {
        if (!needsPlaintextNotice) return@LaunchedEffect
        val dismissedUntil = runCatching { LocalSettingsStorage.getString(PLAINTEXT_KEY_NOTICE_DISMISSED_UNTIL)?.toLongOrNull() }
            .getOrNull() ?: 0L
        plaintextNoticeVisible = Clock.System.now().epochSeconds >= dismissedUntil
    }

    fun run(action: suspend () -> Unit, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logException("SignerStatusBanner", e, "Signer action failed")
                error = e.message ?: "操作できませんでした"
            } finally {
                busy = false
            }
        }
    }

    val message: String
    val actions: List<Pair<String, () -> Unit>>
    when {
        attention == SignerAttention.Locked -> {
            message = "パスキーで解錠すると、投稿やリアクションができます。"
            actions = listOf("解錠" to { run({ interactive?.unlock() }) })
        }
        attention is SignerAttention.ApprovalRequired -> {
            message = "署名器で、この操作を承認してください。"
            actions = listOf("署名器を開く" to { runCatching { uriHandler.openUri(attention.url) } })
        }
        attention is SignerAttention.Disconnected -> {
            message = "署名器に接続できません（${attention.reason}）。読むことはできます。"
            actions = listOf("再接続" to { run({ interactive?.reconnect() }) })
        }
        plaintextNoticeVisible -> {
            message = "秘密鍵が暗号化されずにこのブラウザに保存されています。パスキーで保護できます。"
            actions = listOf(
                "パスキーで保護" to {
                    run({ AccountLogin.protectStoredPrivateKey(session.pubkey) }) { plaintextNoticeVisible = false }
                },
                "後で" to {
                    plaintextNoticeVisible = false
                    scope.launch {
                        val until = Clock.System.now().epochSeconds + PLAINTEXT_KEY_NOTICE_SNOOZE_SECONDS
                        runCatching { LocalSettingsStorage.putString(PLAINTEXT_KEY_NOTICE_DISMISSED_UNTIL, until.toString()) }
                    }
                },
            )
        }
        else -> return
    }

    Surface(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { (label, onClick) ->
                    TextButton(onClick = onClick, enabled = !busy) { Text(label) }
                }
            }
        }
    }
}
