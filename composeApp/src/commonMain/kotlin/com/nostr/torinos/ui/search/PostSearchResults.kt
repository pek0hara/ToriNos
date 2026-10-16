package com.nostr.torinos.ui.search

import com.nostr.torinos.ui.components.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 投稿検索の結果欄に出すもの。ページ状態と結果件数から決める。 */
internal sealed interface PostResultsBody {
    data object Loading : PostResultsBody
    data object NotRequested : PostResultsBody
    data object Empty : PostResultsBody
    data class Failed(val message: String) : PostResultsBody
    data class Results(val footer: PostResultsFooter?) : PostResultsBody
}

internal sealed interface PostResultsFooter {
    data object Loading : PostResultsFooter
    data object LoadMore : PostResultsFooter
    data class Failed(val message: String) : PostResultsFooter
    data class Notice(val message: String) : PostResultsFooter
}

internal fun postResultsBody(page: PostPageState, hasResults: Boolean): PostResultsBody = when (page) {
    PostPageState.Idle -> PostResultsBody.NotRequested
    is PostPageState.Loading -> if (page.request.kind == PageKind.Initial) {
        PostResultsBody.Loading
    } else {
        PostResultsBody.Results(PostResultsFooter.Loading)
    }
    is PostPageState.Interrupted -> if (page.request.kind == PageKind.Initial) {
        PostResultsBody.Loading
    } else {
        PostResultsBody.Results(PostResultsFooter.Loading)
    }
    is PostPageState.Incomplete -> if (hasResults) {
        PostResultsBody.Results(PostResultsFooter.Failed(page.failure.message(page.request.kind)))
    } else {
        PostResultsBody.Failed(page.failure.message(page.request.kind))
    }
    is PostPageState.Complete -> when {
        !hasResults -> PostResultsBody.Empty
        else -> PostResultsBody.Results(
            when (page.end) {
                is PageEnd.More -> PostResultsFooter.LoadMore
                PageEnd.Exhausted -> null
                PageEnd.ResultLimit -> PostResultsFooter.Notice("表示できる件数の上限に達しました")
                PageEnd.BoundaryLimit -> PostResultsFooter.Notice("同じ時刻の投稿が多く、これ以上さかのぼれません")
            },
        )
    }
}

internal fun PageFailure.message(kind: PageKind): String {
    val subject = if (kind == PageKind.Initial) "検索" else "続きの読み込み"
    return when (this) {
        PageFailure.Timeout -> "${subject}がタイムアウトしました"
        is PageFailure.Closed -> "検索リレーが${subject}を打ち切りました" + reason.toReasonSuffix()
        is PageFailure.Unavailable -> "検索リレーに接続できませんでした"
        PageFailure.Overflow -> "検索リレーの応答が多すぎるため、${subject}を中断しました"
        is PageFailure.Error -> "${subject}に失敗しました"
    }
}

private fun String.toReasonSuffix(): String = trim().takeIf { it.isNotEmpty() }?.let { "（$it）" }.orEmpty()

@Composable
internal fun PostSearchResults(
    query: String,
    state: PostSearchUiState,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onUserClick: (pubkey: String) -> Unit,
    onOpenThread: (eventId: String) -> Unit,
    onOpenReplies: (eventId: String) -> Unit,
    onOpenLikes: (eventId: String) -> Unit,
    onOpenReposts: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val body = postResultsBody(state.page, state.events.isNotEmpty())) {
            PostResultsBody.Loading -> SearchLoadingIndicator(Modifier.align(Alignment.Center))
            PostResultsBody.NotRequested -> SearchMessage(
                text = "検索ボタンを押して再検索してください",
                modifier = Modifier.align(Alignment.Center),
            )
            PostResultsBody.Empty -> SearchMessage(
                text = "「$query」の結果はありませんでした",
                modifier = Modifier.align(Alignment.Center),
            )
            is PostResultsBody.Failed -> SearchFailure(
                message = body.message,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            is PostResultsBody.Results -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.events, key = { it.id }) { event ->
                    val engagement = state.engagements[event.id] ?: EMPTY_ENGAGEMENT
                    NoteCardHost(
                        model = NoteCardModel(
                            event = event,
                            profiles = state.profiles,
                            author = NoteAuthorPresentation(
                                profile = state.profiles[event.pubkey],
                            ),
                            engagement = NoteEngagementPresentation(
                                replyCount = engagement.replyCount,
                                reactionCount = engagement.reactionCount,
                                likeReactionCount = engagement.likeReactionCount,
                                customReactions = engagement.customReactions,
                                unicodeReactions = engagement.unicodeReactions,
                                reactionEvents = engagement.reactionEvents,
                                repostCount = engagement.repostCount,
                                repostPubkeys = engagement.repostPubkeys,
                            ),
                            references = NoteReferencePresentation(
                            ),
                            policy = NoteCardPolicy(
                            ),
                        ),
                        actions = NoteCardActions(
                            onUserClick = onUserClick,
                            onNoteClick = onOpenThread,
                            onOpenReplies = { onOpenReplies(event.id) },
                            onOpenLikes = { onOpenLikes(event.id) },
                            onOpenReposts = { onOpenReposts(event.id) },
                        ),
                    )
                    HorizontalDivider()
                }
                body.footer?.let { footer ->
                    item(key = "footer", contentType = "footer") {
                        PostResultsFooterItem(footer = footer, onLoadMore = onLoadMore, onRetry = onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostResultsFooterItem(
    footer: PostResultsFooter,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (footer) {
            PostResultsFooter.Loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
            PostResultsFooter.LoadMore -> FilledTonalButton(onClick = onLoadMore) {
                Text("さらに読み込む")
            }
            is PostResultsFooter.Failed -> SearchFailure(message = footer.message, onRetry = onRetry)
            is PostResultsFooter.Notice -> Text(
                text = footer.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun SearchLoadingIndicator(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = "検索中…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SearchMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun SearchFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onRetry) {
            Text("再試行")
        }
    }
}

private val EMPTY_ENGAGEMENT = SearchEngagement()
