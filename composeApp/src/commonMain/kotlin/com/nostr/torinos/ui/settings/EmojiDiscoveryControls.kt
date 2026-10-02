package com.nostr.torinos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun emojiAdoptionLabel(count: Int, hasData: Boolean, loading: Boolean): String = when {
    !hasData -> "登録人数未取得"
    count == 0 -> "取得した情報では登録者なし"
    loading -> "取得済みの情報で${count}人が登録"
    else -> "フォロー中の${count}人が登録"
}

@Composable
internal fun EmojiDiscoveryControls(
    sort: EmojiSetSort,
    onSort: (EmojiSetSort) -> Unit,
    count: Int,
    loading: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    notice: String?,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onStop: (() -> Unit)?,
    pendingOrder: Boolean,
    onApplyOrder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                TextButton(onClick = { expanded = true }) { Text("${sort.label} ▾") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    EmojiSetSort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            expanded = false; onSort(option)
                        })
                    }
                }
            }
            Text("${count}セット", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleSearch) {
                    Icon(Icons.Default.Search, contentDescription = if (searchOpen) "検索を閉じる" else "検索",
                        tint = if (searchOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                TextButton(onClick = onRefresh, enabled = enabled && !loading) { Text(if (loading) "取得中" else "更新") }
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onStop != null || pendingOrder) {
            Row {
                onStop?.let { stop -> TextButton(onClick = stop) { Text("取得を停止") } }
                if (pendingOrder) TextButton(onClick = onApplyOrder) { Text("並び順を更新") }
            }
        }
    }
}
