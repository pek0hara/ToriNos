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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nostr.torinos.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.CustomEmojiList
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.ui.components.NetworkImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEmojiSettingsScreen(
    onBack: () -> Unit = {},
    initialQuery: String = "",
    viewModel: CustomEmojiSettingsViewModel = viewModel { CustomEmojiSettingsViewModel() },
) {
    val emojis by CustomEmojiStore.emojis.collectAsState()
    val emojiLists by CustomEmojiStore.emojiLists.collectAsState()
    val state by viewModel.state.collectAsState()
    var discoverQuery by remember { mutableStateOf(initialQuery) }
    var registeredQuery by remember { mutableStateOf("") }
    var showRegisteredOnly by remember { mutableStateOf(false) }
    var selectedSet by remember { mutableStateOf<PublishedEmojiSet?>(null) }
    var selectedRegisteredSet by remember { mutableStateOf<CustomEmojiList?>(null) }
    val pagerState = rememberPagerState(pageCount = { EmojiSettingsTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = EmojiSettingsTab.entries[pagerState.currentPage]
    val savedEmojiUrls = remember(emojis) { emojis.associate { it.shortcode to it.imageUrl } }
    val filteredPublishedSets = remember(state.publishedSets, savedEmojiUrls, discoverQuery, showRegisteredOnly) {
        state.publishedSets
            .filterByQuery(discoverQuery)
            .filter { set ->
                !showRegisteredOnly || set.emojis.all { savedEmojiUrls[it.shortcode] == it.imageUrl }
            }
    }
    val filteredRegisteredLists = remember(emojiLists, registeredQuery) {
        emojiLists.filterCustomEmojiListsByQuery(registeredQuery)
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

    selectedRegisteredSet?.let { set ->
        RegisteredEmojiSetDialog(
            set = set,
            onUnregister = {
                CustomEmojiStore.removeList(set.id, set.emojis)
                selectedRegisteredSet = null
            },
            onDismiss = { selectedRegisteredSet = null },
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
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                EmojiSettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(tab.ordinal)
                            }
                        },
                        text = {
                            Text(
                                when (tab) {
                                    EmojiSettingsTab.Discover -> "セットを探す"
                                    EmojiSettingsTab.Registered -> "登録済み (${emojiLists.size})"
                                },
                            )
                        },
                    )
                }
            }

            EmojiSearchField(
                value = when (selectedTab) {
                    EmojiSettingsTab.Discover -> discoverQuery
                    EmojiSettingsTab.Registered -> registeredQuery
                },
                onValueChange = { value ->
                    when (selectedTab) {
                        EmojiSettingsTab.Discover -> discoverQuery = value
                        EmojiSettingsTab.Registered -> registeredQuery = value
                    }
                },
                placeholder = when (selectedTab) {
                    EmojiSettingsTab.Discover -> "セット名・絵文字名で検索"
                    EmojiSettingsTab.Registered -> "登録済みセットを検索"
                },
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
            ) { page ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    when (EmojiSettingsTab.entries[page]) {
                        EmojiSettingsTab.Discover -> {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilterChip(
                                        selected = showRegisteredOnly,
                                        onClick = { showRegisteredOnly = !showRegisteredOnly },
                                        label = { Text("登録済みのみ") },
                                        leadingIcon = if (showRegisteredOnly) {
                                            {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                    Text(
                                        text = if (state.isLoadingPublishedSets) {
                                            "読み込み中…"
                                        } else {
                                            "${filteredPublishedSets.size}件"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (!state.isLoadingPublishedSets && filteredPublishedSets.isEmpty()) {
                                item {
                                    EmptyText(
                                        if (showRegisteredOnly) {
                                            "登録済みの絵文字セットはありません"
                                        } else {
                                            "公開絵文字セットが見つかりません"
                                        },
                                    )
                                }
                            } else {
                                items(filteredPublishedSets, key = { "published-${it.id}" }) { set ->
                                    PublishedEmojiSetRow(
                                        set = set,
                                        isRegistered = set.emojis.all {
                                            savedEmojiUrls[it.shortcode] == it.imageUrl
                                        },
                                        onRegister = { CustomEmojiStore.addList(set.id, set.name, set.emojis) },
                                        onClick = { selectedSet = set },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }

                        EmojiSettingsTab.Registered -> {
                            item {
                                SectionHeader(
                                    title = "登録済みセット",
                                    trailing = if (registeredQuery.isBlank()) {
                                        "${emojiLists.size}件・${emojis.size}個"
                                    } else {
                                        "${filteredRegisteredLists.size}/${emojiLists.size}件"
                                    },
                                )
                            }

                            if (filteredRegisteredLists.isEmpty()) {
                                item {
                                    EmptyText(
                                        if (emojiLists.isEmpty()) {
                                            "登録済みのセットはありません\n「セットを探す」から追加できます"
                                        } else {
                                            "登録済みセットが見つかりません"
                                        },
                                    )
                                }
                            } else {
                                items(filteredRegisteredLists, key = { "registered-${it.id}" }) { set ->
                                    RegisteredEmojiSetRow(
                                        set = set,
                                        onClick = { selectedRegisteredSet = set },
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
}

@Composable
private fun EmojiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "検索語を消去",
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
            if (isRegistered) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "登録済み",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, end = 12.dp),
                )
            } else {
                TextButton(onClick = onRegister) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("登録")
                }
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
private fun RegisteredEmojiSetRow(
    set: CustomEmojiList,
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = set.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${set.emojis.size}個",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "セットの詳細",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EmojiPreviewRow(set.emojis)
    }
}

@Composable
private fun EmojiPreviewRow(emojis: List<CustomEmoji>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emojis.take(8).forEach { emoji ->
            NetworkImage(
                url = emoji.imageUrl,
                contentDescription = emoji.shortcode,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(28.dp),
            )
        }
        if (emojis.size > 8) {
            Text(
                text = "+${emojis.size - 8}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class EmojiSettingsTab {
    Discover,
    Registered,
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

@Composable
private fun RegisteredEmojiSetDialog(
    set: CustomEmojiList,
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
                        text = "${set.emojis.size}個の絵文字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(set.emojis, key = { "registered-dialog-${it.shortcode}" }) { emoji ->
                    CustomEmojiRow(
                        emoji = emoji,
                        onDelete = null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUnregister) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("セットを登録解除")
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

private fun List<CustomEmojiList>.filterCustomEmojiListsByQuery(query: String): List<CustomEmojiList> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return this
    return filter { set ->
        set.name.lowercase().contains(normalizedQuery) ||
            set.emojis.any { emoji ->
                emoji.shortcode.lowercase().contains(normalizedQuery) ||
                    emoji.imageUrl.lowercase().contains(normalizedQuery)
            }
    }
}
