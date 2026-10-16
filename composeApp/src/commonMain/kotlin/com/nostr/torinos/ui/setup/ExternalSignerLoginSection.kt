package com.nostr.torinos.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountLogin
import com.nostr.torinos.crypto.isWebPlatform

/**
 * 秘密鍵をこのアプリに渡さずにログインする方法。
 * [onLogin] には保存処理（公開鍵を返す）を渡し、セッションの開始と画面の遷移は呼び出し元が行う。
 */
@Composable
internal fun ExternalSignerLoginSection(
    busy: Boolean,
    error: String?,
    onLogin: (save: suspend () -> String) -> Unit,
    onOpenNip46: () -> Unit,
) {
    var nip07Available by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        nip07Available = runCatching { AccountLogin.isNip07Available() }.getOrDefault(false)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "秘密鍵を渡さずにログイン", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "署名は拡張機能や署名アプリが行い、ToriNos には秘密鍵を保存しません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isWebPlatform) {
                Button(
                    onClick = { onLogin { AccountLogin.saveNip07Account() } },
                    enabled = nip07Available && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ブラウザ拡張でログイン（NIP-07）")
                }
                if (!nip07Available) {
                    Text(
                        text = "nos2x や Alby などの Nostr 拡張機能を入れると使えます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenNip46,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("リモート署名でログイン（NIP-46）")
            }
            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
