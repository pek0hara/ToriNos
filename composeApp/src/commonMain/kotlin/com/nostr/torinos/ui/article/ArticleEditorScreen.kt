package com.nostr.torinos.ui.article

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountScopedViewModelKey
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.ui.relay.RelaySettingsViewModel
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException

private enum class ArticleEditorTab(val label: String) {
    Edit("編集"),
    Preview("プレビュー"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleEditorScreen(
    onBack: () -> Unit,
    onPublished: (pubkey: String, identifier: String) -> Unit,
    editPubkey: String? = null,
    editIdentifier: String? = null,
    relayUrl: String? = null,
    viewModelOverride: ArticleEditorViewModel? = null,
) {
    val editorViewModel = viewModelOverride ?: viewModel(
        key = accountScopedViewModelKey(
            "article-editor-${editPubkey.orEmpty()}-${editIdentifier.orEmpty()}-${relayUrl.orEmpty()}",
        ),
    ) {
        ArticleEditorViewModel(
            editPubkey = editPubkey,
            editIdentifier = editIdentifier,
            relayUrl = relayUrl,
        )
    }
    val state by editorViewModel.state.collectAsState()
    val relayEntries by RelayStore.entries.collectAsState()
    val enabledRelayCount = relayEntries.count { it.enabled }
    var selectedTab by rememberSaveable { mutableStateOf(ArticleEditorTab.Edit) }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
    var showRelaySettingsDialog by rememberSaveable { mutableStateOf(false) }
    var navigationError by remember { mutableStateOf<String?>(null) }

    fun requestBack() {
        when {
            state.isPublishing -> Unit
            state.hasUnsavedChanges -> showDiscardConfirmation = true
            else -> onBack()
        }
    }

    val pickCoverImage = rememberImagePickerLauncher { bytes, mimeType ->
        if (bytes != null && mimeType != null) {
            editorViewModel.uploadCoverImage(bytes, mimeType)
        }
    }
    val pickBodyImage = rememberImagePickerLauncher { bytes, mimeType ->
        if (bytes != null && mimeType != null) {
            editorViewModel.uploadBodyImage(bytes, mimeType)
        }
    }

    LaunchedEffect(state.publishedArticle) {
        state.publishedArticle?.let { article ->
            try {
                onPublished(article.pubkey, article.identifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("ArticleEditor", e, "Failed to open published article")
                navigationError = e.message ?: "投稿した記事を開けませんでした"
            }
        }
    }
    ArticleEditorBackHandler(enabled = state.isPublishing || state.hasUnsavedChanges) {
        requestBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                AppTopBar(
                    navigationIcon = {
                        IconButton(
                            onClick = ::requestBack,
                            enabled = !state.isPublishing,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                            )
                        }
                    },
                    title = { Text(if (state.isEditing) "記事を編集" else "記事を書く") },
                    actions = {
                        IconButton(
                            onClick = { showRelaySettingsDialog = true },
                            enabled = !state.isPublishing,
                        ) {
                            Icon(
                                Icons.Default.SettingsInputAntenna,
                                contentDescription = "リレー設定",
                            )
                        }
                        Button(
                            onClick = editorViewModel::publish,
                            enabled = state.canPublish && enabledRelayCount > 0,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            if (state.isPublishing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(if (state.isEditing) "更新" else "公開")
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    ArticleEditorTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (state.isLoadingArticle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            when (selectedTab) {
                ArticleEditorTab.Edit -> ArticleEditContent(
                    state = state,
                    enabledRelayCount = enabledRelayCount,
                    onOpenRelaySettings = { showRelaySettingsDialog = true },
                    onTitleChange = editorViewModel::onTitleChange,
                    onSummaryChange = editorViewModel::onSummaryChange,
                    onContentChange = editorViewModel::onContentChange,
                    onCoverImageUrlChange = editorViewModel::onCoverImageUrlChange,
                    onTopicsChange = editorViewModel::onTopicsChange,
                    onPickCoverImage = pickCoverImage,
                    onPickBodyImage = pickBodyImage,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
                ArticleEditorTab.Preview -> ArticlePreviewContent(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }

    if (showRelaySettingsDialog) {
        ArticleRelaySettingsDialog(
            onDismiss = { showRelaySettingsDialog = false },
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("記事の編集を終了しますか？") },
            text = { Text("入力した内容は保存されません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onBack()
                    },
                ) {
                    Text("破棄して終了")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("編集を続ける")
                }
            },
        )
    }

    navigationError?.let { message ->
        AlertDialog(
            onDismissRequest = { navigationError = null },
            title = { Text("記事は投稿されました") },
            text = { Text("記事詳細を開けませんでした。\n$message") },
            confirmButton = {
                TextButton(
                    onClick = {
                        navigationError = null
                        state.publishedArticle?.let { article ->
                            try {
                                onPublished(article.pubkey, article.identifier)
                            } catch (e: Throwable) {
                                logException("ArticleEditor", e, "Retry opening published article failed")
                                navigationError = e.message ?: "投稿した記事を開けませんでした"
                            }
                        }
                    },
                ) {
                    Text("再試行")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        navigationError = null
                        onBack()
                    },
                ) {
                    Text("一覧へ戻る")
                }
            },
        )
    }
}

@Composable
private fun ArticleEditContent(
    state: ArticleEditorState,
    enabledRelayCount: Int,
    onOpenRelaySettings: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onCoverImageUrlChange: (String) -> Unit,
    onTopicsChange: (String) -> Unit,
    onPickCoverImage: () -> Unit,
    onPickBodyImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("タイトル（必須）") },
                singleLine = true,
                enabled = !state.isPublishing,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本文（Markdown・必須）",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(
                        onClick = onPickBodyImage,
                        enabled = !state.isUploadingBodyImage && !state.isPublishing,
                    ) {
                        if (state.isUploadingBodyImage) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Image, contentDescription = "本文に画像を追加")
                        }
                    }
                }
                OutlinedTextField(
                    value = state.content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    placeholder = {
                        Text("# 見出し\n\n本文をMarkdown形式で入力してください")
                    },
                    supportingText = {
                        Text(state.content.length.toString())
                    },
                    enabled = !state.isPublishing,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "カバー画像",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                state.coverImageUrl.takeIf { it.isNotBlank() }?.let { imageUrl ->
                    NetworkImage(
                        url = imageUrl,
                        contentDescription = "カバー画像",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        maxDecodeSizePx = 1000,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.coverImageUrl,
                        onValueChange = onCoverImageUrlChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("HTTPS URL") },
                        singleLine = true,
                        enabled = !state.isUploadingCover && !state.isPublishing,
                    )
                    IconButton(
                        onClick = onPickCoverImage,
                        enabled = !state.isUploadingCover && !state.isPublishing,
                    ) {
                        if (state.isUploadingCover) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "カバー画像を選択")
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.summary,
                onValueChange = onSummaryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("概要") },
                minLines = 2,
                maxLines = 4,
                enabled = !state.isPublishing,
            )
        }
        item {
            OutlinedTextField(
                value = state.topicsInput,
                onValueChange = onTopicsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("トピック") },
                placeholder = { Text("nostr, 技術, 日記") },
                supportingText = { Text("空白またはカンマで区切って入力") },
                enabled = !state.isPublishing,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "投稿先リレー",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (enabledRelayCount > 0) {
                            "有効なリレー $enabledRelayCount 件すべて"
                        } else {
                            "投稿先リレーが設定されていません"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabledRelayCount == 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onOpenRelaySettings,
                    enabled = !state.isPublishing,
                ) {
                    Icon(
                        Icons.Default.SettingsInputAntenna,
                        contentDescription = "リレー設定",
                    )
                }
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
private fun ArticleRelaySettingsDialog(
    onDismiss: () -> Unit,
    viewModel: RelaySettingsViewModel = viewModel(
        key = accountScopedViewModelKey("article-post-relays"),
    ) { RelaySettingsViewModel() },
) {
    val relayEntries by viewModel.entries.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リレー設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (relayEntries.isEmpty()) {
                    Text(
                        text = "リレーが設定されていません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(320.dp),
                    ) {
                        items(relayEntries, key = { it.url }) { entry ->
                            ArticleRelayRow(
                                entry = entry,
                                onToggle = { enabled -> viewModel.setEnabled(entry.url, enabled) },
                                onDelete = { viewModel.remove(entry.url) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun ArticleRelayRow(
    entry: RelayEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = entry.enabled,
            onCheckedChange = onToggle,
        )
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
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
private fun ArticlePreviewContent(
    state: ArticleEditorState,
    modifier: Modifier = Modifier,
) {
    if (state.title.isBlank() && state.content.isBlank()) {
        Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "タイトルと本文を入力するとプレビューを表示します",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = state.title.ifBlank { "無題の記事" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        state.coverImageUrl.takeIf { it.isNotBlank() }?.let { imageUrl ->
            item {
                NetworkImage(
                    url = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 1200,
                )
            }
        }
        item {
            MarkdownBody(
                content = state.content,
                articleQuoteIds = emptyList(),
                quotedEvents = emptyMap(),
                quotedProfiles = emptyMap(),
                loadingQuoteIds = emptySet(),
                onUserClick = {},
                onNoteClick = {},
            )
        }
        parseArticleTopics(state.topicsInput).takeIf { it.isNotEmpty() }?.let { topics ->
            item {
                Text(
                    text = topics.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
