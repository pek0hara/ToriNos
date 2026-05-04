package com.nostr.torinos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.relay.RelaySettingsViewModel

private enum class QuickSettingsPane {
    Menu,
    AccountSwitcher,
    RelayList,
    MuteList,
}

@Composable
fun QuickSettingsDialogs(
    open: Boolean,
    ownPubkey: String?,
    onOpenChange: (Boolean) -> Unit,
    onAccountChanged: (String?) -> Unit,
    onAddAccountClick: () -> Unit,
    onOpenAllSettings: () -> Unit,
) {
    var pane by remember { mutableStateOf(QuickSettingsPane.Menu) }

    LaunchedEffect(open) {
        if (open) pane = QuickSettingsPane.Menu
    }

    if (!open) return

    when (pane) {
        QuickSettingsPane.Menu -> QuickSettingsMenuDialog(
            onDismiss = { onOpenChange(false) },
            onAccountSwitcherClick = { pane = QuickSettingsPane.AccountSwitcher },
            onRelayListClick = { pane = QuickSettingsPane.RelayList },
            onMuteListClick = { pane = QuickSettingsPane.MuteList },
            onOpenAllSettings = {
                onOpenChange(false)
                onOpenAllSettings()
            },
        )

        QuickSettingsPane.AccountSwitcher -> AccountSwitcherDialog(
            ownPubkey = ownPubkey,
            onDismiss = { onOpenChange(false) },
            onBackToMenu = { pane = QuickSettingsPane.Menu },
            onAccountChanged = {
                onOpenChange(false)
                onAccountChanged(it)
            },
            onAddAccountClick = {
                onOpenChange(false)
                onAddAccountClick()
            },
        )

        QuickSettingsPane.RelayList -> RelayListDialog(
            onDismiss = { onOpenChange(false) },
            onBackToMenu = { pane = QuickSettingsPane.Menu },
        )

        QuickSettingsPane.MuteList -> MuteListDialog(
            onDismiss = { onOpenChange(false) },
            onBackToMenu = { pane = QuickSettingsPane.Menu },
        )
    }
}

@Composable
private fun QuickSettingsMenuDialog(
    onDismiss: () -> Unit,
    onAccountSwitcherClick: () -> Unit,
    onRelayListClick: () -> Unit,
    onMuteListClick: () -> Unit,
    onOpenAllSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                QuickSettingsMenuRow("アカウント切り替え", onAccountSwitcherClick)
                QuickSettingsMenuRow("リレー一覧", onRelayListClick)
                QuickSettingsMenuRow("ミュートリスト", onMuteListClick)
                QuickSettingsMenuRow("すべての設定", onOpenAllSettings)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun QuickSettingsMenuRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AccountSwitcherDialog(
    ownPubkey: String?,
    onDismiss: () -> Unit,
    onBackToMenu: () -> Unit,
    onAccountChanged: (String?) -> Unit,
    onAddAccountClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel(key = "quick-settings-account") { SettingsViewModel() },
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshAccounts()
    }

    AlertDialog(
        onDismissRequest = { if (!state.isAccountActionProcessing) onDismiss() },
        title = { Text("アカウント切り替え") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.accounts.isEmpty()) {
                    Text(
                        text = "保存済みアカウントがありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(state.accounts, key = { it.pubkeyHex }) { account ->
                            val profile = state.profiles[account.pubkeyHex]
                            AccountSwitchRow(
                                pubkey = account.pubkeyHex,
                                npub = account.npub,
                                profile = profile,
                                active = account.pubkeyHex == ownPubkey,
                                enabled = !state.isAccountActionProcessing,
                                onClick = {
                                    viewModel.switchAccount(account.pubkeyHex, onAccountChanged)
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                if (state.accountActionError != null) {
                    Text(
                        text = state.accountActionError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    onClick = onAddAccountClick,
                    enabled = !state.isAccountActionProcessing,
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onBackToMenu, enabled = !state.isAccountActionProcessing) {
                Text("戻る")
            }
            TextButton(onClick = onDismiss, enabled = !state.isAccountActionProcessing) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun AccountSwitchRow(
    pubkey: String,
    npub: String,
    profile: NostrProfile?,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shortNpub = if (npub.length > 16) npub.take(10) + "..." + npub.takeLast(6) else npub

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !active, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 36,
        )
        Column(modifier = Modifier.weight(1f)) {
            if (profile?.bestName != null) {
                Text(
                    text = profile.bestName!!,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = shortNpub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "選択中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RelayListDialog(
    onDismiss: () -> Unit,
    onBackToMenu: () -> Unit,
    viewModel: RelaySettingsViewModel = viewModel(key = "quick-settings-relays") { RelaySettingsViewModel() },
) {
    val relayEntries by viewModel.entries.collectAsState()
    var relayInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リレー一覧") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = relayInput,
                        onValueChange = { relayInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("wss://relay.example.com") },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            if (relayInput.isNotBlank()) {
                                viewModel.add(relayInput)
                                relayInput = ""
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "追加")
                    }
                }

                if (relayEntries.isEmpty()) {
                    Text(
                        text = "リレーが設定されていません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(relayEntries, key = { it.url }) { entry ->
                            QuickRelayRow(
                                entry = entry,
                                onToggle = { enabled -> viewModel.setEnabled(entry.url, enabled) },
                                onDelete = { viewModel.remove(entry.url) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onBackToMenu) {
                Text("戻る")
            }
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun QuickRelayRow(
    entry: RelayEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = entry.enabled,
            onCheckedChange = onToggle,
        )
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MuteListDialog(
    onDismiss: () -> Unit,
    onBackToMenu: () -> Unit,
    viewModel: MuteListViewModel = viewModel(factory = MuteListViewModel.Factory),
) {
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ミュートリスト") },
        text = {
            if (mutedPubkeys.isEmpty()) {
                Text(
                    text = "ミュートしているユーザーはいません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(mutedPubkeys.toList(), key = { it }) { pubkey ->
                        val profile = profiles[pubkey]
                        MutedUserDialogRow(
                            pubkey = pubkey,
                            profile = profile,
                            onUnmute = { MuteStore.unmute(pubkey) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onBackToMenu) {
                Text("戻る")
            }
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun MutedUserDialogRow(
    pubkey: String,
    profile: NostrProfile?,
    onUnmute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            modifier = Modifier.size(36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.bestName ?: shortPubkeyForDialog(pubkey),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile?.bestName != null) {
                Text(
                    text = shortPubkeyForDialog(pubkey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = onUnmute,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("解除")
        }
    }
}

private fun shortPubkeyForDialog(pubkey: String): String = pubkey.take(8) + "..." + pubkey.takeLast(8)
