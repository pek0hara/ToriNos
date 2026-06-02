package com.nostr.torinos.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.ProfileNameText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListScreen(
    mode: FollowListMode,
    ownPubkey: String,
    onBack: () -> Unit,
    onUserClick: (pubkey: String) -> Unit,
    viewModel: FollowListViewModel = viewModel(
        key = "${mode.name}-$ownPubkey",
    ) { FollowListViewModel(mode, ownPubkey) },
) {
    val state by viewModel.state.collectAsState()
    val title = if (mode == FollowListMode.FOLLOWING) "フォロー" else "フォロワー"
    var query by remember(mode, ownPubkey) { mutableStateOf("") }
    val filteredEntries = remember(state.entries, query) {
        state.entries.filterByQuery(query)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
        if (state.isLoading && state.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else if (state.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (mode == FollowListMode.FOLLOWING) "フォローしているユーザーはいません"
                    else "フォロワーはいません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item(contentType = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        singleLine = true,
                        placeholder = { Text("ユーザー検索") },
                    )
                    HorizontalDivider()
                }

                if (filteredEntries.isEmpty()) {
                    item(contentType = "empty-search") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "一致するユーザーはいません",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(filteredEntries, key = { it.first }) { (pubkey, profile) ->
                    UserRow(
                        pubkey = pubkey,
                        profile = profile,
                        onClick = { onUserClick(pubkey) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun List<Pair<String, NostrProfile?>>.filterByQuery(query: String): List<Pair<String, NostrProfile?>> {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return this
    return filter { (pubkey, profile) ->
        val fields = listOfNotNull(
            pubkey,
            runCatching { hexToNpub(pubkey) }.getOrNull(),
            profile?.name,
            profile?.displayName,
            profile?.nip05,
            profile?.about,
        )
        fields.any { it.lowercase().contains(normalized) }
    }
}

@Composable
private fun UserRow(pubkey: String, profile: NostrProfile?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 44,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProfileNameText(
                profile = profile,
                fallback = pubkey.take(8) + "…" + pubkey.takeLast(8),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            profile?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                Text(
                    text = nip05,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } ?: profile?.about?.takeIf { it.isNotBlank() }?.let { about ->
                Text(
                    text = about,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
