package com.nostr.torinos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.PrivateMuteListSyncState
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.reflect.KClass

class MuteListViewModel : SafeViewModel() {
    companion object {
        private const val SUB_ID = "mute-list-prof"
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                MuteListViewModel() as T
        }
    }

    private val _profiles = MutableStateFlow<Map<String, NostrProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, NostrProfile>> = _profiles.asStateFlow()

    init {
        launch {
            MuteStore.mutedPubkeys.collect { pubkeys ->
                if (pubkeys.isNotEmpty()) {
                    NostrRepository.subscribe(
                        SUB_ID,
                        NostrFilter(kinds = listOf(0), authors = pubkeys.toList()),
                    )
                }
            }
        }
        launch {
            NostrRepository.events(SUB_ID).collect { event ->
                if (event.kind != 0) return@collect
                val profile = event.toProfile() ?: return@collect
                _profiles.update { it + (event.pubkey to profile) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        NostrRepository.close(SUB_ID)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuteListScreen(
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    viewModel: MuteListViewModel = viewModel(factory = MuteListViewModel.Factory),
) {
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val syncState by MuteStore.syncState.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("ミュートリスト") },
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
                actions = {
                    IconButton(
                        onClick = { MuteStore.refresh() },
                        enabled = !syncState.isRefreshing,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "リレーから同期",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            MuteSyncStatusRow(syncState)
            HorizontalDivider()
            if (mutedPubkeys.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ミュートしているユーザーはいません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                ) {
                    items(mutedPubkeys.toList().sorted(), key = { it }) { pubkey ->
                        val profile = profiles[pubkey]
                        MutedUserRow(
                            pubkey = pubkey,
                            profile = profile,
                            onClick = { onUserClick(pubkey) },
                            onUnmute = { MuteStore.unmute(pubkey) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MuteSyncStatusRow(syncState: PrivateMuteListSyncState) {
    val text = when {
        syncState.isRefreshing -> "リレーから取得中"
        syncState.isPublishing -> "リレーへ保存中"
        syncState.error != null -> syncState.error
        syncState.lastSyncedAt != null -> "リレー同期済み"
        else -> "リレー同期: 未確認"
    }
    val color = if (syncState.error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncState.isRefreshing || syncState.isPublishing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun MutedUserRow(
    pubkey: String,
    profile: NostrProfile?,
    onClick: () -> Unit,
    onUnmute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            modifier = Modifier.size(36.dp),
        )
        ProfileNameText(
            profile = profile,
            fallback = shortPubkey(pubkey),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUnmute) {
            Text("解除", color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "…" + pubkey.takeLast(8)
