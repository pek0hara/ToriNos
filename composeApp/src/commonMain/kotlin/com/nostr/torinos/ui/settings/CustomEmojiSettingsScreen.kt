package com.nostr.torinos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.ui.components.NetworkImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEmojiSettingsScreen(
    onBack: () -> Unit = {},
    initialQuery: String = "",
    viewModel: CustomEmojiSettingsViewModel = viewModel { CustomEmojiSettingsViewModel() },
) {
    val emojis by CustomEmojiStore.emojis.collectAsState()
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf(initialQuery) }
    var showRegisteredOnly by remember { mutableStateOf(false) }
    var selectedSet by remember { mutableStateOf<PublishedEmojiSet?>(null) }
    val savedEmojiUrls = remember(emojis) { emojis.associate { it.shortcode to it.imageUrl } }
    val filteredPublishedSets = remember(state.publishedSets, savedEmojiUrls, searchQuery, showRegisteredOnly) {
        state.publishedSets
            .filterByQuery(searchQuery)
            .filter { set ->
                !showRegisteredOnly || set.emojis.all { savedEmojiUrls[it.shortcode] == it.imageUrl }
            }
    }
    val filteredEmojis = remember(emojis, searchQuery) {
        emojis.filterCustomEmojisByQuery(searchQuery)
    }

    selectedSet?.let { set ->
        PublishedEmojiSetDialog(
            set = set,
            isRegistered = set.emojis.all { savedEmojiUrls[it.shortcode] == it.imageUrl },
            onRegister = { CustomEmojiStore.addList(set.id, set.name, set.emojis) },
            onUnregister = { CustomEmojiStore.removeList(set.id, set.emojis) },
            onDismiss = { selectedSet = null },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = { Text("カスタム絵文字設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshPublishedSets) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "再読み込み",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("絵文字セット・登録済み絵文字を検索") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    RegisteredOnlyFilterRow(
                        checked = showRegisteredOnly,
                        onCheckedChange = { showRegisteredOnly = it },
                    )
                    HorizontalDivider()
                }

                item {
                    SectionHeader(
                        title = "公開絵文字セット",
                        trailing = if (state.isLoadingPublishedSets) {
                            "読み込み中"
                        } else {
                            "${filteredPublishedSets.size}/${state.publishedSets.size}件"
                        },
                        showProgress = state.isLoadingPublishedSets,
                    )
                }

                if (!state.isLoadingPublishedSets && filteredPublishedSets.isEmpty()) {
                    item {
                        EmptyText("公開絵文字セットが見つかりません")
                    }
                } else {
                    items(filteredPublishedSets, key = { "published-${it.id}" }) { set ->
                        PublishedEmojiSetRow(
                            set = set,
                            isRegistered = set.emojis.all { savedEmojiUrls[it.shortcode] == it.imageUrl },
                            onRegister = { CustomEmojiStore.addList(set.id, set.name, set.emojis) },
                            onUnregister = { CustomEmojiStore.removeList(set.id, set.emojis) },
                            onClick = { selectedSet = set },
                        )
                        HorizontalDivider()
                    }
                }

                item {
                    SectionHeader(
                        title = "登録済み",
                        trailing = if (searchQuery.isBlank()) {
                            "${emojis.size}件"
                        } else {
                            "${filteredEmojis.size}/${emojis.size}件"
                        },
                    )
                }

                if (filteredEmojis.isEmpty()) {
                    item {
                        EmptyText(
                            if (emojis.isEmpty()) {
                                "カスタム絵文字が設定されていません"
                            } else {
                                "登録済みカスタム絵文字が見つかりません"
                            },
                        )
                    }
                } else {
                    items(filteredEmojis, key = { "local-${it.shortcode}" }) { emoji ->
                        CustomEmojiRow(
                            emoji = emoji,
                            onDelete = { CustomEmojiStore.remove(emoji.shortcode) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisteredOnlyFilterRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "登録済みセットのみ",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "公開絵文字セットの一覧を登録済みに絞り込みます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String,
    showProgress: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CustomEmojiRow(
    emoji: CustomEmoji,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkImage(
            url = emoji.imageUrl,
            contentDescription = emoji.shortcode,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = ":${emoji.shortcode}:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = emoji.imageUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PublishedEmojiSetRow(
    set: PublishedEmojiSet,
    isRegistered: Boolean,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = set.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${set.emojis.size}個 / ${set.authorPubkey.take(8)}...${set.authorPubkey.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = if (isRegistered) onUnregister else onRegister,
            ) {
                Icon(
                    imageVector = if (isRegistered) Icons.Default.Delete else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(if (isRegistered) "登録解除" else "登録")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            set.emojis.take(8).forEach { emoji ->
                NetworkImage(
                    url = emoji.imageUrl,
                    contentDescription = emoji.shortcode,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (set.emojis.size > 8) {
                Text(
                    text = "+${set.emojis.size - 8}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PublishedEmojiSetDialog(
    set: PublishedEmojiSet,
    isRegistered: Boolean,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(set.name) },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = "${set.emojis.size}個 / ${set.authorPubkey.take(8)}...${set.authorPubkey.takeLast(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(set.emojis, key = { "dialog-${it.shortcode}" }) { emoji ->
                    CustomEmojiRow(
                        emoji = emoji,
                        onDelete = null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (isRegistered) onUnregister else onRegister,
            ) {
                Icon(
                    imageVector = if (isRegistered) Icons.Default.Delete else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(if (isRegistered) "登録解除" else "登録")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

private fun List<PublishedEmojiSet>.filterByQuery(query: String): List<PublishedEmojiSet> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return this
    return filter { set ->
        set.name.lowercase().contains(normalizedQuery) ||
            set.authorPubkey.lowercase().contains(normalizedQuery) ||
            set.emojis.any { it.shortcode.lowercase().contains(normalizedQuery) }
    }
}

private fun List<CustomEmoji>.filterCustomEmojisByQuery(query: String): List<CustomEmoji> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return this
    return filter { emoji ->
        emoji.shortcode.lowercase().contains(normalizedQuery) ||
            emoji.imageUrl.lowercase().contains(normalizedQuery)
    }
}
