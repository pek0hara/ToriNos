package com.nostr.torinos.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.LiveActivityItem
import com.nostr.torinos.model.LiveActivityStatus
import com.nostr.torinos.model.LiveParticipant
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import com.nostr.torinos.ui.service.serviceTabSwipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveHubScreen(
    ownPubkey: String?,
    ownProfile: NostrProfile?,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onLiveClick: (pubkey: String, identifier: String) -> Unit,
    onUserClick: (pubkey: String) -> Unit,
    selectedServiceTab: ServiceTab,
    onServiceTabSelected: (ServiceTab) -> Unit,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedLiveRelayUrl.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedLiveRelayUrl(relays.firstOrNull())
        }
    }

    val viewModel: LiveListViewModel = viewModel(key = "live-hub-${selectedRelayUrl ?: "all"}") {
        LiveListViewModel(relayUrl = selectedRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(headerBackgroundColor)) {
                TopAppBar(
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedRelayUrl?.relayDisplayName() ?: "-",
                                modifier = Modifier.weight(1f, fill = false),
                                color = headerContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { showRelayMenu = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "リレー切り替え", tint = headerContentColor)
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
                                            RelayStore.setSelectedLiveRelayUrl(url)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (url == selectedRelayUrl) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "設定", tint = headerContentColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = headerBackgroundColor,
                        scrolledContainerColor = headerBackgroundColor,
                        titleContentColor = headerContentColor,
                        actionIconContentColor = headerContentColor,
                        navigationIconContentColor = headerContentColor,
                    ),
                )
                ServiceTabRow(selectedTab = selectedServiceTab, onTabSelected = onServiceTabSelected)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .serviceTabSwipe(selectedTab = selectedServiceTab, onTabSelected = onServiceTabSelected),
        ) {
            LiveStatusFilterRow(
                selectedStatuses = state.selectedStatuses,
                onToggle = viewModel::toggleStatus,
            )
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isInitialLoad && state.activities.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.activities.isEmpty() -> {
                        Text(
                            text = "ライブがありません",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(
                                items = state.activities,
                                key = { it.address },
                                contentType = { "live" },
                            ) { activity ->
                                LiveActivityCard(
                                    activity = activity,
                                    profiles = state.profiles,
                                    onClick = { onLiveClick(activity.event.pubkey, activity.meta.identifier) },
                                    onUserClick = onUserClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDetailScreen(
    pubkey: String,
    identifier: String,
    ownPubkey: String?,
    onBack: () -> Unit,
    onUserClick: (pubkey: String) -> Unit,
) {
    val selectedRelayUrl by RelayStore.selectedLiveRelayUrl.collectAsState()
    val viewModel: LiveDetailViewModel = viewModel(key = "live-detail-$pubkey-$identifier-${selectedRelayUrl ?: "all"}") {
        LiveDetailViewModel(pubkey = pubkey, identifier = identifier, relayUrl = selectedRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var chatText by rememberSaveable(pubkey, identifier) { mutableStateOf("") }

    LaunchedEffect(state.publishCompletedCount) {
        if (state.publishCompletedCount > 0) {
            chatText = ""
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                title = {
                    Text(
                        text = state.activity?.displayTitle ?: "ライブ",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
        bottomBar = {
            ChatComposer(
                text = chatText,
                enabled = ownPubkey != null && !state.isPublishing,
                isPublishing = state.isPublishing,
                onTextChange = {
                    chatText = it
                    if (state.error != null) viewModel.clearError()
                },
                onSubmit = {
                    val message = chatText.trim()
                    if (message.isNotBlank()) {
                        viewModel.publishChat(message)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isInitialLoad && state.activity == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.activity == null -> {
                    Text(
                        text = "ライブが見つかりません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        item(contentType = "activity") {
                            LiveDetailHeader(
                                activity = state.activity,
                                profiles = state.profiles,
                                onUserClick = onUserClick,
                                onOpenUrl = { uriHandler.openUri(it) },
                            )
                        }
                        if (state.error != null) {
                            item(contentType = "error") {
                                Text(
                                    text = state.error ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        item(contentType = "chat-title") {
                            Text(
                                text = "チャット",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        if (state.chatMessages.isEmpty()) {
                            item(contentType = "empty-chat") {
                                Text(
                                    text = "チャットはまだありません",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            items(
                                items = state.chatMessages,
                                key = { it.id },
                                contentType = { "chat" },
                            ) { event ->
                                ChatMessageRow(
                                    event = event,
                                    profile = state.profiles[event.pubkey],
                                    onUserClick = { onUserClick(event.pubkey) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStatusFilterRow(
    selectedStatuses: Set<LiveActivityStatus>,
    onToggle: (LiveActivityStatus) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(listOf(LiveActivityStatus.Live, LiveActivityStatus.Planned, LiveActivityStatus.Ended)) { status ->
            FilterChip(
                selected = status in selectedStatuses,
                onClick = { onToggle(status) },
                label = { Text(status.label) },
            )
        }
    }
}

@Composable
private fun LiveActivityCard(
    activity: LiveActivityItem,
    profiles: Map<String, NostrProfile>,
    onClick: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            activity.meta.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
                NetworkImage(
                    url = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 900,
                )
            }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(activity.meta.status)
                    activity.meta.currentParticipants?.let {
                        Text(
                            text = "${it}人参加中",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text = activity.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activity.displaySummary.isNotBlank()) {
                    Text(
                        text = activity.displaySummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.clickable { onUserClick(activity.event.pubkey) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarCircle(
                        pubkey = activity.event.pubkey,
                        name = activity.authorProfile?.bestName,
                        pictureUrl = activity.authorProfile?.picture,
                        size = 28,
                    )
                    Spacer(Modifier.width(8.dp))
                    ProfileNameText(
                        profile = activity.authorProfile,
                        fallback = activity.event.pubkey.take(8),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val starts = activity.meta.starts
                if (starts != null) {
                    Text(
                        text = "開始 ${formatTimestamp(starts)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (activity.meta.topics.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(activity.meta.topics.take(8)) { topic ->
                            AssistChip(onClick = {}, label = { Text("#$topic") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveDetailHeader(
    activity: LiveActivityItem?,
    profiles: Map<String, NostrProfile>,
    onUserClick: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val item = activity ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        item.meta.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            NetworkImage(
                url = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 1200,
            )
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(item.meta.status)
                item.meta.currentParticipants?.let { current ->
                    val total = item.meta.totalParticipants?.let { " / $it" }.orEmpty()
                    Text(
                        text = "$current$total 人",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (item.displaySummary.isNotBlank()) {
                LinkedText(text = item.displaySummary)
            }
            Row(
                modifier = Modifier.clickable { onUserClick(item.event.pubkey) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(
                    pubkey = item.event.pubkey,
                    name = item.authorProfile?.bestName,
                    pictureUrl = item.authorProfile?.picture,
                    size = 36,
                )
                Spacer(Modifier.width(10.dp))
                ProfileNameText(
                    profile = item.authorProfile,
                    fallback = item.event.pubkey.take(8),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item.meta.starts?.let {
                Text(text = "開始 ${formatTimestamp(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.meta.ends?.let {
                Text(text = "終了 ${formatTimestamp(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.meta.streamingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    Button(onClick = { onOpenUrl(url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("視聴")
                    }
                }
                item.meta.recordingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    Button(onClick = { onOpenUrl(url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("録画")
                    }
                }
            }
            if (item.meta.participants.isNotEmpty()) {
                Text(
                    text = "参加者",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.meta.participants.take(30).forEach { participant ->
                        ParticipantRow(
                            participant = participant,
                            profile = profiles[participant.pubkey],
                            onUserClick = { onUserClick(participant.pubkey) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ParticipantRow(
    participant: LiveParticipant,
    profile: NostrProfile?,
    onUserClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUserClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            pubkey = participant.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 32,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            ProfileNameText(
                profile = profile,
                fallback = participant.pubkey.take(8),
                fontWeight = FontWeight.Bold,
            )
            participant.role?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    event: NostrEvent,
    profile: NostrProfile?,
    onUserClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.clickable(onClick = onUserClick)) {
            AvatarCircle(
                pubkey = event.pubkey,
                name = profile?.bestName,
                pictureUrl = profile?.picture,
                size = 34,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileNameText(
                    profile = profile,
                    fallback = event.pubkey.take(8),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = formatTimestamp(event.createdAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LinkedText(text = event.content)
        }
    }
}

@Composable
private fun ChatComposer(
    text: String,
    enabled: Boolean,
    isPublishing: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    SurfaceLikeBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(if (enabled) "チャットを書く" else "ログインするとチャットできます") },
                minLines = 1,
                maxLines = 4,
            )
            IconButton(
                onClick = onSubmit,
                enabled = enabled && !isPublishing && text.isNotBlank(),
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
                }
            }
        }
    }
}

@Composable
private fun SurfaceLikeBar(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider()
        content()
    }
}

@Composable
private fun StatusChip(status: LiveActivityStatus) {
    val color = when (status) {
        LiveActivityStatus.Live -> MaterialTheme.colorScheme.errorContainer
        LiveActivityStatus.Planned -> MaterialTheme.colorScheme.secondaryContainer
        LiveActivityStatus.Ended -> MaterialTheme.colorScheme.surfaceVariant
        LiveActivityStatus.Unknown -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (status) {
        LiveActivityStatus.Live -> MaterialTheme.colorScheme.onErrorContainer
        LiveActivityStatus.Planned -> MaterialTheme.colorScheme.onSecondaryContainer
        LiveActivityStatus.Ended -> MaterialTheme.colorScheme.onSurfaceVariant
        LiveActivityStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status.label,
        color = contentColor,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")
