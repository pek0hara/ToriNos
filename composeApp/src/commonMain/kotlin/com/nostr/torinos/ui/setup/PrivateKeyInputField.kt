package com.nostr.torinos.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal const val PrivateKeyInputLabel = "秘密鍵（nsec1... または hex）"

/**
 * 秘密鍵(nsec または hex)の入力欄。入力値は伏せて表示する。
 * Web 版は Compose の入力欄だとスマホで貼り付けできないため、ブラウザの input 要素を使う。
 */
@Composable
internal expect fun PrivateKeyInputField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
)
