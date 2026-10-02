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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    ownPubkey: String?,
    onDismiss: () -> Unit,
    onAccountChanged: (String?) -> Unit,
    onAddAccountClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        key = "profile-account-switcher",
    ) { SettingsViewModel() },
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(ownPubkey) {
        viewModel.clearAccountActionError()
        viewModel.refreshAccounts()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!state.isAccountActionProcessing) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("アカウント切り替え", style = MaterialTheme.typography.titleLarge)
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
                            isLoggedOut = account.isLoggedOut,
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
    }
}

@Composable
private fun AccountSwitchRow(
    pubkey: String,
    npub: String,
    profile: NostrProfile?,
    active: Boolean,
    isLoggedOut: Boolean,
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
            ProfileNameText(
                profile = profile,
                fallback = shortNpub,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shortNpub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isLoggedOut) {
                Text(
                    text = "ログアウト済み・タップしてログイン",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
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

