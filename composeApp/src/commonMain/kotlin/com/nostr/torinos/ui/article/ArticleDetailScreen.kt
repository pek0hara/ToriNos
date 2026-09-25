package com.nostr.torinos.ui.article

import com.nostr.torinos.article.commentReplyTarget
import com.nostr.torinos.model.ReplyTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.NetworkImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    pubkey: String,
    identifier: String,
    ownPubkey: String?,
    onBack: () -> Unit,
    onEditArticle: (pubkey: String, identifier: String) -> Unit,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
    onTopicClick: (String) -> Unit,
    onComment: (target: ReplyTarget, preview: String) -> Unit,
    commentPostedSignal: Int,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelayUrl by RelayStore.selectedArticleRelayUrl.collectAsState()
    val isRelayStoreLoaded by RelayStore.isLoaded.collectAsState()
    val activeRelayUrl = selectedRelayUrl
    if (!isRelayStoreLoaded || activeRelayUrl == null) {
        RelaySelectionPendingContent(
            isLoaded = isRelayStoreLoaded,
            hasEnabledRelays = relays.isNotEmpty(),
        )
        return
    }

    val viewModel: ArticleDetailViewModel = accountSessionViewModel(
        key = "article-$pubkey-$identifier-$activeRelayUrl",
    ) { accountSession ->
        ArticleDetailViewModel(
            pubkey,
            identifier,
            relayUrl = activeRelayUrl,
            accountSession = accountSession,
        )
    }
    val state by viewModel.state.collectAsState()
    // 記事へのコメント投稿が完了したら、コメント一覧を取り直す。
    val initialCommentPostedSignal = remember(viewModel) { commentPostedSignal }
    LaunchedEffect(commentPostedSignal) {
        if (commentPostedSignal != initialCommentPostedSignal) viewModel.reloadEngagement()
    }
    val reactionActions = remember(viewModel) {
        if (viewModel.canReact) {
            ArticleReactionActions(
                onLike = viewModel::like,
                onUnlike = viewModel::unlike,
                onReact = viewModel::react,
                onUnreact = viewModel::unreact,
            )
        } else {
            null
        }
    }
    var showDeleteDialog by rememberSaveable(pubkey, identifier) { mutableStateOf(false) }
    var showCommentsSheet by rememberSaveable(pubkey, identifier) { mutableStateOf(false) }
    // 本文を下へ読み進めている間は下部バーを隠し、上へ戻すと再表示する。
    var isEngagementBarVisible by remember { mutableStateOf(true) }
    val barScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                when {
                    available.y < -BarToggleThresholdPx -> isEngagementBarVisible = false
                    available.y > BarToggleThresholdPx -> isEngagementBarVisible = true
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(state.deleteCompletedCount) {
        if (state.deleteCompletedCount > 0) {
            showDeleteDialog = false
            onBack()
        }
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
                        text = state.article?.displayTitle ?: "記事",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    val article = state.article
                    if (article != null && ownPubkey != null && article.event.pubkey == ownPubkey) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !state.isDeleting,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "記事を削除")
                        }
                        IconButton(
                            onClick = { onEditArticle(article.event.pubkey, article.meta.identifier) },
                            enabled = !state.isDeleting,
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "記事を編集")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.article != null -> state.article?.let { article ->
                // 下部バーは本文の上に重ねる。バーの出入りで本文の高さを変えず、スクロール中の再レイアウトを避ける。
                val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(barScrollConnection),
                        contentPadding = PaddingValues(bottom = EngagementBarHeight + navigationBarBottom + 24.dp),
                    ) {
                        item(contentType = "article") {
                            ArticleDetailContent(
                                article = article,
                                quotedEvents = state.quotedEvents,
                                quotedProfiles = state.quotedProfiles,
                                loadingQuoteIds = state.loadingQuoteIds,
                                onUserClick = onUserClick,
                                onNoteClick = onNoteClick,
                                onTopicClick = onTopicClick,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = isEngagementBarVisible,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        ArticleEngagementBar(
                            engagement = state.engagement,
                            actions = reactionActions,
                            onOpenComments = { showCommentsSheet = true },
                            onComment = { onComment(article.commentReplyTarget(), article.displayTitle) },
                        )
                    }
                }
            }
        }
    }

    val sheetArticle = state.article
    if (showCommentsSheet && sheetArticle != null) {
        ArticleCommentsSheet(
            engagement = state.engagement,
            actions = reactionActions,
            onUserClick = { pubkey ->
                showCommentsSheet = false
                onUserClick(pubkey)
            },
            onComment = { onComment(sheetArticle.commentReplyTarget(), sheetArticle.displayTitle) },
            onRetry = viewModel::reloadEngagement,
            onDismiss = { showCommentsSheet = false },
        )
    }

    if (showDeleteDialog) {
        DeleteArticleDialog(
            isDeleting = state.isDeleting,
            error = state.deleteError,
            onDismiss = {
                if (!state.isDeleting) showDeleteDialog = false
            },
            onConfirm = viewModel::deleteArticle,
        )
    }
}

@Composable
private fun DeleteArticleDialog(
    isDeleting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("記事を削除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("有効なリレーへ削除要求を送信します。対応していないリレーやキャッシュ済みデータからの削除は保証されません。")
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("削除する")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun ArticleDetailContent(
    article: ArticleItem,
    quotedEvents: Map<String, NostrEvent>,
    quotedProfiles: Map<String, NostrProfile>,
    loadingQuoteIds: Set<String>,
    onUserClick: (pubkey: String) -> Unit,
    onNoteClick: (eventId: String) -> Unit,
    onTopicClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = article.displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        ArticleAuthorLine(article = article, onUserClick = { onUserClick(article.event.pubkey) })
        article.meta.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
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
        MarkdownBody(
            content = article.event.content,
            articleQuoteIds = quotedEventIds(article.event),
            quotedEvents = quotedEvents,
            quotedProfiles = quotedProfiles,
            loadingQuoteIds = loadingQuoteIds,
            onUserClick = onUserClick,
            onNoteClick = onNoteClick,
        )
        if (article.meta.topics.isNotEmpty()) {
            ArticleTopics(
                topics = article.meta.topics,
                onTopicClick = onTopicClick,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val BarToggleThresholdPx = 6f
