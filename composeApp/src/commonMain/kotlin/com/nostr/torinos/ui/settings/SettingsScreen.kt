package com.nostr.torinos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.crypto.hexToNpub

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ownPubkey: String,
    onBack: () -> Unit = {},
    onAccountCleared: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var nsec by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    val npub = remember(ownPubkey) {
        runCatching { hexToNpub(ownPubkey) }.getOrDefault(ownPubkey)
    }

    LaunchedEffect(viewModel) {
        viewModel.secretKeyEvent.collect { nsec = it }
    }

    LaunchedEffect(state.isSecretKeyVisible) {
        if (!state.isSecretKeyVisible) nsec = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                KeySection(
                    npub = npub,
                    nsec = nsec,
                    isSecretVisible = state.isSecretKeyVisible,
                    error = state.keyError,
                    onShowSecret = viewModel::showSecretKey,
                    onHideSecret = viewModel::hideSecretKey,
                )
                HorizontalDivider()
            }
            item {
                AccountSection(
                    isProcessing = state.isAccountActionProcessing,
                    error = state.accountActionError,
                    onLogoutClick = {
                        viewModel.clearAccountActionError()
                        showLogoutDialog = true
                    },
                    onDeleteAccountClick = {
                        viewModel.clearAccountActionError()
                        showDeleteAccountDialog = true
                    },
                )
            }
        }
    }

    if (showLogoutDialog) {
        ConfirmAccountDialog(
            title = "ログアウト",
            text = "この端末に保存されている秘密鍵を削除してログアウトします。投稿するには再度秘密鍵のインポートが必要です。",
            confirmText = "ログアウト",
            isProcessing = state.isAccountActionProcessing,
            onDismiss = { showLogoutDialog = false },
            onConfirm = {
                viewModel.clearAccount {
                    showLogoutDialog = false
                    onAccountCleared()
                }
            },
        )
    }

    if (showDeleteAccountDialog) {
        ConfirmAccountDialog(
            title = "アカウント削除",
            text = "この端末に保存されている秘密鍵を削除します。Nostr上に公開済みの投稿やプロフィールは削除されません。秘密鍵を控えていない場合、このアカウントは復元できません。",
            confirmText = "削除",
            isProcessing = state.isAccountActionProcessing,
            destructive = true,
            onDismiss = { showDeleteAccountDialog = false },
            onConfirm = {
                viewModel.clearAccount {
                    showDeleteAccountDialog = false
                    onAccountCleared()
                }
            },
        )
    }
}

@Composable
private fun KeySection(
    npub: String,
    nsec: String?,
    isSecretVisible: Boolean,
    error: String?,
    onShowSecret: () -> Unit,
    onHideSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "鍵",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "公開鍵",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = npub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "秘密鍵",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = if (isSecretVisible) onHideSecret else onShowSecret,
            ) {
                Text(if (isSecretVisible) "隠す" else "表示")
            }
        }

        if (isSecretVisible && nsec != null) {
            SelectionContainer {
                Text(
                    text = nsec,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                text = "秘密鍵は表示操作をしたときだけ読み込みます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AccountSection(
    isProcessing: Boolean,
    error: String?,
    onLogoutClick: () -> Unit,
    onDeleteAccountClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "アカウント",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "この端末に保存されている秘密鍵を管理します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onLogoutClick,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text("ログアウト")
        }
        OutlinedButton(
            onClick = onDeleteAccountClick,
            enabled = !isProcessing,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text("アカウント削除")
        }
    }
}

@Composable
private fun ConfirmAccountDialog(
    title: String,
    text: String,
    confirmText: String,
    isProcessing: Boolean,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isProcessing,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isProcessing,
            ) {
                Text("キャンセル")
            }
        },
    )
}
