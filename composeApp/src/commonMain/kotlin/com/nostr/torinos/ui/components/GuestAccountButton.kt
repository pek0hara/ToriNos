package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 鍵が無いときにヘッダー左上の自分のアバターの位置へ出す。
 * 設定の入口はアバター → プロフィールに集めているので、未ログインでもここから設定へ行けるようにする。
 */
@Composable
internal fun GuestAccountButton(
    onLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    size: Int = 32,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = "ログインと設定",
                tint = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.size(size.dp),
            )
        }
        HeaderDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            HeaderDropdownMenuItem(
                label = "ログイン",
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onLogin()
                },
            )
            HeaderDropdownMenuItem(
                label = "設定",
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}
