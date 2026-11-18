package com.nostr.torinos.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * 文字ラベルだけのタブ。下線だけでは選択中が分かりにくいので、選択中は太字、選択していないタブは文字を薄くする。
 * アプリ内のタブはこれで見た目を揃える。
 */
@Composable
fun AppTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    selectedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        selectedContentColor = selectedContentColor,
        unselectedContentColor = unselectedContentColor,
        text = {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
    )
}
