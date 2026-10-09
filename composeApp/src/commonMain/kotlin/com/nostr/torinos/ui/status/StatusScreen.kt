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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlin.time.Clock
import kotlinx.coroutines.delay
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.MUSIC_STATUS_IDENTIFIER
import com.nostr.torinos.status.StatusEntry
import com.nostr.torinos.status.StatusPublishState
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.ui.components.RelaySelector
import com.nostr.torinos.ui.service.ServiceHeaderDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    ownPubkey: String?,
    ownProfile: NostrProfile? = null,
    showComposer: Boolean,
    onComposerShown: () -> Unit,
    onRequestComposer: () -> Unit,
    onUserClick: (String) -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenRelaySettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** false の間は描画だけして、購読しない。 */
    isActive: Boolean = true,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedStatusRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()

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
        key = "status",
    ) { accountSession ->
        StatusViewModel(accountSession = accountSession)
    }
    val state by viewModel.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    val headerBackgroundColor = MaterialTheme.colorScheme.background
    val headerContentColor = MaterialTheme.colorScheme.onBackground

    if (isActive) {
        LifecycleStartEffect(viewModel, activeRelayUrl) {
            viewModel.start(activeRelayUrl)
            onStopOrDispose { viewModel.stop() }
        }
    }

    LaunchedEffect(showComposer) {
        if (showComposer) {
            showDialog = true
            onComposerShown()
        }
    }
    LaunchedEffect(state.publishState) {
        val succeeded = state.publishState as? StatusPublishState.Succeeded
        if (succeeded != null) {
            showDialog = false
            viewModel.consumePublishResult(succeeded.eventId)
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
                        RelaySelector(
                            relays = relays,
                            selectedRelayUrl = selectedRelayUrl,
                            onRelaySelected = RelayStore::setSelectedStatusRelayUrl,
                            onOpenRelaySettings = onOpenRelaySettings,
                        )
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
                ServiceHeaderDivider()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                if (state.loadState == StatusLoadState.Loading && state.statuses.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    val now by produceState(Clock.System.now().epochSeconds) {
                        while (true) {
                            delay(RELATIVE_TIME_REFRESH_MS)
                            value = Clock.System.now().epochSeconds
                        }
                    }
                    val groups = remember(state.statuses, ownPubkey) {
                        groupStatusesByUser(state.statuses, excludePubkey = ownPubkey)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 末尾の行が投稿ボタンの裏に隠れないようにする。
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        if (ownPubkey != null) {
                            item(key = "own", contentType = "own") {
                                OwnStatusCard(
                                    pubkey = ownPubkey,
                                    profile = ownProfile,
                                    statuses = remember(state.ownStatuses) {
                                        state.ownStatuses.values.sortedForDisplay()
                                    },
                                    now = now,
                                    onClick = onRequestComposer,
                                )
                                StatusDivider()
                            }
                        }
                        when {
                            state.selectedCategories.isEmpty() -> item(key = "empty", contentType = "empty") {
                                StatusEmptyText("カテゴリが選択されていません")
                            }
                            groups.isEmpty() -> item(key = "empty", contentType = "empty") {
                                StatusEmptyText("ステータスがありません")
                            }
                            else -> items(
                                items = groups,
                                key = { it.pubkey },
                                contentType = { "status" },
                            ) { group ->
                                StatusGroupRow(
                                    group = group,
                                    profile = state.profiles[group.pubkey],
                                    now = now,
                                    onUserClick = { onUserClick(group.pubkey) },
                                )
                                StatusDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        StatusComposerSheet(
            title = "ステータス",
            initialStatusTag = GENERAL_STATUS_IDENTIFIER,
            initialStatuses = state.ownStatuses.mapValues { (_, status) ->
                StatusComposerValue(
                    content = status.event.content,
                    expiration = status.expiration,
                    referenceUrl = status.referenceUrls.firstOrNull().orEmpty(),
                )
            },
            isPublishing = state.publishState is StatusPublishState.Publishing,
            errorMessage = (state.publishState as? StatusPublishState.Failed)?.message,
            onDismiss = {
                showDialog = false
                viewModel.clearError()
            },
            onSubmit = { tag, content, expiration, referenceUrl ->
                viewModel.publishStatus(tag, content, expiration, referenceUrl)
            },
            onDelete = viewModel::deleteStatus,
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
            val selected = category in selectedCategories
            FilterChip(
                selected = selected,
                onClick = { onToggle(category) },
                label = {
                    Text(
                        text = category.statusFilterLabel(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun OwnStatusCard(
    pubkey: String,
    profile: NostrProfile?,
    statuses: List<StatusEntry>,
    now: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = if (statuses.isEmpty()) Alignment.CenterVertically else Alignment.Top,
    ) {
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 36,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (statuses.isEmpty()) {
                Text(
                    text = "ステータスを設定…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "自分のステータス",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                statuses.forEach { status -> StatusLine(status = status, now = now) }
            }
        }
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "ステータスを編集",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusGroupRow(
    group: StatusUserGroup,
    profile: NostrProfile?,
    now: Long,
    onUserClick: () -> Unit,
) {
    val latestCreatedAt = group.latestCreatedAt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUserClick)
            // 1日以上更新のない人は「今」の情報として弱いので控えめに見せる。
            .alpha(if (isStaleStatus(latestCreatedAt, now)) STALE_ROW_ALPHA else 1f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AvatarCircle(
            pubkey = group.pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = 36,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileNameText(
                    profile = profile,
                    fallback = group.pubkey.take(8) + "…" + group.pubkey.takeLast(8),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusRelativeTimeText(latestCreatedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            group.statuses.forEach { status -> StatusLine(status = status, now = now) }
        }
    }
}

@Composable
private fun StatusLine(
    status: StatusEntry,
    now: Long,
) {
    val isGeneral = status.identifier == GENERAL_STATUS_IDENTIFIER
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (status.identifier) {
                GENERAL_STATUS_IDENTIFIER -> Unit
                MUSIC_STATUS_IDENTIFIER -> Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "音楽",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = "#" + customStatusTagLabel(status.identifier),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            LinkedText(
                text = status.event.content,
                style = MaterialTheme.typography.bodyMedium,
                customEmojis = status.customEmojis,
                maxLines = if (isGeneral) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        status.referenceUrls.forEach { url -> ReferenceUrlLine(url) }
        status.expiration?.let { expiration ->
            StatusMetaLine(
                icon = Icons.Default.AccessTime,
                text = statusExpirationRemainingText(expiration, now),
            )
        }
    }
}

@Composable
private fun ReferenceUrlLine(url: String) {
    val uriHandler = LocalUriHandler.current
    StatusMetaLine(
        icon = Icons.Default.Link,
        text = referenceUrlDisplayHost(url),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } },
    )
}

@Composable
private fun StatusMetaLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun StatusEmptyText(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun statusExpirationRemainingText(expiration: Long, now: Long): String {
    val remaining = expiration - now
    return when {
        remaining <= 0L -> "終了"
        remaining < 60L -> "まもなく終了"
        remaining < 3600L -> "あと${remaining / 60L}分"
        remaining < 2L * 86400L -> "あと${remaining / 3600L}時間"
        else -> "あと${remaining / 86400L}日"
    }
}

private fun String.statusFilterLabel(): String =
    when (this) {
        GENERAL_STATUS_IDENTIFIER -> "💬 一般"
        MUSIC_STATUS_IDENTIFIER -> "♫ 音楽"
        else -> "#" + customStatusTagLabel(this)
    }

private const val STALE_ROW_ALPHA = 0.6f
private const val RELATIVE_TIME_REFRESH_MS = 60_000L
