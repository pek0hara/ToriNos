package com.nostr.torinos.ui.article

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.nostr.torinos.article.ArticleEngagementSummary
import com.nostr.torinos.article.ArticleEngagementTarget
import com.nostr.torinos.model.ArticleItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [onAuthorClick]がnullの場合、カードの著者行は独立したタップ領域にならず、カード全体で記事を開く。
 */
@Composable
internal fun ArticleListContent(
    state: ArticleListState,
    listState: LazyListState,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
    onAuthorClick: ((pubkey: String) -> Unit)?,
    onTopicClick: ((String) -> Unit)?,
    engagementFor: ((address: String) -> StateFlow<ArticleEngagementSummary?>)? = null,
    onVisibleArticlesChanged: ((List<ArticleEngagementTarget>) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    emptyText: String = "記事がありません",
) {
    if (onVisibleArticlesChanged != null) {
        TrackVisibleArticles(listState, state.articles, onVisibleArticlesChanged)
    }
    when {
        state.isInitialLoad -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null -> Box(
            modifier = modifier.padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.articles.isEmpty() -> EmptyArticleList(modifier, emptyText)
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = contentPadding,
        ) {
            items(
                items = state.articles,
                key = { it.address },
                contentType = { "article" },
            ) { article ->
                val engagement = remember(article.address, engagementFor) {
                    engagementFor?.invoke(article.address)
                }
                ArticleCard(
                    article = article,
                    onClick = { onArticleClick(article.event.pubkey, article.meta.identifier) },
                    onAuthorClick = onAuthorClick?.let { { it(article.event.pubkey) } },
                    onTopicClick = onTopicClick,
                    engagement = engagement,
                )
                HorizontalDivider()
            }
            if (state.isLoadingMore) {
                item(contentType = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun EmptyArticleList(modifier: Modifier, text: String) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 見えている記事と直後の数件を、スクロールが止まってから少し待って通知する。
 * スクロール中は通知せず、画面を離れるときは空リストで待機中の取得をやめさせる。
 */
@OptIn(FlowPreview::class)
@Composable
private fun TrackVisibleArticles(
    listState: LazyListState,
    articles: List<ArticleItem>,
    onVisibleArticlesChanged: (List<ArticleEngagementTarget>) -> Unit,
) {
    val latestCallback by rememberUpdatedState(onVisibleArticlesChanged)
    LaunchedEffect(listState, articles) {
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                val first = visible.minOrNull() ?: 0
                val last = (visible.maxOrNull() ?: -1) + ENGAGEMENT_PREFETCH_COUNT
                (first..last).mapNotNull { index ->
                    articles.getOrNull(index)?.let { ArticleEngagementTarget(it.address, it.event.id) }
                }
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(ENGAGEMENT_SETTLE_MILLIS)
            .collect { latestCallback(it) }
    }
    DisposableEffect(Unit) {
        onDispose { latestCallback(emptyList()) }
    }
}

private const val ENGAGEMENT_PREFETCH_COUNT = 3
private const val ENGAGEMENT_SETTLE_MILLIS = 300L
