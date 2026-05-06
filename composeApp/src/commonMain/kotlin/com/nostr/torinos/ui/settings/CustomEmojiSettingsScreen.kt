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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
    val savedEmojiUrls = remember(emojis) { emojis.associate { it.shortcode to it.imageUrl } }
    val filteredPublishedSets = remember(state.publishedSets, searchQuery) {
        state.publishedSets.filterByQuery(searchQuery)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("カスタム絵文字設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshPublishedSets) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "再読み込み",
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
                        label = { Text("公開絵文字セットを検索") },
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
                            onRegister = { CustomEmojiStore.addAll(set.emojis) },
                        )
                        HorizontalDivider()
                    }
                }

                item {
                    SectionHeader(
                        title = "登録済み",
                        trailing = if (emojis.isEmpty()) "0件" else "${emojis.size}件",
                    )
                }

                if (emojis.isEmpty()) {
                    item {
                        EmptyText("カスタム絵文字が設定されていません")
                    }
                } else {
                    items(emojis, key = { "local-${it.shortcode}" }) { emoji ->
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
    onDelete: () -> Unit,
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
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PublishedEmojiSetRow(
    set: PublishedEmojiSet,
    isRegistered: Boolean,
    onRegister: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                onClick = onRegister,
                enabled = !isRegistered,
            ) {
                Icon(
                    imageVector = if (isRegistered) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(if (isRegistered) "登録済み" else "登録")
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

private fun List<PublishedEmojiSet>.filterByQuery(query: String): List<PublishedEmojiSet> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return this
    return filter { set ->
        set.name.lowercase().contains(normalizedQuery) ||
            set.authorPubkey.lowercase().contains(normalizedQuery) ||
            set.emojis.any { it.shortcode.lowercase().contains(normalizedQuery) }
    }
}
