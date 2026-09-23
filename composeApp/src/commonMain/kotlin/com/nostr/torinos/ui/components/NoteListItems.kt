package com.nostr.torinos.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.feed.FeedViewModel

fun LazyListScope.noteListItems(
    state: FeedViewModel.UiState,
    ownPubkey: String?,
    onUserClick: (String) -> Unit,
    onLike: (eventId: String, authorPubkey: String) -> Unit,
    onUnlike: (eventId: String) -> Unit,
    onEmojiReact: (eventId: String, authorPubkey: String, option: ReactionOption) -> Unit,
    onEmojiUnreact: (eventId: String, option: ReactionOption) -> Unit,
    onDelete: (eventId: String) -> Unit,
    onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    onOpenReplies: ((eventId: String) -> Unit)? = null,
    onOpenLikes: ((eventId: String) -> Unit)? = null,
    onOpenReposts: ((eventId: String) -> Unit)? = null,
    onRefreshReactions: ((eventId: String) -> Unit)? = null,
    onRepost: ((eventId: String, authorPubkey: String) -> Unit)? = null,
    onUnrepost: ((eventId: String) -> Unit)? = null,
    onReport: ((eventId: String, reason: String, detail: String) -> Unit)? = null,
    onHashtagClick: ((tag: String) -> Unit)? = null,
    onMuteUser: ((pubkey: String) -> Unit)? = null,
    onUnmuteUser: ((pubkey: String) -> Unit)? = null,
    mutedPubkeys: Set<String> = emptySet(),
    emptyText: String = "ポストがありません",
    emptyContent: (@Composable () -> Unit)? = null,
    eventContentVisible: Boolean = true,
    eventEnterFadeMillis: Int = 0,
    deferWebViewLoad: Boolean = false,
) {
    when {
        state.events.isEmpty() &&
            state.initialFeedState == FeedViewModel.InitialFeedState.Loading ->
            item(contentType = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        state.events.isEmpty() &&
            state.initialFeedState == FeedViewModel.InitialFeedState.Slow ->
            item(contentType = "slow") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = "リレーから取得中…",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        state.events.isEmpty() &&
            state.initialFeedState == FeedViewModel.InitialFeedState.Failed ->
            item(contentType = "failed") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyTimelineMessage("ポストを取得できませんでした")
                }
            }
        state.events.isEmpty() -> item(contentType = "empty") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                emptyContent?.invoke() ?: EmptyTimelineMessage(emptyText)
            }
        }
        else -> {
            items(
                items = state.events,
                key = { it.id },
                contentType = { "note" },
            ) { event ->
                val contentAlpha = animateFloatAsState(
                    targetValue = if (eventContentVisible) 1f else 0f,
                    animationSpec = tween(eventEnterFadeMillis),
                ).value
                Column(modifier = Modifier.alpha(contentAlpha)) {
                    val repostedByPubkey = state.repostedByPubkeys[event.id]
                    val repliesForEvent = state.replies[event.id].orEmpty()
                    val repostPubkeysForEvent = state.repostPubkeys[event.id].orEmpty()
                    val quoteRepostEventsForEvent = state.quoteRepostEvents[event.id].orEmpty()
                    val reactionEventsForEvent = state.reactionEvents[event.id].orEmpty()
                    val replyParentForEvent = run {
                        val parentId = event.replyTargetId() ?: return@run null
                        val parentEvent = state.quotedEvents[parentId] ?: return@run null
                        QuotedEvent(event = parentEvent, profile = state.profiles[parentEvent.pubkey])
                    }
                    val quotedEventsForEvent = run {
                        val replyParentId = event.replyTargetId()
                        quotedEventIds(event)
                            .filter { it != replyParentId }
                            .mapNotNull { quotedEventId ->
                                state.quotedEvents[quotedEventId]?.let { quotedEvent ->
                                    QuotedEvent(
                                        event = quotedEvent,
                                        profile = state.profiles[quotedEvent.pubkey],
                                    )
                                }
                            }
                    }
                    // このノートが実際に参照しうるpubkeyだけに絞り込み、無関係なプロフィール更新で
                    // 表示中の全アイテムが再コンポーズされるのを防ぐ。state.profilesはキーに含めない。
                    val relevantPubkeys = remember(
                        event.id,
                        event.content,
                        event.pubkey,
                        repostedByPubkey,
                        replyParentForEvent?.event?.id,
                        replyParentForEvent?.event?.content,
                        quotedEventsForEvent.map { it.event.id to it.event.content },
                        repostPubkeysForEvent,
                        reactionEventsForEvent.map { it.pubkey },
                        repliesForEvent.map { it.id to it.content },
                        quoteRepostEventsForEvent.map { it.id to it.content },
                    ) {
                        buildSet {
                            add(event.pubkey)
                            repostedByPubkey?.let(::add)
                            replyParentForEvent?.event?.let { parentEvent ->
                                add(parentEvent.pubkey)
                                extractNpubReferences(parentEvent.content).forEach { add(it.pubkey) }
                            }
                            quotedEventsForEvent.forEach { quoted ->
                                add(quoted.event.pubkey)
                                extractNpubReferences(quoted.event.content).forEach { add(it.pubkey) }
                            }
                            addAll(repostPubkeysForEvent)
                            reactionEventsForEvent.forEach { add(it.pubkey) }
                            repliesForEvent.forEach { reply ->
                                add(reply.pubkey)
                                extractNpubReferences(reply.content).forEach { add(it.pubkey) }
                            }
                            quoteRepostEventsForEvent.forEach { quoteRepost ->
                                add(quoteRepost.pubkey)
                                extractNpubReferences(quoteRepost.content).forEach { add(it.pubkey) }
                            }
                            extractNpubReferences(event.content).forEach { add(it.pubkey) }
                        }
                    }
                    val relevantProfileEntries = relevantPubkeys.mapNotNull { pubkey ->
                        state.profiles[pubkey]?.let { profile -> pubkey to profile }
                    }
                    // 無関係なプロフィールが更新されても、NoteCardへ渡すMapの同一性を維持する。
                    val relevantProfiles = remember(relevantProfileEntries) {
                        relevantProfileEntries.toMap()
                    }
                    NoteCard(
                        event = event,
                        precomputedContent = state.parsedContents[event.id],
                        deferWebViewLoad = deferWebViewLoad,
                        profile = state.profiles[event.pubkey],
                        repostedByPubkey = repostedByPubkey,
                        repostedByProfile = repostedByPubkey?.let { state.profiles[it] },
                        profiles = relevantProfiles,
                        replyCount = state.replyCounts[event.id] ?: 0,
                        replies = repliesForEvent,
                        repostCount = state.repostCounts[event.id] ?: 0,
                        repostPubkeys = repostPubkeysForEvent,
                        quoteRepostEvents = quoteRepostEventsForEvent,
                        reactionCount = state.reactionCounts[event.id] ?: 0,
                        likeReactionCount = state.likeReactionCounts[event.id] ?: 0,
                        customReactions = state.customReactions[event.id].orEmpty(),
                        unicodeReactions = state.unicodeReactions[event.id].orEmpty(),
                        reactionEvents = reactionEventsForEvent,
                        isLiked = state.isLiked(event.id),
                        ownEmojiReactionEventIds = state.displayOwnEmojiReactionEventIds(event.id),
                        isReposted = state.isReposted(event.id),
                        onUserClick = onUserClick,
                        onLike = if (ownPubkey != null) {
                            {
                                if (state.isLiked(event.id))
                                    onUnlike(event.id)
                                else
                                    onLike(event.id, event.pubkey)
                            }
                        } else null,
                        onEmojiReact = if (ownPubkey != null) {
                            { option -> onEmojiReact(event.id, event.pubkey, option) }
                        } else null,
                        onEmojiUnreact = if (ownPubkey != null) {
                            { option -> onEmojiUnreact(event.id, option) }
                        } else null,
                        onReply = if (ownPubkey != null && onReply != null) {
                            { onReply(event, event.content.replyPreviewText()) }
                        } else null,
                        onOpenReplies = if (onOpenReplies != null) {
                            { onOpenReplies(event.id) }
                        } else null,
                        onOpenLikes = if (onOpenLikes != null) {
                            { onOpenLikes(event.id) }
                        } else null,
                        onOpenReposts = if (onOpenReposts != null) {
                            { onOpenReposts(event.id) }
                        } else null,
                        onRefreshReactions = if (onRefreshReactions != null) {
                            { onRefreshReactions(event.id) }
                        } else null,
                        onRepost = if (event.kind == 1 && ownPubkey != null && onRepost != null) {
                            {
                                if (state.isReposted(event.id))
                                    onUnrepost?.invoke(event.id)
                                else
                                    onRepost(event.id, event.pubkey)
                            }
                        } else null,
                        onHashtagClick = onHashtagClick,
                        onNoteClick = if (onOpenReplies != null) onOpenReplies else null,
                        replyParent = replyParentForEvent,
                        quotedEvents = quotedEventsForEvent,
                        ownPubkey = ownPubkey,
                        onDelete = { onDelete(event.id) },
                        isMuted = mutedPubkeys.contains(event.pubkey),
                        onMute = if (onMuteUser != null) {
                            { onMuteUser(event.pubkey) }
                        } else null,
                        onUnmute = if (onUnmuteUser != null) {
                            { onUnmuteUser(event.pubkey) }
                        } else null,
                        onReport = if (ownPubkey != null && onReport != null) {
                            { reason, detail -> onReport(event.id, reason, detail) }
                        } else null,
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
            }
            if (state.isLoadingMore) {
                item(contentType = "loadingMore") {
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
internal fun EmptyTimelineMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun String.replyPreviewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(160)
