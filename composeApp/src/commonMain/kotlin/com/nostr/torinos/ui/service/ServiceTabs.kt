package com.nostr.torinos.ui.service

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class ServiceTab(val label: String, val icon: ImageVector) {
    Articles("記事", Icons.AutoMirrored.Filled.Article),
    Channels("チャンネル", Icons.Default.Forum),
    Status("ステータス", Icons.Default.Sms),
    Emojis("絵文字", Icons.Default.EmojiEmotions),
}

/** サービス画面のヘッダー下端。サービスの切り替えはフッターで行う。 */
@Composable
fun ServiceHeaderDivider() {
    HorizontalDivider()
}
