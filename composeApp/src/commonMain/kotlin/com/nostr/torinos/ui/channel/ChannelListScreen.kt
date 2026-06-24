package com.nostr.torinos.ui.channel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import com.nostr.torinos.ui.service.serviceTabSwipe
import kotlin.time.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (channelId: String) -> Unit = {},
    ownPubkey: String? = null,
    ownProfile: NostrProfile? = null,
    onOpenProfile: () -> Unit = {},
    onOpenRelaySettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    selectedServiceTab: ServiceTab = ServiceTab.Channels,
    onServiceTabSelected: (ServiceTab) -> Unit = {},
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedChannelRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedChannelRelayUrl(relays.firstOrNull())
        }
    }

    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        ChannelRelayPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: ChannelListViewModel = viewModel(key = "channel-list-$activeRelayUrl") {
        ChannelListViewModel(relayUrl = activeRelayUrl)
    }
    val state by viewModel.state.collectAsState()
    val listState = rememberSaveable(activeRelayUrl, saver = LazyListState.Saver) { LazyListState() }
    val createdChannelIdToOpen = (state as? ChannelListViewModel.UiState.Ready)?.createdChannelIdToOpen

    LaunchedEffect(createdChannelIdToOpen) {
        val channelId = createdChannelIdToOpen ?: return@LaunchedEffect
        viewModel.consumeCreatedChannelNavigation()
        onChannelClick(channelId)
    }

    var previousTopKey by remember(activeRelayUrl) { mutableStateOf<String?>(null) }
    val currentTopKey = (state as? ChannelListViewModel.UiState.Ready)?.channels?.firstOrNull()?.event?.id

    LaunchedEffect(currentTopKey) {
        val prev = previousTopKey
        val curr = currentTopKey
        if (curr != null && prev != null && curr != prev) {
            val firstVisibleKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
            if (firstVisibleKey == prev) {
                listState.scrollToItem(0)
            }
        }
        previousTopKey = curr
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val ready = state as? ChannelListViewModel.UiState.Ready
            lastVisible >= layoutInfo.totalItemsCount - 3
                && ready?.canLoadMore == true
                && ready.isLoadingMore == false
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
    }

    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
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
                                            RelayStore.setSelectedChannelRelayUrl(url)
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
                        if (state is ChannelListViewModel.UiState.Ready) {
                            IconButton(onClick = viewModel::showBulkDeleteDialog) {
                                Icon(
                                    Icons.Default.CleaningServices,
                                    contentDescription = "お気に入り以外のキャッシュを削除",
                                    tint = headerContentColor,
                                )
                            }
                        }
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
        floatingActionButton = {
            if (state is ChannelListViewModel.UiState.Ready) {
                FloatingActionButton(
                    onClick = viewModel::showCreateDialog,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 1.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 1.dp,
                    ),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新規チャンネル")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .serviceTabSwipe(
                    selectedTab = selectedServiceTab,
                    onTabSelected = onServiceTabSelected,
                ),
        ) {
            when (val s = state) {
                is ChannelListViewModel.UiState.Loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "チャンネルを読み込み中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is ChannelListViewModel.UiState.Ready -> {
                    if (s.channels.isEmpty() && s.isLoadingMore) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "チャンネルを読み込み中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (s.channels.isEmpty()) {
                        Text(
                            text = "チャンネルがありません",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(s.channels, key = { it.event.id }) { item ->
                                ChannelRow(
                                    item = item,
                                    onClick = { onChannelClick(item.event.id) },
                                    onLongClick = {
                                        viewModel.showDeleteDialog(
                                            channelId = item.event.id,
                                            channelName = item.meta.name.ifBlank { "（名前なし）" },
                                            deleteFromRelays = ownPubkey != null && item.event.pubkey == ownPubkey,
                                        )
                                    },
                                    onFavoriteClick = { viewModel.toggleFavorite(item.event.id) },
                                )
                                HorizontalDivider()
                            }
                            if (s.isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // キャッシュ削除ダイアログ
    val deleteDialog = (state as? ChannelListViewModel.UiState.Ready)?.deleteDialog
    if (deleteDialog != null) {
        AlertDialog(
            onDismissRequest = { if (!deleteDialog.isDeleting) viewModel.dismissDeleteDialog() },
            title = { Text(if (deleteDialog.deleteFromRelays) "チャンネルを削除" else "キャッシュを削除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (deleteDialog.deleteFromRelays) {
                            "「${deleteDialog.channelName}」の削除要求をリレーへ送信し、この端末のキャッシュからも削除します。対応していないリレーからの削除は保証されません。"
                        } else {
                            "「${deleteDialog.channelName}」のキャッシュデータを削除します。既読情報が消えます。"
                        },
                    )
                    deleteDialog.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmDelete,
                    enabled = !deleteDialog.isDeleting,
                ) {
                    if (deleteDialog.isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("削除")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissDeleteDialog,
                    enabled = !deleteDialog.isDeleting,
                ) {
                    Text("キャンセル")
                }
            },
        )
    }

    // お気に入り以外のキャッシュ一括削除ダイアログ
    val bulkDeleteDialog = (state as? ChannelListViewModel.UiState.Ready)?.bulkDeleteDialog
    if (bulkDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { if (!bulkDeleteDialog.isDeleting) viewModel.dismissBulkDeleteDialog() },
            title = { Text("キャッシュを一括削除") },
            text = {
                Text("お気に入り以外のすべてのチャンネルキャッシュを削除します。既読情報も消えます。")
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmBulkDelete,
                    enabled = !bulkDeleteDialog.isDeleting,
                ) {
                    if (bulkDeleteDialog.isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("削除")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissBulkDeleteDialog,
                    enabled = !bulkDeleteDialog.isDeleting,
                ) {
                    Text("キャンセル")
                }
            },
        )
    }

    // 新規チャンネル作成ダイアログ
    val dialog = (state as? ChannelListViewModel.UiState.Ready)?.createDialog
    if (dialog != null) {
        CreateChannelDialog(
            dialog = dialog,
            onDismiss = viewModel::dismissCreateDialog,
            onNameChange = viewModel::onCreateNameChange,
            onAboutChange = viewModel::onCreateAboutChange,
            onBodyChange = viewModel::onCreateBodyChange,
            onCreate = viewModel::createChannel,
        )
    }
}

@Composable
private fun ChannelRelayPendingContent(
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
private fun CreateChannelDialog(
    dialog: ChannelListViewModel.CreateDialogState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var nameValue by remember { mutableStateOf(TextFieldValue(dialog.name)) }
    var aboutValue by remember { mutableStateOf(TextFieldValue(dialog.about)) }
    var bodyValue by remember { mutableStateOf(TextFieldValue(dialog.body)) }

    AlertDialog(
        onDismissRequest = { if (!dialog.isCreating) onDismiss() },
        title = { Text("新規チャンネル") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nameValue,
                    onValueChange = {
                        nameValue = it
                        onNameChange(it.text)
                    },
                    label = { Text("チャンネル名 *") },
                    singleLine = true,
                    enabled = !dialog.isCreating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aboutValue,
                    onValueChange = {
                        aboutValue = it
                        onAboutChange(it.text)
                    },
                    label = { Text("説明") },
                    maxLines = 3,
                    enabled = !dialog.isCreating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bodyValue,
                    onValueChange = {
                        bodyValue = it
                        onBodyChange(it.text)
                    },
                    label = { Text("本文") },
                    maxLines = 6,
                    enabled = !dialog.isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 120.dp),
                )
                if (dialog.error != null) {
                    Text(
                        text = dialog.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = nameValue.text.isNotBlank() && !dialog.isCreating,
            ) {
                if (dialog.isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("作成")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !dialog.isCreating,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(item: ChannelItem, onClick: () -> Unit, onLongClick: () -> Unit = {}, onFavoriteClick: () -> Unit = {}) {
    val activityTime = item.lastActivityAt ?: item.event.createdAt
    val timeText = relativeTime(activityTime)
    val barColor = if (item.hasBeenOpened) Color(0xFF4DD0E1) else Color(0xFFBDBDBD)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .fillMaxHeight()
                .background(barColor),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.meta.name.ifBlank { "（名前なし）" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.meta.about.isNotBlank()) {
                Text(
                    text = item.meta.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.latestMessagePreview.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.latestMessageAuthorPubkey?.let { pubkey ->
                        AvatarCircle(
                            pubkey = pubkey,
                            name = item.latestMessageAuthorProfile?.bestName,
                            pictureUrl = item.latestMessageAuthorProfile?.picture,
                            size = 16,
                        )
                    }
                    LinkedText(
                        text = item.latestMessagePreview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        customEmojis = item.latestMessageCustomEmojis,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 12.dp),
        ) {
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (item.isFavorite) "お気に入り解除" else "お気に入り登録",
                    tint = if (item.isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (item.unreadCount > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (item.unreadCount > 99) "99+" else "${item.unreadCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        } // inner Row
    } // outer Row
}

private fun relativeTime(epochSeconds: Long): String {
    val now = Clock.System.now().epochSeconds
    val diff = now - epochSeconds
    return when {
        diff < 60L -> "たった今"
        diff < 3600L -> "${diff / 60L}分前"
        diff < 86400L -> "${diff / 3600L}時間前"
        diff < 86400L * 30L -> "${diff / 86400L}日前"
        else -> "${diff / (86400L * 30L)}ヶ月前"
    }
}

private fun String.relayDisplayName(): String =
    removePrefix("wss://").removePrefix("ws://").trimEnd('/')
