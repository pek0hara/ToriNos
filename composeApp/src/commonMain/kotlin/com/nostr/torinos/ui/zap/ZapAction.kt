package com.nostr.torinos.ui.zap

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.settings.setPlainText
import com.nostr.torinos.zap.ZapProgress
import com.nostr.torinos.zap.ZapSettingsStore
import com.nostr.torinos.zap.ZapTally
import com.nostr.torinos.zap.ZapTarget
import com.nostr.torinos.zap.ZapTotal
import com.nostr.torinos.zap.formatZapAmount
import com.nostr.torinos.zap.zapRecipientAddress
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** この額を超える Zap は確認を挟む（桁の打ち間違い対策）。 */
internal const val ZAP_CONFIRM_THRESHOLD_SATS = 10_000L

internal fun formatSats(sats: Long): String =
    sats.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * 操作欄の⚡と、受け取った Zap の合計。受け取り先を設定した人の投稿だけに出す。
 * ログインしていない・設定で非表示・ミュート中の人の投稿では出さない。
 * 自分の投稿は合計だけ出し、押せない。
 *
 * 合計・送信の状態はこの部品の中だけで読み、カード全体を再描画しない。
 */
@Composable
internal fun ZapAction(event: NostrEvent, authorProfile: NostrProfile?, hidden: Boolean) {
    val session = LocalAccountSession.current ?: return
    val enabled by ZapSettingsStore.enabled.collectAsState()
    if (!enabled || hidden) return
    val recipient = remember(authorProfile) { authorProfile?.zapRecipientAddress() }
    if (recipient == null) return
    val total by remember(event.id, event.pubkey) { ZapTally.shared.observe(event.id, event.pubkey) }
        .collectAsState(ZapTotal.Empty)
    val progress by remember(session, event.id) { session.zaps.progress.map { it[event.id] }.distinctUntilChanged() }
        .collectAsState(null)
    val zappedHere by remember(session, event.id) { session.zaps.zappedByMe.map { event.id in it }.distinctUntilChanged() }
        .collectAsState(false)
    val isOwnNote = event.pubkey == session.pubkey
    val isZapped = zappedHere || session.pubkey in total.senders
    val target = remember(event) { ZapTarget.of(event) }

    var showAmounts by remember(event.id) { mutableStateOf(false) }
    var showSheet by remember(event.id) { mutableStateOf(false) }
    var showAwaiting by remember(event.id) { mutableStateOf(false) }
    var confirmAmount by remember(event.id) { mutableStateOf<Long?>(null) }

    fun sendFromPopup(sats: Long) {
        if (sats > ZAP_CONFIRM_THRESHOLD_SATS) confirmAmount = sats else session.zaps.send(target, sats)
    }

    val description = when {
        isOwnNote -> "受け取った Zap"
        progress is ZapProgress.AwaitingPayment -> "支払いを確認中"
        progress != null -> "Zap の送信を取り消す"
        else -> "Zap"
    }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                modifier = Modifier.size(32.dp).let {
                    if (isOwnNote) it else it.combinedClickable(
                        onClick = {
                            when (progress) {
                                null -> showAmounts = true
                                is ZapProgress.AwaitingPayment -> showAwaiting = true
                                else -> session.zaps.cancel(event.id)
                            }
                        },
                        onLongClick = { if (progress == null) showSheet = true },
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isZapped) ZapIcons.Filled else ZapIcons.Outlined,
                    contentDescription = description,
                    modifier = Modifier.size(if (progress != null) 16.dp else 20.dp),
                    tint = if (isZapped || progress != null) ZapAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (progress) {
                    null -> Unit
                    // 請求書を受け取るまでが前半、ウォレットへ渡したあとが後半（Amethyst の2段階の表示）。
                    is ZapProgress.AwaitingPayment -> CircularProgressIndicator(
                        modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = ZapAmber,
                    )
                    else -> CircularProgressIndicator(
                        progress = { if (progress == ZapProgress.ResolvingEndpoint) 0.2f else 0.45f },
                        modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = ZapAmber,
                    )
                }
            }
            Box(modifier = Modifier.height(32.dp).widthIn(min = 20.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = formatZapAmount(total.totalMsats),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = (-1).dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        ZapAmountPopup(
            expanded = showAmounts,
            onDismiss = { showAmounts = false },
            onSelect = { sats ->
                showAmounts = false
                sendFromPopup(sats)
            },
            onCustomize = {
                showAmounts = false
                showSheet = true
            },
        )
    }

    confirmAmount?.let { sats ->
        LargeZapConfirmDialog(sats, onConfirm = {
            confirmAmount = null
            session.zaps.send(target, sats)
        }, onDismiss = { confirmAmount = null })
    }
    if (showSheet) {
        ZapSheet(
            session = session,
            target = target,
            profile = authorProfile,
            onDismiss = { showSheet = false },
        )
    }
    (progress as? ZapProgress.AwaitingPayment)?.takeIf { showAwaiting }?.let { awaiting ->
        AwaitingPaymentDialog(
            invoice = awaiting.invoice,
            onResend = {
                showAwaiting = false
                session.zaps.cancel(event.id)
                showAmounts = true
            },
            onDismiss = { showAwaiting = false },
        )
    }
}

@Composable
private fun ZapAmountPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onCustomize: () -> Unit,
) {
    val amounts by ZapSettingsStore.amounts.collectAsState()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(modifier = Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            amounts.forEach { sats ->
                TextButton(onClick = { onSelect(sats) }) { Text("⚡${formatSats(sats)}") }
            }
            TextButton(onClick = onCustomize) { Text("…") }
        }
    }
}

@Composable
internal fun LargeZapConfirmDialog(sats: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${formatSats(sats)} sats を送りますか？") },
        text = { Text("額が大きいため確認しています。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("送る") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** 支払い待ちの投稿で⚡を押したとき。二重払いを防ぐため、もう一度送る前に確かめる。 */
@Composable
private fun AwaitingPaymentDialog(
    invoice: String,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("支払いを確認しています") },
        text = { Text("ウォレットで支払いが済むと、⚡が塗られます。もう一度送ると二重に支払うことがあります。") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { uriHandler.openUri("lightning:$invoice") }
                onDismiss()
            }) { Text("ウォレットを開く") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    scope.launch { clipboard.setPlainText(invoice) }
                    onDismiss()
                }) { Text("請求書をコピー") }
                TextButton(onClick = onResend) { Text("もう一度送る") }
            }
        },
    )
}
