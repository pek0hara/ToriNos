package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * 投稿や記事の「…」から開く操作メニュー。
 * 項目にアイコンを添え、区切り線でまとまりを分ける。高さは標準より詰めて、一覧の上でも場所を取りすぎないようにする。
 */
@Composable
internal fun ActionDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    /** 幅の指定。絵文字の並びなど独自の中身を入れるときは、中身側で幅を決めるため [Modifier] を渡す。 */
    modifier: Modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        content = content,
    )
}

@Composable
internal fun ActionDropdownMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    /** 削除・通報など取り消しにくい操作。文字とアイコンをエラー色にする。 */
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val baseColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val baseIconColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (enabled) baseColor else baseColor.copy(alpha = 0.38f)
    val iconColor = if (enabled) baseIconColor else baseIconColor.copy(alpha = 0.38f)
    DropdownMenuItem(
        enabled = enabled,
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = color) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp)) },
        modifier = Modifier.padding(horizontal = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

/** メニュー内のまとまりを分ける線。 */
@Composable
internal fun ActionDropdownMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}
