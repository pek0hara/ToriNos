package com.nostr.torinos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.nostr.torinos.FooterMenuLabel

/**
 * フッターと「メニュー」に並べる項目と、その順。項目ごとにどちらへ置くかを入れ替えられる。変更はその場で反映する。
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
        FooterGroup(
            title = "フッター",
            items = config.barItems,
            moveLabel = "メニューへ",
            config = config,
            onConfigChange = onConfigChange,
        )
        Spacer(Modifier.height(12.dp))
        FooterGroup(
            title = FooterMenuLabel,
            items = config.menuItems,
            moveLabel = "フッターへ",
            config = config,
            onConfigChange = onConfigChange,
        )
        Text(
            text = "「${FooterMenuLabel}」はフッターの右端に置きます。メニューに入れる項目が無いときは出しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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
private fun FooterGroup(
    title: String,
    items: List<FooterItem>,
    moveLabel: String,
    config: FooterConfig,
    onConfigChange: (FooterConfig) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    if (items.isEmpty()) {
        Text(
            text = "なし",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    items.forEach { item ->
        FooterItemRow(
            item = item,
            canMoveUp = config.canMove(item, -1),
            canMoveDown = config.canMove(item, 1),
            moveLabel = moveLabel,
            canChangePlacement = config.isInMenu(item) || item.canMoveToMenu,
            onMove = { delta -> onConfigChange(config.move(item, delta)) },
            onChangePlacement = { onConfigChange(config.togglePlacement(item)) },
        )
    }
}

@Composable
private fun FooterItemRow(
    item: FooterItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveLabel: String,
    canChangePlacement: Boolean,
    onMove: (Int) -> Unit,
    onChangePlacement: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "${item.label}を上へ")
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "${item.label}を下へ")
        }
        // フィードはフッターから動かせないが、行の幅を揃えるためにボタンの場所は空けておく。
        TextButton(
            onClick = onChangePlacement,
            enabled = canChangePlacement,
            modifier = Modifier
                .width(FooterPlacementButtonWidth)
                .semantics { if (!canChangePlacement) stateDescription = "常にフッター" },
        ) {
            Text(if (canChangePlacement) moveLabel else "固定", maxLines = 1)
        }
    }
}

private val FooterPlacementButtonWidth = 104.dp
