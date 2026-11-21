package com.nostr.torinos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.ui.components.CenteredInitial
import com.nostr.torinos.ui.components.NetworkImage
import androidx.compose.ui.layout.ContentScale

/** リレー・コミュニティの丸いアイコン（名前の頭文字。無ければサーバーの絵）。 */
@Composable
internal fun ChatRelayIcon(label: String, flavor: GroupFlavor, size: Dp = 40.dp, iconUrl: String? = null) {
    val buzz = flavor == GroupFlavor.Buzz
    val container = if (buzz) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (buzz) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val initial = label.trim().firstOrNull()?.takeIf { it.isLetterOrDigit() }?.uppercaseChar()
    // 透過のある絵だと下の頭文字が透けるので、読めたら頭文字は消す。
    var iconLoaded by remember(iconUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (!iconLoaded) {
            if (initial != null) {
                CenteredInitial(initial.toString(), color = content, style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(Icons.Outlined.Dns, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
            }
        }
        // 絵があれば頭文字の上に重ねる（読めるまで・読めないときは頭文字のまま）。
        if (iconUrl != null) {
            NetworkImage(
                url = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 128,
                animate = false,
                onSuccessSize = { iconLoaded = true },
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/** リレー名・ホスト・Buzz の印を並べた見出し。 */
@Composable
internal fun ChatRelayHeadline(label: String, host: String?, flavor: GroupFlavor, modifier: Modifier = Modifier, iconUrl: String? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatRelayIcon(label, flavor, iconUrl = iconUrl)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (flavor == GroupFlavor.Buzz) {
                    Spacer(Modifier.size(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text("Buzz", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
            }
            if (host != null && host != label) {
                Text(
                    host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 切り替えメニューの長押しから。リレーを非表示にしてよいかを聞く。 */
@Composable
internal fun HideChatRelayDialog(relay: ChatRelaySummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        title = { Text("リレーを非表示") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ChatRelayHeadline(
                        label = relay.label,
                        host = relay.host,
                        flavor = relay.flavor,
                        modifier = Modifier.padding(12.dp),
                        iconUrl = relay.iconUrl,
                    )
                }
                Text(
                    "切り替えメニューと「すべて」の一覧から外し、このリレーのグループを読み込まなくなります。" +
                        "参加はそのままで、この端末だけの設定です。設定の「チャット」からいつでも戻せます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("非表示にする") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
