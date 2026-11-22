package com.nostr.torinos.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 名前の横に出す Bot の印。チャットの「bot を隠す」と同じアイコンを使い、線で囲んで小さく出す。
 * 名前が長くても欠けないよう、名前の側を `weight(1f, fill = false)` で縮める。
 */
@Composable
fun BotBadge(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = "Bot" }
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        Text("BOT", color = color, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
    }
}
