package com.nostr.torinos.ui.settings

import androidx.compose.ui.platform.Clipboard

expect suspend fun Clipboard.setPlainText(text: String)

/** 秘密鍵など、クリップボードのプレビューや履歴に表示させたくない文字列をコピーする。 */
expect suspend fun Clipboard.setSensitiveText(text: String)
