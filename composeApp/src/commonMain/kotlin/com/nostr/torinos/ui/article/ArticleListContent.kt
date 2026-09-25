package com.nostr.torinos.ui.article

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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    emptyText: String = "記事がありません",
) {
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
                ArticleCard(
                    article = article,
                    onClick = { onArticleClick(article.event.pubkey, article.meta.identifier) },
                    onAuthorClick = onAuthorClick?.let { { it(article.event.pubkey) } },
                    onTopicClick = onTopicClick,
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
