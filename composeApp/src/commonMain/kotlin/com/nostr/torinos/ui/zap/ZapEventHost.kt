package com.nostr.torinos.ui.zap

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.ui.settings.setPlainText
import com.nostr.torinos.zap.ZapEvent
import kotlinx.coroutines.launch

/**
 * Zap の送信からの知らせを受ける。カードが画面から外れても請求書をウォレットへ渡せるよう、アプリの外枠に置く。
 * ウォレットのアプリが開けなければ、請求書をコピーできるダイアログを出す。
 */
@Composable
internal fun ZapEventHost(session: AccountSession?, showMessage: suspend (String) -> Unit) {
    if (session == null) return
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var unopenedInvoice by remember(session) { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        session.zaps.events.collect { event ->
            when (event) {
                is ZapEvent.OpenWallet -> try {
                    uriHandler.openUri("lightning:${event.invoice}")
                } catch (_: Throwable) {
                    unopenedInvoice = event.invoice
                }
                // 表示を待つと次の知らせを受け取れないので、別に出す。
                is ZapEvent.Message -> scope.launch { showMessage(event.text) }
            }
        }
    }

    unopenedInvoice?.let { invoice ->
        AlertDialog(
            onDismissRequest = { unopenedInvoice = null },
            title = { Text("Lightning ウォレットのアプリが見つかりません") },
            text = { Text("請求書をコピーして、お使いのウォレットで支払ってください。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { clipboard.setPlainText(invoice) }
                    unopenedInvoice = null
                }) { Text("請求書をコピー") }
            },
            dismissButton = { TextButton(onClick = { unopenedInvoice = null }) { Text("閉じる") } },
        )
    }
}
