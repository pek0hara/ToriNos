package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*

internal data class NoteAuthorPresentation(
    val profile: NostrProfile?,
    val repostedByPubkey: String? = null,
    val repostedByProfile: NostrProfile? = null,
    val ownPubkey: String? = null,
)

internal data class NoteEngagementPresentation(
    val replyCount: Int,
    val replies: List<NostrEvent> = emptyList(),
    val reactionCount: Int,
    val likeReactionCount: Int? = null,
    val customReactions: List<CustomReaction> = emptyList(),
    val unicodeReactions: List<UnicodeReaction> = emptyList(),
    val reactionEvents: List<NostrEvent> = emptyList(),
    val repostCount: Int = 0,
    val repostPubkeys: List<String> = emptyList(),
    val quoteRepostEvents: List<NostrEvent> = emptyList(),
    val isLiked: Boolean = false,
    val isReposted: Boolean = false,
    val ownEmojiReactionEventIds: Map<String, String> = emptyMap(),
)

internal data class NoteReferencePresentation(
    val quotedEvents: List<QuotedEvent> = emptyList(),
    val replyParent: QuotedEvent? = null,
)

internal data class NoteCardPolicy(
    val deferWebViewLoad: Boolean = false,
    val showEngagement: Boolean = true,
    val showReactions: Boolean = showEngagement,
    val showImagePreviews: Boolean = true,
    val showXPreviews: Boolean = true,
)

internal data class NoteCardModel(
    val event: NostrEvent,
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val isMuted: Boolean = false,
    val precomputedContent: ParsedNoteContent? = null,
    val author: NoteAuthorPresentation,
    val engagement: NoteEngagementPresentation,
    val references: NoteReferencePresentation,
    val policy: NoteCardPolicy,
)

internal data class NoteCardActions(
    val onUserClick: (pubkey: String) -> Unit = {},
    val onLike: (() -> Unit)? = null,
    val onEmojiReact: ((ReactionOption) -> Unit)? = null,
    val onEmojiUnreact: ((ReactionOption) -> Unit)? = null,
    val onReply: (() -> Unit)? = null,
    val onOpenReplies: (() -> Unit)? = null,
    val onOpenLikes: (() -> Unit)? = null,
    val onOpenReposts: (() -> Unit)? = null,
    val onRefreshReactions: (() -> Unit)? = null,
    val onRepost: (() -> Unit)? = null,
    val onHashtagClick: ((tag: String) -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onHide: (() -> Unit)? = null,
    val onMute: (() -> Unit)? = null,
    val onUnmute: (() -> Unit)? = null,
    val onReport: ((reason: String, detail: String) -> Unit)? = null,
    val onNoteClick: ((eventId: String) -> Unit)? = null,
    val onQuotedNoteClick: ((eventId: String) -> Unit)? = onNoteClick,
    val onReplyParentClick: ((eventId: String) -> Unit)? = onNoteClick,
)
