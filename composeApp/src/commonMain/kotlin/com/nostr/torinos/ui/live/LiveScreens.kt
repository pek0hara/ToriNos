package com.nostr.torinos.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.LiveActivityItem
import com.nostr.torinos.model.LiveActivityStatus
import com.nostr.torinos.model.LiveParticipant
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.PreviewImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
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
    createLiveRequest: Int = 0,
    onCreateLiveRequestConsumed: () -> Unit = {},
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedLiveRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedLiveRelayUrl(relays.firstOrNull())
        }
    }

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        LiveRelayPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: LiveListViewModel = viewModel(key = "live-hub-$activeRelayUrl") {
        LiveListViewModel(relayUrl = activeRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val createViewModel: LiveCreateViewModel = viewModel(key = "live-create-$activeRelayUrl") {
        LiveCreateViewModel(relayUrl = activeRelayUrl)
    }
    val createState by createViewModel.state.collectAsState()
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground
    var showCreateDialog by rememberSaveable(activeRelayUrl) { mutableStateOf(false) }
    val pickCoverImage = rememberImagePickerLauncher { bytes, mime ->
        if (bytes != null && mime != null) createViewModel.uploadImage(bytes, mime)
    }

    LaunchedEffect(createState.publishCompletedCount) {
        val event = createState.publishedEvent
        if (event != null) {
            viewModel.addPublishedActivity(event)
            createViewModel.clearPublishedEvent()
            showCreateDialog = false
        }
    }

    LaunchedEffect(createLiveRequest) {
        if (createLiveRequest > 0) {
            showCreateDialog = true
            onCreateLiveRequestConsumed()
        }
    }

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

    if (showCreateDialog) {
        LiveCreateDialog(
            state = createState,
            onDismiss = {
                if (!createState.isPublishing) showCreateDialog = false
            },
            onTitleChange = createViewModel::onTitleChange,
            onSummaryChange = createViewModel::onSummaryChange,
            onStreamingUrlChange = createViewModel::onStreamingUrlChange,
            onTopicsTextChange = createViewModel::onTopicsTextChange,
            onStartModeChange = createViewModel::onStartModeChange,
            onScheduledDateChange = createViewModel::onScheduledDateChange,
            onScheduledTimeChange = createViewModel::onScheduledTimeChange,
            onPickImage = pickCoverImage,
            onRemoveImage = createViewModel::removeImage,
            onPublish = createViewModel::publish,
        )
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
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedLiveRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedLiveRelayUrl(relays.firstOrNull())
        }
    }

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        LiveRelayPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: LiveDetailViewModel = viewModel(key = "live-detail-$pubkey-$identifier-$activeRelayUrl") {
        LiveDetailViewModel(pubkey = pubkey, identifier = identifier, relayUrl = activeRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var chatText by rememberSaveable(pubkey, identifier) { mutableStateOf("") }
    var isChatExpanded by rememberSaveable(pubkey, identifier) { mutableStateOf(false) }
    var showEndDialog by rememberSaveable(pubkey, identifier) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(pubkey, identifier) { mutableStateOf(false) }
    var recordingUrl by rememberSaveable(pubkey, identifier) { mutableStateOf("") }

    val clearErrorAndUpdateChatText: (String) -> Unit = {
        chatText = it
        if (state.error != null) viewModel.clearError()
    }
    val submitChat: () -> Unit = {
        val message = chatText.trim()
        if (message.isNotBlank()) {
            viewModel.publishChat(message)
        }
    }

    LaunchedEffect(state.publishCompletedCount) {
        if (state.publishCompletedCount > 0) {
            chatText = ""
        }
    }

    LaunchedEffect(state.activity?.meta?.status) {
        if (state.activity?.meta?.status == LiveActivityStatus.Ended) {
            showEndDialog = false
            recordingUrl = ""
        }
    }

    LaunchedEffect(state.deleteCompletedCount) {
        if (state.deleteCompletedCount > 0) {
            showDeleteDialog = false
            onBack()
        }
    }

    if (isChatExpanded) {
        LiveChatExpandedScreen(
            title = state.activity?.displayTitle ?: "ライブ",
            chatMessages = state.chatMessages,
            profiles = state.profiles,
            error = state.error,
            chatText = chatText,
            ownPubkey = ownPubkey,
            isPublishing = state.isPublishing,
            onBack = { isChatExpanded = false },
            onTextChange = clearErrorAndUpdateChatText,
            onSubmit = submitChat,
            onUserClick = onUserClick,
        )
        return
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
                onTextChange = clearErrorAndUpdateChatText,
                onSubmit = submitChat,
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
                                canEndLive = state.activity?.event?.pubkey == ownPubkey &&
                                    state.activity?.meta?.status != LiveActivityStatus.Ended,
                                canDeleteLive = state.activity?.event?.pubkey == ownPubkey,
                                isEndingLive = state.isPublishing,
                                isDeletingLive = state.isPublishing,
                                onUserClick = onUserClick,
                                onOpenUrl = { uriHandler.openUri(it) },
                                onEndLiveClick = { showEndDialog = true },
                                onDeleteLiveClick = { showDeleteDialog = true },
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
                            ChatSectionHeader(onExpand = { isChatExpanded = true })
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

    if (showEndDialog) {
        EndLiveDialog(
            recordingUrl = recordingUrl,
            isPublishing = state.isPublishing,
            onRecordingUrlChange = {
                recordingUrl = it
                if (state.error != null) viewModel.clearError()
            },
            onDismiss = {
                if (!state.isPublishing) showEndDialog = false
            },
            onConfirm = {
                viewModel.endLive(recordingUrl)
            },
        )
    }

    if (showDeleteDialog) {
        DeleteLiveDialog(
            isPublishing = state.isPublishing,
            onDismiss = {
                if (!state.isPublishing) showDeleteDialog = false
            },
            onConfirm = {
                viewModel.deleteLive()
            },
        )
    }
}

@Composable
private fun EndLiveDialog(
    recordingUrl: String,
    isPublishing: Boolean,
    onRecordingUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ライブを終了") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("このライブを終了として投稿します。")
                OutlinedTextField(
                    value = recordingUrl,
                    onValueChange = onRecordingUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPublishing,
                    label = { Text("録画URL") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isPublishing,
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("終了する")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isPublishing,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun DeleteLiveDialog(
    isPublishing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ライブを削除") },
        text = {
            Text("選択中のリレーへ削除要求を送信します。対応していないリレーやキャッシュ済みデータからの削除は保証されません。")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isPublishing,
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("削除する")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isPublishing,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun ChatSectionHeader(onExpand: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "チャット",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExpand) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "チャットを拡大")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveChatExpandedScreen(
    title: String,
    chatMessages: List<NostrEvent>,
    profiles: Map<String, NostrProfile>,
    error: String?,
    chatText: String,
    ownPubkey: String?,
    isPublishing: Boolean,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onUserClick: (pubkey: String) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ライブ詳細に戻る")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "チャット",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            ChatComposer(
                text = chatText,
                enabled = ownPubkey != null && !isPublishing,
                isPublishing = isPublishing,
                onTextChange = onTextChange,
                onSubmit = onSubmit,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (error != null) {
                item(contentType = "error") {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (chatMessages.isEmpty()) {
                item(contentType = "empty-chat") {
                    Text(
                        text = "チャットはまだありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(
                    items = chatMessages,
                    key = { it.id },
                    contentType = { "chat" },
                ) { event ->
                    ChatMessageRow(
                        event = event,
                        profile = profiles[event.pubkey],
                        onUserClick = { onUserClick(event.pubkey) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LiveRelayPendingContent(
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
private fun LiveCreateDialog(
    state: LiveCreateState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onStreamingUrlChange: (String) -> Unit,
    onTopicsTextChange: (String) -> Unit,
    onStartModeChange: (LiveStartMode) -> Unit,
    onScheduledDateChange: (String) -> Unit,
    onScheduledTimeChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onPublish: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "ライブを投稿",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "開始",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(LiveStartMode.Now, LiveStartMode.Scheduled).forEach { mode ->
                                val label = when (mode) {
                                    LiveStartMode.Now -> "今から"
                                    LiveStartMode.Scheduled -> "予約"
                                }
                                FilterChip(
                                    selected = state.startMode == mode,
                                    onClick = { onStartModeChange(mode) },
                                    enabled = !state.isPublishing,
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    if (state.startMode == LiveStartMode.Scheduled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.scheduledDate,
                                onValueChange = onScheduledDateChange,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isPublishing,
                                label = { Text("開始日") },
                                placeholder = { Text("yyyy-MM-dd") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = state.scheduledTime,
                                onValueChange = onScheduledTimeChange,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isPublishing,
                                label = { Text("開始時刻") },
                                placeholder = { Text("HH:mm") },
                                singleLine = true,
                            )
                        }
                    }
                    if (state.startMode == LiveStartMode.Now) {
                        Text(
                            text = "投稿するとライブ中として表示されます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "予約日時までは予定として表示されます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isPublishing,
                        label = { Text("タイトル") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.streamingUrl,
                        onValueChange = onStreamingUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isPublishing,
                        label = { Text("配信URL") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.summary,
                        onValueChange = onSummaryChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isPublishing,
                        label = { Text("概要") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    OutlinedTextField(
                        value = state.topicsText,
                        onValueChange = onTopicsTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isPublishing,
                        label = { Text("トピック") },
                        placeholder = { Text("nostr, live") },
                        singleLine = true,
                    )
                    LiveCoverImagePicker(
                        image = state.image,
                        enabled = !state.isPublishing,
                        onPickImage = onPickImage,
                        onRemoveImage = onRemoveImage,
                    )
                    state.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !state.isPublishing,
                        ) {
                            Text("キャンセル")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onPublish,
                            enabled = state.canPublish,
                        ) {
                            if (state.isPublishing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("投稿")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCoverImagePicker(
    image: LiveImageAttachment?,
    enabled: Boolean,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "画像",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (image == null) {
            TextButton(
                onClick = onPickImage,
                enabled = enabled,
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("画像を追加")
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            ) {
                val previewData: Any? = image.uploadedUrl ?: image.previewBytes
                if (previewData != null) {
                    PreviewImage(
                        data = previewData,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (image.isUploading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    }
                } else {
                    IconButton(
                        onClick = onRemoveImage,
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "画像を削除",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
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
    canEndLive: Boolean,
    canDeleteLive: Boolean,
    isEndingLive: Boolean,
    isDeletingLive: Boolean,
    onUserClick: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onEndLiveClick: () -> Unit,
    onDeleteLiveClick: () -> Unit,
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
                if (canEndLive) {
                    Button(
                        onClick = onEndLiveClick,
                        enabled = !isEndingLive,
                    ) {
                        if (isEndingLive) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("終了")
                    }
                }
                if (canDeleteLive) {
                    TextButton(
                        onClick = onDeleteLiveClick,
                        enabled = !isDeletingLive,
                    ) {
                        if (isDeletingLive) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("削除", color = MaterialTheme.colorScheme.error)
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
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
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
