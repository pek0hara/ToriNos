package com.nostr.torinos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.ui.relay.RelaySettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ownPubkey: String?,
    onBack: () -> Unit = {},
    onAccountChanged: (String?) -> Unit = {},
    onAddAccountClick: () -> Unit = {},
    onMuteListClick: () -> Unit = {},
    onNgWordClick: () -> Unit = {},
    onCustomEmojiClick: () -> Unit = {},
    relayViewModel: RelaySettingsViewModel = viewModel(key = "settings-relays") { RelaySettingsViewModel() },
) {
    val accountViewModel = if (ownPubkey != null) {
        viewModel(key = "settings-account") { SettingsViewModel() }
    } else {
        null
    }
    val state by accountViewModel?.state?.collectAsState()
        ?: remember { mutableStateOf(SettingsState()) }
    val relayEntries by relayViewModel.entries.collectAsState()
    var nsec by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var selectedVanishRelays by remember { mutableStateOf<Set<String>>(emptySet()) }
    val npub = remember(ownPubkey) {
        ownPubkey?.let { pubkey -> runCatching { hexToNpub(pubkey) }.getOrDefault(pubkey) }
    }
    val clipboard = LocalClipboard.current
    var nsecClipboardCopied by remember { mutableStateOf(false) }

    LaunchedEffect(accountViewModel) {
        accountViewModel?.secretKeyEvent?.collect { nsec = it }
    }

    LaunchedEffect(accountViewModel) {
        accountViewModel?.secretKeyClipboardEvent?.collect { key ->
            clipboard.setPlainText(key)
            nsecClipboardCopied = true
        }
    }

    LaunchedEffect(nsecClipboardCopied) {
        if (nsecClipboardCopied) {
            delay(2000)
            nsecClipboardCopied = false
        }
    }

    LaunchedEffect(accountViewModel, ownPubkey) {
        accountViewModel?.refreshAccounts()
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                FilterSection(
                    onMuteListClick = onMuteListClick,
                    onNgWordClick = onNgWordClick,
                    onCustomEmojiClick = onCustomEmojiClick,
                )
                HorizontalDivider()
            }
            val account = accountViewModel
            if (npub != null && account != null) {
                item {
                    AccountSwitcherSection(
                        accounts = state.accounts,
                        profiles = state.profiles,
                        activePubkey = ownPubkey,
                        isProcessing = state.isAccountActionProcessing,
                        onSwitch = { pubkey -> account.switchAccount(pubkey, onAccountChanged) },
                        onAddAccountClick = onAddAccountClick,
                    )
                    HorizontalDivider()
                }
                item {
                    KeySection(
                        npub = npub,
                        nsec = nsec,
                        isSecretVisible = state.isSecretKeyVisible,
                        nsecClipboardCopied = nsecClipboardCopied,
                        error = state.keyError,
                        onShowSecret = account::showSecretKey,
                        onHideSecret = account::hideSecretKey,
                        onCopySecret = account::copySecretKey,
                    )
                    HorizontalDivider()
                }
                item {
                    AccountSection(
                        isProcessing = state.isAccountActionProcessing,
                        error = state.accountActionError,
                        onLogoutClick = {
                            account.clearAccountActionError()
                            showLogoutDialog = true
                        },
                        onDeleteAccountClick = {
                            account.clearAccountActionError()
                            selectedVanishRelays = relayEntries
                                .filter { it.enabled }
                                .map { it.url }
                                .toSet()
                                .ifEmpty { relayEntries.map { it.url }.toSet() }
                            showDeleteAccountDialog = true
                        },
                    )
                }
            }
        }
    }

    if (showLogoutDialog) {
        ConfirmAccountDialog(
            title = "ログアウト",
            text = "現在のアカウントの秘密鍵をこの端末から削除します。ほかに保存済みアカウントがある場合は、そのアカウントへ切り替わります。",
            confirmText = "ログアウト",
            isProcessing = state.isAccountActionProcessing,
            onDismiss = { showLogoutDialog = false },
            onConfirm = {
                accountViewModel?.clearAccount {
                    showLogoutDialog = false
                    onAccountChanged(it)
                }
            },
        )
    }

    if (showDeleteAccountDialog) {
        ConfirmDeleteAccountDialog(
            relayEntries = relayEntries,
            selectedRelayUrls = selectedVanishRelays,
            isProcessing = state.isAccountActionProcessing,
            error = state.accountActionError,
            onDismiss = { showDeleteAccountDialog = false },
            onRelayToggle = { url, checked ->
                selectedVanishRelays = if (checked) {
                    selectedVanishRelays + url
                } else {
                    selectedVanishRelays - url
                }
            },
            onConfirm = {
                accountViewModel?.requestVanishAndClearAccount(selectedVanishRelays) {
                    showDeleteAccountDialog = false
                    onAccountChanged(it)
                }
            },
        )
    }
}

