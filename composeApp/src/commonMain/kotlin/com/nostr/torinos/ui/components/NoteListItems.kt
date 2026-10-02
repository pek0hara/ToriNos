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
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.ui.feed.FeedViewModel

/**
 * Actions for timeline items. Items hold the [State] (one stable instance) and read `.value` only
 * when the user acts, so a new UiState or recreated caller lambdas never change NoteCard's lambdas.
 * Do not read `.value` during composition of an item.
 */
internal class NoteListActions(
    val onUserClick: (String) -> Unit,
    val onLike: (eventId: String, authorPubkey: String) -> Unit,
    val onUnlike: (eventId: String) -> Unit,
    val onEmojiReact: (eventId: String, authorPubkey: String, option: ReactionOption) -> Unit,
    val onEmojiUnreact: (eventId: String, option: ReactionOption) -> Unit,
    val onDelete: (eventId: String) -> Unit,
    val onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    val onOpenReplies: ((eventId: String) -> Unit)? = null,
    val onOpenLikes: ((eventId: String) -> Unit)? = null,
    val onOpenReposts: ((eventId: String) -> Unit)? = null,
    val onRefreshReactions: ((eventId: String) -> Unit)? = null,
    val onRepost: ((NostrEvent) -> Unit)? = null,
    val onUnrepost: ((eventId: String) -> Unit)? = null,
    val onReport: ((event: NostrEvent, reason: String, detail: String) -> Unit)? = null,
    val onHashtagClick: ((tag: String) -> Unit)? = null,
    val onMuteUser: ((pubkey: String) -> Unit)? = null,
    val onUnmuteUser: ((pubkey: String) -> Unit)? = null,
)

/** The reply target shown above a reply, when it has been fetched. */
internal fun noteReplyParent(
    event: NostrEvent,
    quotedEvents: Map<String, NostrEvent>,
    profiles: Map<String, NostrProfile>,
): QuotedEvent? {
    val parentEvent = event.replyTargetId()?.let(quotedEvents::get) ?: return null
    return QuotedEvent(event = parentEvent, profile = profiles[parentEvent.pubkey])
}

/** Fetched quoted events in reference order, excluding the reply target shown separately. */
internal fun noteQuotedEvents(
    event: NostrEvent,
    quotedEvents: Map<String, NostrEvent>,
    profiles: Map<String, NostrProfile>,
): List<QuotedEvent> {
    val replyParentId = event.replyTargetId()
    return quotedEventIds(event)
        .filter { it != replyParentId }
        .mapNotNull { id -> quotedEvents[id]?.let { QuotedEvent(event = it, profile = profiles[it.pubkey]) } }
}

internal fun LazyListScope.noteListItems(
    state: FeedViewModel.UiState,
    ownPubkey: String?,
    actions: State<NoteListActions>,
    mutedPubkeys: Set<String> = emptySet(),
    emptyText: String = "ポストがありません",
    emptyContent: (@Composable () -> Unit)? = null,
    eventContentVisible: Boolean = true,
    eventEnterFadeMillis: Int = 0,
    deferWebViewLoad: Boolean = false,
) {
    // Which actions exist decides which buttons are shown; read once here, not inside items.
    val available = actions.value
    val canReply = ownPubkey != null && available.onReply != null
    val canOpenReplies = available.onOpenReplies != null
    val canOpenLikes = available.onOpenLikes != null
    val canOpenReposts = available.onOpenReposts != null
    val canRefreshReactions = available.onRefreshReactions != null
    val canRepost = ownPubkey != null && available.onRepost != null
    val canHashtag = available.onHashtagClick != null
    val canMute = available.onMuteUser != null
    val canUnmute = available.onUnmuteUser != null
    val canReport = ownPubkey != null && available.onReport != null
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
                    // Rebuilt on every UiState; keep the previous instance while the content is equal,
                    // otherwise NoteCard sees a new (identity-compared) argument and cannot skip.
                    val builtReplyParent = noteReplyParent(event, state.quotedEvents, state.profiles)
                    val replyParentForEvent = remember(builtReplyParent) { builtReplyParent }
                    val builtQuotedEvents = noteQuotedEvents(event, state.quotedEvents, state.profiles)
                    val quotedEventsForEvent = remember(builtQuotedEvents) { builtQuotedEvents }
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
                    // Toggle decisions use this note's own flags, not the whole UiState, so the
                    // lambdas below change only when this note's like/repost state changes.
                    val isLiked = state.isLiked(event.id)
                    val isReposted = state.isReposted(event.id)
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
                        isLiked = isLiked,
                        ownEmojiReactionEventIds = state.displayOwnEmojiReactionEventIds(event.id),
                        isReposted = isReposted,
                        onUserClick = { actions.value.onUserClick(it) },
                        onLike = if (ownPubkey != null) {
                            {
                                if (isLiked)
                                    actions.value.onUnlike(event.id)
                                else
                                    actions.value.onLike(event.id, event.pubkey)
                            }
                        } else null,
                        onEmojiReact = if (ownPubkey != null) {
                            { option -> actions.value.onEmojiReact(event.id, event.pubkey, option) }
                        } else null,
                        onEmojiUnreact = if (ownPubkey != null) {
                            { option -> actions.value.onEmojiUnreact(event.id, option) }
                        } else null,
                        onReply = if (canReply) {
                            { actions.value.onReply?.invoke(event, event.content.replyPreviewText()) }
                        } else null,
                        onOpenReplies = if (canOpenReplies) {
                            { actions.value.onOpenReplies?.invoke(event.id) }
                        } else null,
                        onOpenLikes = if (canOpenLikes) {
                            { actions.value.onOpenLikes?.invoke(event.id) }
                        } else null,
                        onOpenReposts = if (canOpenReposts) {
                            { actions.value.onOpenReposts?.invoke(event.id) }
                        } else null,
                        onRefreshReactions = if (canRefreshReactions) {
                            { actions.value.onRefreshReactions?.invoke(event.id) }
                        } else null,
                        onRepost = if (event.kind == 1 && canRepost) {
                            {
                                if (isReposted)
                                    actions.value.onUnrepost?.invoke(event.id)
                                else
                                    actions.value.onRepost?.invoke(event)
                            }
                        } else null,
                        onHashtagClick = if (canHashtag) {
                            { tag -> actions.value.onHashtagClick?.invoke(tag) }
                        } else null,
                        onNoteClick = if (canOpenReplies) {
                            { eventId -> actions.value.onOpenReplies?.invoke(eventId) }
                        } else null,
                        replyParent = replyParentForEvent,
                        quotedEvents = quotedEventsForEvent,
                        ownPubkey = ownPubkey,
                        onDelete = { actions.value.onDelete(event.id) },
                        isMuted = mutedPubkeys.contains(event.pubkey),
                        onMute = if (canMute) {
                            { actions.value.onMuteUser?.invoke(event.pubkey) }
                        } else null,
                        onUnmute = if (canUnmute) {
                            { actions.value.onUnmuteUser?.invoke(event.pubkey) }
                        } else null,
                        onReport = if (canReport) {
                            { reason, detail -> actions.value.onReport?.invoke(event, reason, detail) }
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
