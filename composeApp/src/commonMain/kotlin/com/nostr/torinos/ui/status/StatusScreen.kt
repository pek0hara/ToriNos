package com.nostr.torinos.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import com.nostr.torinos.ui.service.serviceTabSwipe
import kotlin.time.Clock

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

    val viewModel: StatusViewModel = viewModel(
        key = "status-$activeRelayUrl",
        factory = viewModelFactory { initializer { StatusViewModel(relayUrl = activeRelayUrl) } },
    )
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
                            IconButton(onClick = { showRelayMenu = true }) {
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
            HorizontalDivider()
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
                                    isOwn = status.event.pubkey == ownPubkey,
                                    onUserClick = { onUserClick(status.event.pubkey) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        StatusComposerDialog(
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(availableCategories) { category ->
            FilterChip(
                selected = category in selectedCategories,
                onClick = { onToggle(category) },
                label = { Text(category, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun StatusRow(
    status: UserStatus,
    profile: NostrProfile?,
    isOwn: Boolean,
    onUserClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUserClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCircle(
            pubkey = status.event.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 32,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(status.statusTag, style = MaterialTheme.typography.labelSmall) },
                )
                LinkedText(
                    text = status.event.content,
                    style = MaterialTheme.typography.bodyLarge,
                    customEmojis = status.customEmojis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isOwn) {
                    AssistChip(onClick = {}, label = { Text("自分") })
                }
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
private fun StatusComposerDialog(
    isPublishing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (statusTag: String, content: String, expiration: Long?, referenceUrl: String?) -> Unit,
) {
    var statusTag by remember { mutableStateOf("general") }
    var content by remember { mutableStateOf("") }
    var referenceUrl by remember { mutableStateOf("") }
    var selectedExpiration by remember { mutableStateOf(ExpirationOption.OneHour) }
    var expirationExpanded by remember { mutableStateOf(false) }
    val dismissKeyboard = rememberDismissKeyboard()
    val showExpirationMenu = {
        dismissKeyboard()
        expirationExpanded = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("ステータスを追加", style = MaterialTheme.typography.headlineSmall)

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = statusTag,
                            onValueChange = { statusTag = it },
                            label = { Text("ステータスタグ") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("ステータス") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = referenceUrl,
                            onValueChange = { referenceUrl = it },
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box {
                            OutlinedTextField(
                                value = selectedExpiration.label,
                                onValueChange = {},
                                label = { Text("終了時刻") },
                                readOnly = true,
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = showExpirationMenu),
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(onClick = showExpirationMenu),
                            )
                            DropdownMenu(
                                expanded = expirationExpanded,
                                onDismissRequest = { expirationExpanded = false },
                            ) {
                                ExpirationOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            selectedExpiration = option
                                            expirationExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("キャンセル") }
                        TextButton(
                            enabled = !isPublishing && content.isNotBlank(),
                            onClick = {
                                val expiration = selectedExpiration.secondsFromNow?.let {
                                    Clock.System.now().epochSeconds + it
                                }
                                onSubmit(statusTag, content, expiration, referenceUrl)
                            },
                        ) {
                            Text(if (isPublishing) "投稿中" else "追加")
                        }
                    }
                }
            }
        }
    }
}

private enum class ExpirationOption(val label: String, val secondsFromNow: Long?) {
    FifteenMinutes("15分後", 15 * 60),
    OneHour("1時間後", 60 * 60),
    TwentyFourHours("24時間後", 24 * 60 * 60),
    NoExpiration("終了なし", null),
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")