@Composable
private fun AccountSwitcherSection(
    accounts: List<StoredAccount>,
    profiles: Map<String, NostrProfile>,
    activePubkey: String?,
    isProcessing: Boolean,
    onSwitch: (String) -> Unit,
    onAddAccountClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "ログイン中のアカウント",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        accounts.forEach { account ->
            val profile = profiles[account.pubkeyHex]
            val shortNpub = account.npub.let {
                if (it.length > 16) it.take(10) + "..." + it.takeLast(6) else it
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isProcessing && account.pubkeyHex != activePubkey) {
                        onSwitch(account.pubkeyHex)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AvatarCircle(
                    pubkey = account.pubkeyHex,
                    name = profile?.bestName,
                    pictureUrl = profile?.picture,
                    size = 36,
                )
                Column(modifier = Modifier.weight(1f)) {
                    ProfileNameText(
                        profile = profile,
                        fallback = account.pubkeyHex.take(8),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shortNpub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (account.pubkeyHex == activePubkey) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        OutlinedButton(
            onClick = onAddAccountClick,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("アカウントを追加")
        }
    }
}

@Composable
private fun ConfirmDeleteAccountDialog(
    relayEntries: List<RelayEntry>,
    selectedRelayUrls: Set<String>,
    isProcessing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRelayToggle: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text("アカウント削除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("選択したリレーへ NIP-62 の削除要求を送信してから、この端末に保存されている秘密鍵を削除します。対応していないリレーやキャッシュ済みデータからの削除は保証されません。")
                Text(
                    text = "送信先リレー",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(relayEntries.size) { index ->
                        val entry = relayEntries[index]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = entry.url in selectedRelayUrls,
                                onCheckedChange = { checked -> onRelayToggle(entry.url, checked) },
                                enabled = !isProcessing,
                            )
                            Text(
                                text = entry.url,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isProcessing && selectedRelayUrls.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("削除要求を送信")
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

@Composable
private fun KeySection(
    npub: String,
    nsec: String?,
    isSecretVisible: Boolean,
    nsecClipboardCopied: Boolean,
    error: String?,
    onShowSecret: () -> Unit,
    onHideSecret: () -> Unit,
    onCopySecret: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var npubCopied by remember { mutableStateOf(false) }
    var nsecDirectCopied by remember { mutableStateOf(false) }
    val nsecCopied = nsecClipboardCopied || nsecDirectCopied

    LaunchedEffect(npubCopied) {
        if (npubCopied) {
            delay(2000)
            npubCopied = false
        }
    }
    LaunchedEffect(nsecDirectCopied) {
        if (nsecDirectCopied) {
            delay(2000)
            nsecDirectCopied = false
        }
    }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = npub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        clipboard.setPlainText(npub)
                        npubCopied = true
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (npubCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "コピー",
                    modifier = Modifier.size(18.dp),
                    tint = if (npubCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isSecretVisible && nsec != null) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nsec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Text(
                    text = "●".repeat(24),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(
                onClick = {
                    if (isSecretVisible && nsec != null) {
                        coroutineScope.launch {
                            clipboard.setPlainText(nsec)
                            nsecDirectCopied = true
                        }
                    } else {
                        onCopySecret()
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (nsecCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "コピー",
                    modifier = Modifier.size(18.dp),
                    tint = if (nsecCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
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
            text = "現在のアカウントの秘密鍵を管理します。",
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

@Composable
private fun FilterSection(
    onMuteListClick: () -> Unit,
    onNgWordClick: () -> Unit,
    onCustomEmojiClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "フィルタリング",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FilterNavRow(label = "ミュートリスト", onClick = onMuteListClick)
        FilterNavRow(label = "NGワード", onClick = onNgWordClick)
        FilterNavRow(label = "カスタム絵文字", onClick = onCustomEmojiClick)
    }
}

@Composable
private fun FilterNavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
