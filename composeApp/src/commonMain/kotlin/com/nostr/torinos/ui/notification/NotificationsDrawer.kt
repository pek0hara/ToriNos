package com.nostr.torinos.ui.notification

import com.nostr.torinos.ui.components.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.ui.components.QuotedEvent
import com.nostr.torinos.ui.components.ReactionSummaryRow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.badge.BadgeAwardNotice
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.profile.AvatarCircle

@Composable
fun NotificationsDrawer(
    ownPubkey: String?,
    isOpen: Boolean,
    scrollToTopRequest: Int = 0,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 360.dp),
    ) {
        if (ownPubkey == null) {
            EmptyNotifications(text = "通知を表示するには鍵を設定してください")
            return@ModalDrawerSheet
        }

        val muteStore = LocalAccountSession.current?.muteStore
        val viewModel: NotificationsViewModel = viewModel(
            key = "notifications-$ownPubkey",
            factory = viewModelFactory {
                initializer {
                    NotificationsViewModel(ownPubkey, muteStore)
                }
            },
        )
        val state by viewModel.state.collectAsState()
        val listState = rememberLazyListState()

        LaunchedEffect(isOpen, viewModel) {
            if (isOpen) {
                viewModel.startLiveSubscriptions()
            } else {
                viewModel.stopLiveSubscriptions()
            }
        }
        DisposableEffect(viewModel) {
            onDispose { viewModel.stopLiveSubscriptions() }
        }

        LaunchedEffect(scrollToTopRequest) {
            if (scrollToTopRequest > 0) {
                listState.scrollToItem(0)
            }
        }

        Column(modifier = Modifier.fillMaxHeight()) {
            Text(
                text = "通知",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            )
            HorizontalDivider()
            NotificationsList(
                state = state,
                ownPubkey = ownPubkey,
                listState = listState,
                onUserClick = onUserClick,
                onOpenTarget = onOpenTarget,
                onRetryTarget = viewModel::retryTarget,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun NotificationsList(
    state: NotificationsState,
    ownPubkey: String,
    listState: LazyListState,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    onRetryTarget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(state.items) { groupNotifications(state.items) }
    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        when {
            state.isInitialLoad && state.items.isEmpty() -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.items.isEmpty() -> item {
                EmptyNotifications(text = "通知はまだありません")
            }
            else -> {
                items(
                    entries,
                    key = { it.key },
                    contentType = { if (it is NotificationListEntry.DateHeader) "date" else (it as NotificationListEntry.Group).type },
                ) { entry ->
                    when (entry) {
                        is NotificationListEntry.DateHeader -> NotificationDateHeader(entry)
                        is NotificationListEntry.Group -> {
                            NotificationGroupRow(
                                group = entry,
                                state = state,
                                ownPubkey = ownPubkey,
                                onUserClick = onUserClick,
                                onOpenTarget = onOpenTarget,
                                onRetryTarget = onRetryTarget,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationDateHeader(header: NotificationListEntry.DateHeader) {
    Text(
        text = notificationDateLabel(header.date),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun NotificationGroupRow(
    group: NotificationListEntry.Group,
    state: NotificationsState,
    ownPubkey: String,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    onRetryTarget: (String) -> Unit,
) {
    when (group.type) {
        NotificationType.Reply -> ReplyNotification(group.latest, state, onUserClick, onOpenTarget, onRetryTarget)
        NotificationType.Like,
        NotificationType.Repost -> EngagementNotification(group, state, onUserClick, onOpenTarget, onRetryTarget)
        NotificationType.Follow -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            NotificationGroupHeader(group, state.profiles, onUserClick)
        }
        NotificationType.BadgeAward -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            NotificationGroupHeader(group, state.profiles, onUserClick)
            group.latest.event?.let { award ->
                Spacer(modifier = Modifier.height(6.dp))
                BadgeAwardNotice(award = award, recipient = ownPubkey, onUserClick = onUserClick)
            }
        }
    }
}

/** 返信は本文が主役なので、まとめずに返信そのものを NoteCard で出す。返信先は NoteCard の引用で示す。 */
@Composable
private fun ReplyNotification(
    item: NotificationItem,
    state: NotificationsState,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    onRetryTarget: (String) -> Unit,
) {
    val reply = item.event ?: return
    val targetState = item.targetEventId?.let { state.targetStates[it] }
    val parent = (targetState as? TargetLoadState.Resolved)?.event
    val openThread: (String) -> Unit = { onOpenTarget(NotificationTargetDestination.Thread(it)) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotificationIcon(icon = notificationIcon(NotificationType.Reply), tint = notificationAccent(NotificationType.Reply))
            Text(
                text = "返信",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (parent == null && item.targetReference != TargetReference.None) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                NotificationTargetPreview(
                    reference = item.targetReference,
                    state = targetState,
                    profile = null,
                    onRetry = onRetryTarget,
                    onOpenTarget = onOpenTarget,
                )
            }
        }
        NoteCardHost(
            model = NoteCardModel(
                event = reply,
                profiles = state.profiles,
                author = NoteAuthorPresentation(
                    profile = state.profiles[reply.pubkey],
                ),
                engagement = NoteEngagementPresentation(
                    replyCount = 0,
                    reactionCount = 0,
                ),
                references = NoteReferencePresentation(
                    replyParent = parent?.let { QuotedEvent(it, state.profiles[it.pubkey]) },
                ),
                policy = NoteCardPolicy(
                    showEngagement = false,
                ),
            ),
            actions = NoteCardActions(
                onUserClick = onUserClick,
                onNoteClick = { notificationTargetDestination(reply)?.let(onOpenTarget) },
                onQuotedNoteClick = openThread,
                onReplyParentClick = openThread,
            ),
        )
    }
}

/** 同じ投稿・同じ日のいいね(またはリポスト)をまとめ、対象の投稿を NoteCard で出す。 */
@Composable
private fun EngagementNotification(
    group: NotificationListEntry.Group,
    state: NotificationsState,
    onUserClick: (String) -> Unit,
    onOpenTarget: (NotificationTargetDestination) -> Unit,
    onRetryTarget: (String) -> Unit,
) {
    val targetState = group.targetEventId?.let { state.targetStates[it] }
    val target = (targetState as? TargetLoadState.Resolved)?.event
    val reactions = remember(group.type, group.items) {
        if (group.type == NotificationType.Like) {
            notificationReactions(group.items.mapNotNull { it.event })
        } else {
            NotificationReactions()
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        NotificationGroupHeader(group, state.profiles, onUserClick)
        Spacer(modifier = Modifier.height(8.dp))
        if (target != null && target.kind in NOTE_CARD_KINDS) {
            val openThread: (String) -> Unit = { onOpenTarget(NotificationTargetDestination.Thread(it)) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TargetCardShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, TargetCardShape),
            ) {
                NoteCardHost(
                    model = NoteCardModel(
                        event = target,
                        profiles = state.profiles,
                        author = NoteAuthorPresentation(
                            profile = state.profiles[target.pubkey],
                        ),
                        engagement = NoteEngagementPresentation(
                            replyCount = 0,
                            reactionCount = reactions.totalCount,
                            likeReactionCount = reactions.likeCount,
                            customReactions = reactions.customReactions,
                            unicodeReactions = reactions.unicodeReactions,
                        ),
                        references = NoteReferencePresentation(
                        ),
                        policy = NoteCardPolicy(
                            showEngagement = false,
                            showReactions = true,
                        ),
                    ),
                    actions = NoteCardActions(
                        onUserClick = onUserClick,
                        onNoteClick = { notificationTargetDestination(target)?.let(onOpenTarget) },
                        onQuotedNoteClick = openThread,
                        onReplyParentClick = openThread,
                    ),
                )
            }
        } else {
            NotificationTargetPreview(
                reference = group.latest.targetReference,
                state = targetState,
                profile = target?.let { state.profiles[it.pubkey] },
                onRetry = onRetryTarget,
                onOpenTarget = onOpenTarget,
            )
            if (reactions.totalCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                ReactionSummaryRow(
                    totalReactionCount = reactions.totalCount,
                    explicitLikeCount = reactions.likeCount,
                    isLiked = false,
                    customReactions = reactions.customReactions,
                    unicodeReactions = reactions.unicodeReactions,
                    ownEmojiReactionEventIds = emptyMap(),
                    onLike = null,
                    onEmojiReact = null,
                    onEmojiUnreact = null,
                    onOpenStandardEmojiPicker = {},
                )
            }
        }
    }
}

/** 種類アイコン、まとめた人のアバター列、「Aさん他N人が〜」、最新の時刻。 */
@Composable
private fun NotificationGroupHeader(
    group: NotificationListEntry.Group,
    profiles: Map<String, NostrProfile>,
    onUserClick: (String) -> Unit,
) {
    val actors = remember(group.items) { group.items.map { it.actorPubkey }.distinct() }
    val first = actors.first()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.padding(top = 5.dp)) {
            NotificationIcon(icon = notificationIcon(group.type), tint = notificationAccent(group.type))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actors.take(MAX_HEADER_AVATARS).forEach { pubkey ->
                    val profile = profiles[pubkey]
                    AvatarCircle(
                        pubkey = pubkey,
                        name = profile?.bestName,
                        pictureUrl = profile?.picture,
                        size = 28,
                        modifier = Modifier.clickable { onUserClick(pubkey) },
                    )
                }
                if (actors.size > MAX_HEADER_AVATARS) {
                    Text(
                        text = "+${actors.size - MAX_HEADER_AVATARS}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                group.latest.createdAt?.let {
                    Text(
                        text = formatTimestamp(it, todayTimeOnly = true),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileNameText(
                    profile = profiles[first],
                    fallback = shortPubkey(first),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { onUserClick(first) },
                )
                Text(
                    text = (if (actors.size > 1) " 他${actors.size - 1}人" else "") +
                        notificationTitleSuffix(group.type),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun notificationAccent(type: NotificationType): Color = when (type) {
    NotificationType.Reply -> MaterialTheme.colorScheme.primary
    NotificationType.Repost -> Color(0xFF2BAE66)
    NotificationType.Like -> Color(0xFFE17055)
    NotificationType.Follow -> MaterialTheme.colorScheme.tertiary
    NotificationType.BadgeAward -> Color(0xFFD4A017)
}

private fun notificationIcon(type: NotificationType): ImageVector = when (type) {
    NotificationType.Reply -> Icons.Default.MailOutline
    NotificationType.Repost -> Icons.Default.Repeat
    NotificationType.Like -> Icons.Default.Favorite
    NotificationType.Follow -> Icons.Default.PersonAdd
    NotificationType.BadgeAward -> Icons.Default.EmojiEvents
}

@Composable
private fun NotificationIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = tint,
    )
}

@Composable
private fun EmptyNotifications(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun notificationTitleSuffix(type: NotificationType): String =
    when (type) {
        NotificationType.Reply -> "から返信"
        NotificationType.Repost -> "がリポスト"
        NotificationType.Like -> "がいいね"
        NotificationType.Follow -> "がフォロー"
        NotificationType.BadgeAward -> "がバッジを授与"
    }

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "..." + pubkey.takeLast(8)

private fun notificationDateLabel(
    date: LocalDate,
    today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
): String = when (date) {
    today -> "今日"
    today.minus(1, DateTimeUnit.DAY) -> "昨日"
    else -> buildString {
        if (date.year != today.year) append("${date.year}年")
        append("${date.month.ordinal + 1}月${date.day}日")
    }
}

// 対象の投稿を NoteCard で出せる kind。記事などは従来の要約表示にする。
private val NOTE_CARD_KINDS = setOf(1, 42, COMMENT_EVENT_KIND)
private const val MAX_HEADER_AVATARS = 5
private val TargetCardShape = RoundedCornerShape(12.dp)
