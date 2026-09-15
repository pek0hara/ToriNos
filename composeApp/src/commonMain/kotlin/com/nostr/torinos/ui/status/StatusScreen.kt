package com.nostr.torinos.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import com.nostr.torinos.ui.service.serviceTabSwipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    ownPubkey: String?,
    ownProfile: NostrProfile? = null,
    showComposer: Boolean,
    onComposerShown: () -> Unit,
    onUserClick: (String) -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenRelaySettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    selectedServiceTab: ServiceTab = ServiceTab.Status,
    onServiceTabSelected: (ServiceTab) -> Unit = {},
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedStatusRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedStatusRelayUrl(relays.firstOrNull())
        }
    }

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        StatusRelayPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: StatusViewModel = accountSessionViewModel(
        key = "status-$activeRelayUrl",
    ) { accountSession ->
        StatusViewModel(relayUrl = activeRelayUrl, accountSession = accountSession)
    }
    val state by viewModel.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(showComposer) {
        if (showComposer) {
            showDialog = true
            onComposerShown()
        }
    }
    LaunchedEffect(state.publishCompletedCount) {
        if (state.publishCompletedCount > 0) {
            showDialog = false
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(headerBackgroundColor)) {
                AppTopBar(
                    navigationIcon = {
                        if (ownPubkey != null) {
                            IconButton(onClick = onOpenProfile) {
                                AvatarCircle(
                                    pubkey = ownPubkey,
                                    name = ownProfile?.bestName,
                                    pictureUrl = ownProfile?.picture,
                                    size = 32,
                                )
                            }
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                text = selectedRelayUrl?.relayDisplayName() ?: "—",
                                modifier = Modifier.weight(1f, fill = false),
                                color = headerContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { showRelayMenu = !showRelayMenu }) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "リレー切り替え",
                                    tint = headerContentColor,
                                )
                            }
                            DropdownMenu(
                                expanded = showRelayMenu,
                                onDismissRequest = { showRelayMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("リレー設定") },
                                    onClick = {
                                        showRelayMenu = false
                                        onOpenRelaySettings()
                                    },
                                )
                                relays.forEach { url ->
                                    DropdownMenuItem(
                                        text = { Text(url.relayDisplayName()) },
                                        onClick = {
                                            RelayStore.setSelectedStatusRelayUrl(url)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (url == selectedRelayUrl) {
                                            {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                )
                                            }
                                        } else null,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "設定",
                                tint = headerContentColor,
                            )
                        }
                    },
                )
                ServiceTabRow(
                    selectedTab = selectedServiceTab,
                    onTabSelected = onServiceTabSelected,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .serviceTabSwipe(
                    selectedTab = selectedServiceTab,
                    onTabSelected = onServiceTabSelected,
                ),
        ) {
            CategoryFilterRow(
                availableCategories = state.availableCategories,
                selectedCategories = state.selectedCategories,
                onToggle = viewModel::toggleCategory,
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isInitialLoad && state.statuses.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.statuses.isEmpty() -> {
                        Text(
                            text = "ステータスがありません",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = state.statuses,
                                key = { it.key },
                                contentType = { "status" },
                            ) { status ->
                                StatusRow(
                                    status = status,
                                    profile = state.profiles[status.event.pubkey],
                                    onUserClick = { onUserClick(status.event.pubkey) },
                                )
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        val currentStatus = state.ownGeneralStatus
        StatusComposerSheet(
            title = "ステータス",
            actionLabel = if (currentStatus == null) "追加" else "保存",
            initialStatusTag = currentStatus?.statusTag ?: "general",
            initialContent = currentStatus?.event?.content.orEmpty(),
            initialExpiration = currentStatus?.expiration,
            initialReferenceUrl = currentStatus?.referenceUrls?.firstOrNull().orEmpty(),
            isPublishing = state.isPublishing,
            errorMessage = state.errorMessage,
            onDismiss = {
                showDialog = false
                viewModel.clearError()
            },
            onSubmit = { tag, content, expiration, referenceUrl ->
                viewModel.publishStatus(tag, content, expiration, referenceUrl)
            },
        )
    }
}

@Composable
private fun StatusRelayPendingContent(
    isLoaded: Boolean,
    hasEnabledRelays: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !isLoaded -> CircularProgressIndicator()
            !hasEnabledRelays -> Text(
                text = "有効なリレーがありません",
                color = MaterialTheme.colorScheme.error,
            )
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun CategoryFilterRow(
    availableCategories: List<String>,
    selectedCategories: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(availableCategories) { category ->
            FilterChip(
                selected = category in selectedCategories,
                onClick = { onToggle(category) },
                label = { StatusTagText(category) },
            )
        }
    }
}

@Composable
private fun StatusRow(
    status: UserStatus,
    profile: NostrProfile?,
    onUserClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUserClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            pubkey = status.event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 42,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileNameText(
                    profile = profile,
                    fallback = status.event.pubkey.take(8) + "…" + status.event.pubkey.takeLast(8),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTimestamp(status.event.createdAt, todayTimeOnly = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusTagLabel(status.statusTag)
                LinkedText(
                    text = status.event.content,
                    style = MaterialTheme.typography.bodyMedium,
                    customEmojis = status.customEmojis,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            status.referenceUrls.forEach { url ->
                LinkedText(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            status.expiration?.let {
                Text(
                    text = "終了 ${formatTimestamp(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusTagLabel(statusTag: String) {
    if (statusTag.isDefaultStatusTag()) {
        StatusTagText(statusTag)
    } else {
        AssistChip(
            onClick = {},
            label = { StatusTagText(statusTag) },
        )
    }
}

@Composable
private fun StatusTagText(statusTag: String) {
    Text(
        text = statusTag.statusTagDisplayLabel(),
        style = if (statusTag.isDefaultStatusTag()) {
            MaterialTheme.typography.bodyLarge
        } else {
            MaterialTheme.typography.labelSmall
        },
    )
}


private fun String.statusTagDisplayLabel(): String =
    when {
        equals("general", ignoreCase = true) -> "💬"
        equals("music", ignoreCase = true) -> "♫"
        else -> this
    }

private fun String.isDefaultStatusTag(): Boolean =
    equals("general", ignoreCase = true) || equals("music", ignoreCase = true)


private fun String.relayDisplayName(): String =
    removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")
