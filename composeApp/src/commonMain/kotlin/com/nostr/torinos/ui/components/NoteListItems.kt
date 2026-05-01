package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.stripNostrEventUris
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.ui.feed.FeedViewModel

fun LazyListScope.noteListItems(
    state: FeedViewModel.UiState,
    ownPubkey: String?,
    onUserClick: (String) -> Unit,
    onLike: (eventId: String, authorPubkey: String) -> Unit,
    onUnlike: (eventId: String) -> Unit,
    onDelete: (eventId: String) -> Unit,
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: ((eventId: String) -> Unit)? = null,
    onOpenLikes: ((eventId: String) -> Unit)? = null,
    onOpenReposts: ((eventId: String) -> Unit)? = null,
    onRepost: ((eventId: String, authorPubkey: String) -> Unit)? = null,
    onUnrepost: ((eventId: String) -> Unit)? = null,
    mutedPubkeys: Set<String> = emptySet(),
    emptyText: String = "ポストがありません",
) {
    when {
        state.isInitialLoad && state.events.isEmpty() -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        state.events.isEmpty() -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            items(state.events, key = { it.id }) { event ->
                val repostedByPubkey = state.repostedByPubkeys[event.id]
                NoteCard(
                    event = event,
                    profile = state.profiles[event.pubkey],
                    repostedByPubkey = repostedByPubkey,
                    repostedByProfile = repostedByPubkey?.let { state.profiles[it] },
                    replyCount = state.replyCounts[event.id] ?: 0,
                    repostCount = state.repostCounts[event.id] ?: 0,
                    reactionCount = state.reactionCounts[event.id] ?: 0,
                    isLiked = state.likedReactions.containsKey(event.id),
                    isReposted = state.repostedEvents.containsKey(event.id),
                    onUserClick = onUserClick,
                    onLike = if (ownPubkey != null) {
                        {
                            if (state.likedReactions.containsKey(event.id))
                                onUnlike(event.id)
                            else
                                onLike(event.id, event.pubkey)
                        }
                    } else null,
                    onReply = if (ownPubkey != null && onReply != null) {
                        { onReply(event.id, event.pubkey, event.content.replyPreviewText()) }
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
                    onRepost = if (ownPubkey != null && onRepost != null) {
                        {
                            if (state.repostedEvents.containsKey(event.id))
                                onUnrepost?.invoke(event.id)
                            else
                                onRepost(event.id, event.pubkey)
                        }
                    } else null,
                    quotedEvents = quotedEventIds(event).mapNotNull { quotedEventId ->
                        state.quotedEvents[quotedEventId]?.let { quotedEvent ->
                            QuotedEvent(
                                event = quotedEvent,
                                profile = state.profiles[quotedEvent.pubkey],
                            )
                        }
                    },
                    ownPubkey = ownPubkey,
                    onDelete = { onDelete(event.id) },
                    isMuted = mutedPubkeys.contains(event.pubkey),
                    onMute = if (event.pubkey != ownPubkey) {
                        { MuteStore.mute(event.pubkey) }
                    } else null,
                    onUnmute = if (event.pubkey != ownPubkey) {
                        { MuteStore.unmute(event.pubkey) }
                    } else null,
                )
                HorizontalDivider()
            }
            if (state.canLoadMore) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

private fun String.replyPreviewText(): String =
    stripImageUrls(stripNostrEventUris(this))
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(160)
