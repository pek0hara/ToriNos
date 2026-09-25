package com.nostr.torinos.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.ArticleAuthorItem
import kotlinx.coroutines.flow.filter

@Composable
internal fun ArticleListContent(
    state: ArticleListState,
    listState: LazyListState,
    selectedTab: ArticleHubTab,
    onArticleClick: (pubkey: String, identifier: String) -> Unit,
    onAuthorClick: (pubkey: String) -> Unit,
    onLoadMore: () -> Unit,
    ownPubkey: String? = null,
    followedPubkeys: Set<String> = emptySet(),
    isMyAuthorsExpanded: Boolean = true,
    isFollowingAuthorsExpanded: Boolean = true,
    isGlobalAuthorsExpanded: Boolean = true,
    onToggleMyAuthors: () -> Unit = {},
    onToggleFollowingAuthors: () -> Unit = {},
    onToggleGlobalAuthors: () -> Unit = {},
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
        selectedTab == ArticleHubTab.Articles && state.articles.isEmpty() -> EmptyArticleList(modifier, emptyText)
        selectedTab == ArticleHubTab.Users && state.authors.isEmpty() && !state.canLoadMore -> {
            EmptyArticleList(modifier, "記事を書いているユーザーが見つかりません")
        }
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = contentPadding,
        ) {
            when (selectedTab) {
                ArticleHubTab.Articles -> {
                    items(
                        items = state.articles,
                        key = { it.address },
                        contentType = { "article" },
                    ) { article ->
                        ArticleCard(
                            article = article,
                            onClick = { onArticleClick(article.event.pubkey, article.meta.identifier) },
                        )
                        HorizontalDivider()
                    }
                }
                ArticleHubTab.Users -> {
                    val myAuthors = state.authors.filter { it.pubkey == ownPubkey }
                    val followingAuthors = state.authors.filter {
                        it.pubkey != ownPubkey && it.pubkey in followedPubkeys
                    }
                    val globalAuthors = state.authors.filter {
                        it.pubkey != ownPubkey && it.pubkey !in followedPubkeys
                    }
                    articleAuthorSection(
                        title = "自分",
                        authors = myAuthors,
                        expanded = isMyAuthorsExpanded,
                        onToggle = onToggleMyAuthors,
                        onAuthorClick = onAuthorClick,
                    )
                    articleAuthorSection(
                        title = "フォロー",
                        authors = followingAuthors,
                        expanded = isFollowingAuthorsExpanded,
                        onToggle = onToggleFollowingAuthors,
                        onAuthorClick = onAuthorClick,
                    )
                    articleAuthorSection(
                        title = "グローバル",
                        authors = globalAuthors,
                        expanded = isGlobalAuthorsExpanded,
                        onToggle = onToggleGlobalAuthors,
                        onAuthorClick = onAuthorClick,
                    )
                }
            }
            if (state.isLoadingMore) {
                item(contentType = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (selectedTab == ArticleHubTab.Users && state.canLoadMore) {
                item(key = "user-load-more", contentType = "load-more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(onClick = onLoadMore) {
                            Text("もっと読み込む")
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.articleAuthorSection(
    title: String,
    authors: List<ArticleAuthorItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAuthorClick: (pubkey: String) -> Unit,
) {
    item(key = "author-section-$title", contentType = "author-section-header") {
        ArticleAuthorSectionHeader(
            title = title,
            count = authors.size,
            expanded = expanded,
            onToggle = onToggle,
        )
        HorizontalDivider()
    }
    if (expanded) {
        if (authors.isEmpty()) {
            item(key = "author-section-$title-empty", contentType = "author-section-empty") {
                Text(
                    text = "該当するユーザーはいません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                HorizontalDivider()
            }
        } else {
            items(
                items = authors,
                key = { "$title-${it.pubkey}" },
                contentType = { "author" },
            ) { author ->
                ArticleAuthorRow(
                    author = author,
                    onClick = { onAuthorClick(author.pubkey) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ArticleAuthorSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.rotate(if (expanded) 0f else -90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
