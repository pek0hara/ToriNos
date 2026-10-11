package com.nostr.torinos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nostr.torinos.FooterConfig
import com.nostr.torinos.FooterItem

/**
 * フッターの並び順と表示する項目。変更はその場でフッターに反映する。
 */
@Composable
internal fun FooterSettingsSection(
    config: FooterConfig,
    onConfigChange: (FooterConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = "フッター",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        config.order.forEachIndexed { index, item ->
            FooterItemRow(
                item = item,
                visible = config.isVisible(item),
                canMoveUp = index > 0,
                canMoveDown = index < config.order.lastIndex,
                onMove = { delta -> onConfigChange(config.move(item, delta)) },
                onToggleVisible = { onConfigChange(config.toggleVisible(item)) },
            )
        }
        TextButton(
            onClick = { onConfigChange(FooterConfig.Default) },
            enabled = config != FooterConfig.Default,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("初期設定に戻す")
        }
    }
}

@Composable
private fun FooterItemRow(
    item: FooterItem,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onToggleVisible: () -> Unit,
) {
    val contentColor = if (visible) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "${item.label}を上へ")
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "${item.label}を下へ")
        }
        if (item.canHide) {
            Switch(checked = visible, onCheckedChange = { onToggleVisible() })
        } else {
            // ホームは常に表示する。無効表示にするとダークテーマでオフに見えるので、操作できないオンとして描く。
            Switch(
                checked = true,
                onCheckedChange = null,
                modifier = Modifier.semantics { stateDescription = "常に表示" },
            )
        }
    }
}
