package com.nostr.torinos.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.generateKeyPair
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.crypto.normalizePrivateKey
import com.nostr.torinos.crypto.rememberPasswordManagerSaver
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException

@Composable
fun KeySetupScreen(onSetupComplete: (pubkeyHex: String) -> Unit, onDismiss: (() -> Unit)? = null) {
    var importKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var generatedInfo by remember { mutableStateOf<Pair<String, String>?>(null) } // priv, pub
    val scope = rememberCoroutineScope()
    val uiExceptionHandler = remember {
        loggingExceptionHandler("KeySetupScreen", "Uncaught UI coroutine exception")
    }
    val saveToPasswordManager = rememberPasswordManagerSaver()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("キャンセル") }
            }
            Text(
                text = "Nostr の鍵を用意する",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            KeyInfoCard(
                title = "Nostr とは",
                body = "ひとつの会社が管理するSNSではなく、公開鍵をIDとして使う分散型のネットワークです。同じ鍵を使えば、対応している別のアプリでも同じアカウントとして利用できます。",
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "ToriNos でポストするには、あなたのアカウントになる鍵が必要です。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ---- 新規生成 ----
            if (generatedInfo == null) {
                Button(
                    onClick = {
                        val kp = generateKeyPair()
                        generatedInfo = Pair(kp.privateKeyHex, kp.publicKeyHex)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("新しい鍵を生成する")
                }
            } else {
                val (priv, pub) = generatedInfo!!
                val nsec = remember(priv) { runCatching { hexToNsec(priv) }.getOrDefault(priv) }
                val npub = remember(pub) { runCatching { hexToNpub(pub) }.getOrDefault(pub) }
                GeneratedKeyValues(npub = npub, nsec = nsec)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch(uiExceptionHandler) {
                            val err = runCatching {
                                savePrivateKeyAndVerify(priv)
                                saveToPasswordManager(nsec, npub)
                            }.exceptionOrNull()?.let {
                                logException("KeySetupScreen", it, "Failed to save generated private key")
                                "秘密鍵を保存できませんでした: ${it.message}"
                            }
                            if (err == null) onSetupComplete(pub) else error = err
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("この鍵で始める")
                }
                TextButton(onClick = { generatedInfo = null }) { Text("やり直す") }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ---- 既存鍵インポート ----
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "既存の秘密鍵をインポート",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importKey,
                        onValueChange = { importKey = it.trim(); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("秘密鍵（nsec1... または hex）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            errorContainerColor = MaterialTheme.colorScheme.surface,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            errorCursorColor = MaterialTheme.colorScheme.error,
                            errorSupportingTextColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch(uiExceptionHandler) {
                                val (err, pubkey) = validateAndSave(importKey, saveToPasswordManager)
                                if (err == null && pubkey != null) onSetupComplete(pubkey) else error = err
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = importKey.isNotBlank(),
                    ) {
                        Text("インポートして始める")
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratedKeyValues(npub: String, nsec: String) {
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
            Text(
                text = "公開鍵（npub）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            KeyInfoCard(
                title = "公開鍵とは",
                body = "あなたを識別するためのIDです。他のユーザーに公開されます。",
            )
            SelectionContainer {
                Text(
                    text = npub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "秘密鍵（必ずメモしてください）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            KeyInfoCard(
                title = "秘密鍵とは",
                body = "あなた本人であることを証明するための鍵です。秘密鍵を知っている人は、あなたとして投稿や操作ができてしまいます。秘密鍵がないと同じアカウントを使えなくなります。",
                isWarning = true,
            )
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = nsec,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyInfoCard(
    title: String,
    body: String,
    isWarning: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isWarning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isWarning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isWarning) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** (errorMessage, pubkeyHex) を返す。成功時は errorMessage = null、pubkeyHex = 公開鍵 hex */
private suspend fun validateAndSave(
    input: String,
    saveToPasswordManager: suspend (nsec: String, npub: String) -> Unit,
): Pair<String?, String?> {
    return try {
        val hexKey = normalizePrivateKey(input)
        val bytes = hexKey.fromHex()
        val pubkeyHex = derivePublicKey(bytes).toHex()
        val nsec = hexToNsec(hexKey)
        val npub = hexToNpub(pubkeyHex)
        try {
            savePrivateKeyAndVerify(hexKey)
            saveToPasswordManager(nsec, npub)
        } catch (e: Exception) {
            logException("KeySetupScreen", e, "Failed to save imported private key")
            return Pair("秘密鍵を保存できませんでした: ${e.message}", null)
        }
        Pair(null, pubkeyHex)
    } catch (e: Exception) {
        logException("KeySetupScreen", e, "Invalid private key input")
        Pair("無効な秘密鍵です: ${e.message}", null)
    }
}

private suspend fun savePrivateKeyAndVerify(hexKey: String) {
    val normalized = normalizePrivateKey(hexKey)
    KeyStorage.savePrivateKey(normalized)
    val saved = KeyStorage.loadPrivateKey()
        ?: error("保存した秘密鍵を読み戻せませんでした")
    check(normalizePrivateKey(saved) == normalized) {
        "保存した秘密鍵が一致しません"
    }
}
