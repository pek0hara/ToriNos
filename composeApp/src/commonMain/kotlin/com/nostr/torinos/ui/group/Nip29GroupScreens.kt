package com.nostr.torinos.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.GroupRef
import com.nostr.torinos.model.Nip29Membership
import com.nostr.torinos.model.Nip29SupportedKindsMode
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.Nip29CreateStage
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.AppMessageComposer
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.NoteCard
import com.nostr.torinos.ui.components.rememberSyncedTextFieldValue
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.service.ServiceTabRow
import com.nostr.torinos.ui.service.serviceTabSwipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Nip29GroupListScreen(
    ownPubkey: String?,
    ownProfile: NostrProfile?,
    onGroupClick: (GroupRef) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    selectedServiceTab: ServiceTab,
    onServiceTabSelected: (ServiceTab) -> Unit,
) {
    val vm: Nip29GroupListViewModel = viewModel(key = "nip29-list-${ownPubkey ?: "guest"}") {
        Nip29GroupListViewModel(ownPubkey)
    }
    val state by vm.state.collectAsState()
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedGroupRelayUrl.collectAsState()
    var showRelayMenu by remember { mutableStateOf(false) }

    LaunchedEffect(relays, selectedRelayUrl) {
        if (selectedRelayUrl == null || selectedRelayUrl !in relays) {
            RelayStore.setSelectedGroupRelayUrl(relays.firstOrNull())
        }
    }
    LaunchedEffect(selectedRelayUrl) {
        vm.selectPublicRelay(selectedRelayUrl)
    }
    LaunchedEffect(state.createdRefToOpen) {
        val ref = state.createdRefToOpen ?: return@LaunchedEffect
        vm.consumeCreatedNavigation()
        onGroupClick(ref)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
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
                                text = selectedRelayUrl?.groupRelayDisplayName() ?: "グループ",
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { showRelayMenu = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "リレー切り替え")
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
                                        text = { Text(url.groupRelayDisplayName()) },
                                        onClick = {
                                            RelayStore.setSelectedGroupRelayUrl(url)
                                            showRelayMenu = false
                                        },
                                        trailingIcon = if (url == selectedRelayUrl) {
                                            {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            }
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
                            Icon(Icons.Default.Settings, contentDescription = "設定")
                        }
                    },
                )
                ServiceTabRow(selectedServiceTab, onServiceTabSelected)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.listMode == Nip29GroupListMode.SAVED,
                        onClick = { vm.selectListMode(Nip29GroupListMode.SAVED) },
                        label = { Text("保存済み") },
                    )
                    FilterChip(
                        selected = state.listMode == Nip29GroupListMode.PUBLIC,
                        onClick = { vm.selectListMode(Nip29GroupListMode.PUBLIC) },
                        label = { Text("公開") },
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.listMode == Nip29GroupListMode.PUBLIC) {
                        IconButton(onClick = vm::refreshPublicGroups, enabled = !state.isLoadingPublic) {
                            Icon(Icons.Default.Refresh, contentDescription = "公開グループを再読み込み")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            AppFloatingActionButton(
                onClick = vm::showAddChoice,
                icon = Icons.Default.Add,
                contentDescription = "グループを追加",
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .serviceTabSwipe(selectedServiceTab, onServiceTabSelected),
        ) {
            when (state.listMode) {
                Nip29GroupListMode.SAVED -> SavedGroupListContent(
                    state = state,
                    onOpen = { item ->
                        vm.markOpened(item.saved.ref)
                        onGroupClick(item.saved.ref)
                    },
                )
                Nip29GroupListMode.PUBLIC -> PublicGroupListContent(
                    state = state,
                    onOpen = onGroupClick,
                    onSave = vm::savePublicGroup,
                )
            }
        }
    }

    if (state.showAddChoice) {
        AlertDialog(
            onDismissRequest = vm::dismissAddChoice,
            title = { Text("グループ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::showAddDialog, modifier = Modifier.fillMaxWidth()) {
                        Text("既存グループを追加")
                    }
                    Button(
                        onClick = {
                            vm.showCreateDialog(selectedRelayUrl ?: relays.firstOrNull())
                        },
                        enabled = ownPubkey != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("新しいグループを作成")
                    }
                    if (ownPubkey == null) {
                        Text(
                            "グループ作成にはログインが必要です",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = vm::dismissAddChoice) { Text("閉じる") }
            },
        )
    }

    state.addDialog?.let { dialog ->
        var naddrValue by rememberSyncedTextFieldValue(dialog.naddr)
        var relayUrlValue by rememberSyncedTextFieldValue(dialog.relayUrl)
        var groupIdValue by rememberSyncedTextFieldValue(dialog.groupId)

        AlertDialog(
            onDismissRequest = vm::dismissAddDialog,
            title = { Text("グループを追加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = naddrValue,
                        onValueChange = {
                            naddrValue = it
                            vm.updateAddDialog(naddr = it.text)
                        },
                        label = { Text("naddr（任意）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("または", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = relayUrlValue,
                        onValueChange = {
                            relayUrlValue = it
                            vm.updateAddDialog(relayUrl = it.text)
                        },
                        label = { Text("リレーURL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = groupIdValue,
                        onValueChange = {
                            groupIdValue = it
                            vm.updateAddDialog(groupId = it.text)
                        },
                        label = { Text("グループID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    dialog.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = vm::addGroup, enabled = !dialog.isSaving) {
                    Text("追加")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissAddDialog, enabled = !dialog.isSaving) {
                    Text("キャンセル")
                }
            },
        )
    }

    state.createDialog?.let { dialog ->
        var groupIdValue by rememberSyncedTextFieldValue(dialog.groupId)
        var nameValue by rememberSyncedTextFieldValue(dialog.name)
        var aboutValue by rememberSyncedTextFieldValue(dialog.about)
        var pictureValue by rememberSyncedTextFieldValue(dialog.picture)
        var showCreateRelayMenu by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = vm::dismissCreateDialog,
            title = { Text("新しいグループを作成") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "作成可否と権限はリレーのポリシーに依存します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("投稿先リレー", style = MaterialTheme.typography.labelMedium)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showCreateRelayMenu = true },
                            enabled = !dialog.isCreating && dialog.result == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = dialog.relayUrl
                                    .takeIf { it.isNotBlank() }
                                    ?.groupRelayDisplayName()
                                    ?: "リレーを選択",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showCreateRelayMenu,
                            onDismissRequest = { showCreateRelayMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("リレー設定") },
                                onClick = {
                                    showCreateRelayMenu = false
                                    vm.dismissCreateDialog()
                                    onOpenRelaySettings()
                                },
                            )
                            relays.forEach { url ->
                                DropdownMenuItem(
                                    text = { Text(url.groupRelayDisplayName()) },
                                    onClick = {
                                        vm.updateCreateDialog(relayUrl = url)
                                        showCreateRelayMenu = false
                                    },
                                    trailingIcon = if (url == dialog.relayUrl) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                    if (relays.isEmpty()) {
                        Text(
                            "有効なリレーがありません。リレー設定から追加してください。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dialog.isCheckingRelay) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        TextButton(
                            onClick = vm::checkCreateRelay,
                            enabled = !dialog.isCreating && !dialog.isCheckingRelay && dialog.result == null,
                        ) {
                            Text("リレーを確認")
                        }
                    }
                    dialog.relayCheck?.let { check ->
                        Text(
                            check.relayName?.let { "接続先: $it" } ?: "NIP-11情報を確認しました",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        check.warnings.forEach { warning ->
                            Text(
                                "・$warning",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = groupIdValue,
                            onValueChange = {
                                groupIdValue = it
                                vm.updateCreateDialog(groupId = it.text)
                            },
                            label = { Text("グループID") },
                            singleLine = true,
                            enabled = !dialog.isCreating && dialog.result == null,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = vm::regenerateGroupId,
                            enabled = !dialog.isCreating && dialog.result == null,
                        ) {
                            Text("再生成")
                        }
                    }
                    OutlinedTextField(
                        value = nameValue,
                        onValueChange = {
                            nameValue = it
                            vm.updateCreateDialog(name = it.text)
                        },
                        label = { Text("グループ名") },
                        singleLine = true,
                        enabled = !dialog.isCreating && dialog.result == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = aboutValue,
                        onValueChange = {
                            aboutValue = it
                            vm.updateCreateDialog(about = it.text)
                        },
                        label = { Text("説明（任意）") },
                        enabled = !dialog.isCreating && dialog.result == null,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pictureValue,
                        onValueChange = {
                            pictureValue = it
                            vm.updateCreateDialog(picture = it.text)
                        },
                        label = { Text("画像URL（任意）") },
                        singleLine = true,
                        enabled = !dialog.isCreating && dialog.result == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GroupCreationSwitch(
                        label = "メンバーのみ閲覧",
                        checked = dialog.isPrivate,
                        enabled = !dialog.isCreating && dialog.result == null,
                        onCheckedChange = { vm.updateCreateDialog(isPrivate = it) },
                    )
                    GroupCreationSwitch(
                        label = "メンバーのみ投稿",
                        checked = dialog.isRestricted,
                        enabled = !dialog.isCreating && dialog.result == null,
                        onCheckedChange = { vm.updateCreateDialog(isRestricted = it) },
                    )
                    GroupCreationSwitch(
                        label = "非メンバーに情報を隠す",
                        checked = dialog.isHidden,
                        enabled = !dialog.isCreating && dialog.result == null,
                        onCheckedChange = { vm.updateCreateDialog(isHidden = it) },
                    )
                    GroupCreationSwitch(
                        label = "招待コード限定",
                        checked = dialog.isClosed,
                        enabled = !dialog.isCreating && dialog.result == null,
                        onCheckedChange = { vm.updateCreateDialog(isClosed = it) },
                    )
                    Text("対応イベント", style = MaterialTheme.typography.labelLarge)
                    Nip29SupportedKindsMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !dialog.isCreating && dialog.result == null) {
                                    vm.updateCreateDialog(supportedKindsMode = mode)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = dialog.supportedKindsMode == mode,
                                onClick = { vm.updateCreateDialog(supportedKindsMode = mode) },
                                enabled = !dialog.isCreating && dialog.result == null,
                            )
                            Text(
                                when (mode) {
                                    Nip29SupportedKindsMode.ALL -> "すべて"
                                    Nip29SupportedKindsMode.TEXT_CHAT -> "テキストチャット（kind 9）"
                                    Nip29SupportedKindsMode.NONE -> "テキスト投稿なし"
                                },
                            )
                        }
                    }
                    if (dialog.isClosed) {
                        Text(
                            "招待コードの作成は管理機能対応後に利用できます。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (dialog.relayUrl.startsWith("ws://")) {
                        Text(
                            "暗号化されないws://接続は開発用途に限定してください。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    dialog.result?.let { result ->
                        Text(
                            result.message,
                            color = if (result.stage == Nip29CreateStage.COMPLETE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            "作成イベント: ${result.createEventId.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    dialog.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (dialog.isCreating) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    dialog.result != null && dialog.result.stage != Nip29CreateStage.COMPLETE -> {
                        Button(onClick = vm::retryCreateCompletion, enabled = !dialog.isCreating) {
                            Text(
                                when (dialog.result.stage) {
                                    Nip29CreateStage.ADMIN_FAILED -> "管理者付与を再試行"
                                    Nip29CreateStage.METADATA_FAILED -> "設定を再試行"
                                    else -> "再確認"
                                },
                            )
                        }
                    }
                    dialog.result == null -> {
                        Button(
                            onClick = vm::createGroup,
                            enabled = !dialog.isCreating &&
                                !dialog.isCheckingRelay &&
                                dialog.relayUrl.isNotBlank() &&
                                dialog.groupId.isNotBlank() &&
                                dialog.name.isNotBlank(),
                        ) {
                            Text("作成")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissCreateDialog, enabled = !dialog.isCreating) {
                    Text(if (dialog.result == null) "キャンセル" else "閉じる")
                }
            },
        )
    }
}

@Composable
private fun SavedGroupListContent(
    state: Nip29GroupListViewModel.UiState,
    onOpen: (Nip29GroupListItem) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoading && state.groups.isEmpty() ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.groups.isEmpty() -> Text(
                "保存済みグループはありません",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.groups, key = { it.saved.ref.key }) { item ->
                    GroupListRow(
                        name = item.metadata?.name?.ifBlank { null }
                            ?: item.saved.name
                            ?: item.saved.ref.groupId,
                        description = item.latestMessage?.content
                            ?: item.error
                            ?: item.saved.ref.relayUrl,
                        picture = item.metadata?.picture,
                        isError = item.error != null,
                        warning = item.warning,
                        onClick = { onOpen(item) },
                        trailing = {
                            if (item.isLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (item.unreadCount > 0) {
                                Text(
                                    item.unreadCount.toString(),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PublicGroupListContent(
    state: Nip29GroupListViewModel.UiState,
    onOpen: (GroupRef) -> Unit,
    onSave: (GroupRef) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.isLoadingPublic && state.publicGroups.isEmpty() ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.publicGroups.isEmpty() -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (state.publicRelayErrors.isEmpty()) {
                        "公開グループが見つかりません"
                    } else {
                        "公開グループを取得できません"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.publicRelayErrors.entries.take(3).forEach { (relay, error) ->
                    Text(
                        "$relay: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.publicGroups, key = { it.ref.key }) { item ->
                    GroupListRow(
                        name = item.metadata.name.ifBlank { item.ref.groupId },
                        description = item.metadata.about.ifBlank { item.ref.relayUrl },
                        picture = item.metadata.picture,
                        isError = false,
                        warning = item.warning,
                        onClick = { onOpen(item.ref) },
                        trailing = {
                            if (item.isSaved) {
                                Text(
                                    "保存済み",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            } else {
                                TextButton(onClick = { onSave(item.ref) }) {
                                    Text("保存")
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
                if (state.isLoadingPublic) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupListRow(
    name: String,
    description: String,
    picture: String?,
    isError: Boolean,
    warning: String? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (picture != null) {
            NetworkImage(
                url = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(CircleShape),
            )
        } else {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            warning?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

private fun String.groupRelayDisplayName(): String =
    removePrefix("wss://")
        .removePrefix("ws://")
        .trimEnd('/')

@Composable
private fun GroupCreationSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Nip29GroupScreen(
    ref: GroupRef,
    ownPubkey: String?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onUserClick: (String) -> Unit,
) {
    val vm: Nip29GroupViewModel = viewModel(key = "nip29-detail-${ref.key}-${ownPubkey ?: "guest"}") {
        Nip29GroupViewModel(ref, ownPubkey)
    }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                title = {
                    Text(
                        if (state.canViewGroupInfo) {
                            state.metadata?.name?.ifBlank { "グループ" } ?: "グループ"
                        } else {
                            "非公開グループ"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (state.canManageGroup && state.metadata != null) {
                        IconButton(onClick = vm::showEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "グループを編集")
                        }
                    }
                    IconButton(onClick = vm::showInfo) {
                        Icon(Icons.Default.Info, contentDescription = "グループ情報")
                    }
                },
            )
        },
        bottomBar = {
            if (ownPubkey != null && state.canPost) {
                AppMessageComposer(
                    text = state.draft,
                    onTextChange = vm::onDraftChange,
                    onSend = vm::sendMessage,
                    placeholder = "メッセージを入力…",
                    isSending = state.isPosting,
                    error = state.error,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.messages.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val emptyMessage = when {
                        !state.canViewGroupInfo && ownPubkey == null -> "このグループの情報は非メンバーには表示されません。ログインして参加してください。"
                        !state.canViewGroupInfo -> "このグループの情報は非メンバーには表示されません。"
                        !state.canViewMessages && ownPubkey == null -> "このグループはメンバーのみ閲覧できます。ログインして参加してください。"
                        !state.canViewMessages -> "このグループはメンバーのみ閲覧できます。"
                        else -> state.error ?: "メッセージはありません"
                    }
                    Text(
                        emptyMessage,
                        color = if (state.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MembershipButton(state.membership, vm::showJoinDialog, vm::leave, ownPubkey != null)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
                    items(state.messages, key = { it.id }) { event ->
                        NoteCard(
                            event = event,
                            profile = state.profiles[event.pubkey],
                            profiles = state.profiles,
                            replyCount = 0,
                            reactionCount = 0,
                            onUserClick = onUserClick,
                            ownPubkey = ownPubkey,
                        )
                        HorizontalDivider()
                    }
                    if (state.canLoadMore) {
                        item {
                            TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                                Text("さらに読み込む")
                            }
                        }
                    }
                }
            }
            state.actionMessage?.let {
                Text(
                    it,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (state.showJoinDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissJoinDialog,
            title = { Text("グループへ参加") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.joinReason,
                        onValueChange = vm::onJoinReasonChange,
                        label = { Text("参加理由（任意）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.inviteCode,
                        onValueChange = vm::onInviteCodeChange,
                        label = { Text("招待コード（任意）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(onClick = vm::requestJoin) { Text("申請") } },
            dismissButton = { TextButton(onClick = vm::dismissJoinDialog) { Text("キャンセル") } },
        )
    }

    if (state.showInfoDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissInfo,
            title = { Text(if (state.canViewGroupInfo) state.metadata?.name ?: ref.groupId else "非公開グループ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canViewGroupInfo) {
                        Text(state.metadata?.about.orEmpty().ifBlank { "説明はありません" })
                    } else {
                        Text(
                            "このグループの情報は非メンバーには表示されません。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.metadataWarning?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("リレー: ${ref.relayUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("グループID: ${ref.groupId}", style = MaterialTheme.typography.bodySmall)
                    if (state.canViewGroupInfo) state.creatorPubkey?.let { creatorPubkey ->
                        val creatorName = state.profiles[creatorPubkey]?.bestName
                            ?: shortGroupPubkey(creatorPubkey)
                        Text(
                            "作成者: $creatorName",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.canViewGroupInfo) {
                        Text(
                            "メンバー: " + (state.members?.size?.toString() ?: "非公開または取得不可"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("管理者: ${state.admins.size}", style = MaterialTheme.typography.bodySmall)
                            state.admins.take(8).forEach { admin ->
                                val profile = state.profiles[admin.pubkey]
                                val name = profile?.bestName ?: shortGroupPubkey(admin.pubkey)
                                val roles = admin.roles.takeIf { it.isNotEmpty() }?.joinToString(", ")
                                Text(
                                    if (roles != null) "・$name ($roles)" else "・$name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (state.admins.size > 8) {
                                Text(
                                    "ほか ${state.admins.size - 8} 人",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (state.canRepairAdmin) {
                        TextButton(
                            onClick = vm::repairAdmin,
                            enabled = !state.isRepairingAdmin,
                        ) {
                            if (state.isRepairingAdmin) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("管理者権限を修復")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    MembershipButton(state.membership, vm::showJoinDialog, vm::leave, ownPubkey != null)
                    if (state.canManageGroup) {
                        TextButton(onClick = vm::showDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("グループを削除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissInfo) { Text("閉じる") } },
        )
    }

    if (state.showEditDialog) {
        var editName by rememberSyncedTextFieldValue(state.editName)
        var editAbout by rememberSyncedTextFieldValue(state.editAbout)
        var editPicture by rememberSyncedTextFieldValue(state.editPicture)
        AlertDialog(
            onDismissRequest = vm::dismissEdit,
            title = { Text("グループを編集") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = {
                            editName = it
                            vm.updateEdit(name = it.text)
                        },
                        label = { Text("グループ名") },
                        singleLine = true,
                        enabled = !state.isEditing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editAbout,
                        onValueChange = {
                            editAbout = it
                            vm.updateEdit(about = it.text)
                        },
                        label = { Text("説明（任意）") },
                        enabled = !state.isEditing,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editPicture,
                        onValueChange = {
                            editPicture = it
                            vm.updateEdit(picture = it.text)
                        },
                        label = { Text("画像URL（任意）") },
                        singleLine = true,
                        enabled = !state.isEditing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GroupCreationSwitch(
                        label = "メンバーのみ閲覧",
                        checked = state.editIsPrivate,
                        enabled = !state.isEditing,
                        onCheckedChange = { vm.updateEdit(isPrivate = it) },
                    )
                    GroupCreationSwitch(
                        label = "メンバーのみ投稿",
                        checked = state.editIsRestricted,
                        enabled = !state.isEditing,
                        onCheckedChange = { vm.updateEdit(isRestricted = it) },
                    )
                    GroupCreationSwitch(
                        label = "非メンバーに情報を隠す",
                        checked = state.editIsHidden,
                        enabled = !state.isEditing,
                        onCheckedChange = { vm.updateEdit(isHidden = it) },
                    )
                    GroupCreationSwitch(
                        label = "招待コード限定",
                        checked = state.editIsClosed,
                        enabled = !state.isEditing,
                        onCheckedChange = { vm.updateEdit(isClosed = it) },
                    )
                    Text("対応イベント", style = MaterialTheme.typography.labelLarge)
                    Nip29SupportedKindsMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isEditing) {
                                    vm.updateEdit(supportedKindsMode = mode)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.editSupportedKindsMode == mode,
                                onClick = { vm.updateEdit(supportedKindsMode = mode) },
                                enabled = !state.isEditing,
                            )
                            Text(
                                when (mode) {
                                    Nip29SupportedKindsMode.ALL -> "すべて"
                                    Nip29SupportedKindsMode.TEXT_CHAT -> "テキストチャット（kind 9）"
                                    Nip29SupportedKindsMode.NONE -> "テキスト投稿なし"
                                },
                            )
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.isEditing) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally).size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = vm::saveEdit,
                    enabled = !state.isEditing && state.editName.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissEdit, enabled = !state.isEditing) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("グループを削除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${state.metadata?.name ?: ref.groupId}」をリレーから削除します。この操作は取り消せません。")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally).size(28.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = vm::deleteGroup, enabled = !state.isDeleting) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete, enabled = !state.isDeleting) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun MembershipButton(
    membership: Nip29Membership,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    loggedIn: Boolean,
) {
    if (!loggedIn) return
    when (membership) {
        Nip29Membership.JOINED -> TextButton(onClick = onLeave) { Text("退出") }
        Nip29Membership.PENDING -> Text("参加承認待ち", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Nip29Membership.INVITE_REQUIRED -> Button(onClick = onJoin) { Text("招待コードで参加") }
        Nip29Membership.REJECTED,
        Nip29Membership.NOT_JOINED,
        -> Button(onClick = onJoin) { Text("参加") }
    }
}

private fun shortGroupPubkey(pubkey: String): String =
    if (pubkey.length <= 16) pubkey else "${pubkey.take(8)}…${pubkey.takeLast(8)}"
