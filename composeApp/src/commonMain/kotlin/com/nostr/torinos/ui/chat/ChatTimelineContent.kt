package com.nostr.torinos.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupMessage
import com.nostr.torinos.chat.chatListTime
import com.nostr.torinos.chat.dmDisplayName
import com.nostr.torinos.chat.splitTodayEntries
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileRepository
import kotlinx.coroutines.flow.flowOf

/** 「グループ / タイムライン」の切り替え。 */
@Composable
internal fun ChatViewToggle(view: ChatListView, onSelect: (ChatListView) -> Unit) {
    val options = listOf(ChatListView.Groups to "グループ", ChatListView.Timeline to "タイムライン")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = option == view,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** 参加中のグループの会話を、最後の動きの新しい順に（docs/chat-joined-timeline-design.md 1章）。 */
@Composable
internal fun ChatTimelineContent(
    state: ChatTimelineState,
    ownPubkey: String?,
    onOpen: (ChatTimelineRow) -> Unit,
    onLoadOlder: () -> Unit,
    actions: TimelineBodyActions,
) {
    val people = remember(state.rows) {
        state.rows.flatMap { row ->
            listOfNotNull(row.entry.conversation.root?.author, row.entry.conversation.latestReply?.author) +
                row.group.metadata?.participants.orEmpty()
        }.toSet()
    }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(emptyMap())
    when {
        state.rows.isEmpty() && !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.rows.isEmpty() -> Column(Modifier.fillMaxSize()) {
            FailedRelaysNotice(state.failedRelays)
            Text(
                "参加中のグループに投稿がありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }
        else -> {
            val now = kotlin.time.Clock.System.now().epochSeconds
            val byKey = state.rows.associateBy { it.entry.key }
            val (today, earlier) = splitTodayEntries(state.rows.map { it.entry }, now)
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (state.failedRelays.isNotEmpty()) item(key = "tl-failed") { FailedRelaysNotice(state.failedRelays) }
                listOf("今日" to today, "昨日以前" to earlier).forEach { (label, entries) ->
                    if (entries.isEmpty()) return@forEach
                    item(key = "tl-h-$label") { TimelineSectionHeader(label) }
                    items(entries, key = { "tl-" + it.key }) { entry ->
                        if (entry !== entries.first()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val row = byKey.getValue(entry.key)
                        TimelineRowItem(row, ownPubkey, profiles, actions, onClick = { onOpen(row) })
                    }
                }
                item(key = "tl-more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.isLoadingOlder -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            state.canLoadOlder -> TextButton(onClick = onLoadOlder) { Text("さらに読み込む") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedRelaysNotice(relays: List<String>) {
    if (relays.isEmpty()) return
    Text(
        "読めなかったリレーがあります: " + relays.joinToString("、"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TimelineSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 画像の読み込みとリンク（行の中の本文から使う）。 */
internal class TimelineBodyActions(
    val imageAuth: suspend (String) -> ImageAuth,
    val imagesUnlocked: Boolean,
    val onUnlockImages: () -> Unit,
    val onBuzzLink: (link: String, relayUrl: String) -> Unit,
)

/**
 * 会話1つ。見出しにグループ名、その下に元の投稿を実際の投稿と同じ形（アイコン・名前・時刻・本文）で出し、
 * 最新の返信を「╰」を付けて字下げして出す。
 */
@Composable
private fun TimelineRowItem(
    row: ChatTimelineRow,
    ownPubkey: String?,
    profiles: Map<String, NostrProfile>,
    actions: TimelineBodyActions,
    onClick: () -> Unit,
) {
    val group = row.group
    val conversation = row.entry.conversation
    val participants = group.metadata?.participants.orEmpty()
    val direct = group.layout == GroupLayout.Direct
    val title = if (direct) dmDisplayName(participants, ownPubkey.orEmpty()) { profiles[it]?.bestName } else group.title
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupIcon(group.metadata?.picture, locked = !direct && group.metadata?.isPrivate == true, channel = !direct, forum = group.layout == GroupLayout.Forum, size = 20)
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (conversation.latestReply != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = "スレッド",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        val root = conversation.root
        if (root != null) {
            TimelinePost(root, group.flavor, group.ref.relayUrl, profiles, actions, avatarSize = 40)
        } else {
            Text(
                "元の投稿を読み込めません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
        conversation.latestReply?.let { reply ->
            Row(modifier = Modifier.padding(start = 48.dp)) {
                Text("╰", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                TimelinePost(reply, group.flavor, group.ref.relayUrl, profiles, actions, avatarSize = 28)
            }
            // チャットの画面の「返信 n件」と同じ表示。
            Text(
                "💬 返信 ${conversation.replyCount}件 ▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

/** 投稿1件。チャットの画面の発言と同じく、アイコン・名前・時刻・本文（Markdown・画像）を出す。 */
@Composable
private fun TimelinePost(
    message: GroupMessage,
    flavor: com.nostr.torinos.chat.GroupFlavor,
    relayUrl: String,
    profiles: Map<String, NostrProfile>,
    actions: TimelineBodyActions,
    avatarSize: Int,
) {
    val profile = profiles[message.author]
    val now = kotlin.time.Clock.System.now().epochSeconds
    Row {
        ChatAvatar(pubkey = message.author, name = profile?.bestName, pictureUrl = profile?.picture, size = avatarSize)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile?.bestName ?: message.author.take(8),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    chatListTime(message.event.createdAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val event = message.event
            when {
                event.kind == com.nostr.torinos.chat.BUZZ_FORUM_POST_KIND || event.kind == com.nostr.torinos.chat.GROUP_THREAD_KIND -> {
                    Text(com.nostr.torinos.chat.groupPostTitle(event), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                flavor == com.nostr.torinos.chat.GroupFlavor.Buzz -> MarkdownMessageBody(
                    text = message.body,
                    imageAuth = actions.imageAuth,
                    onUnlockImages = actions.onUnlockImages,
                    onBuzzLink = { actions.onBuzzLink(it, relayUrl) },
                    imagesUnlocked = actions.imagesUnlocked,
                    emojis = message.emojis,
                )
                else -> StandardMessageBody(message, onUserClick = {})
            }
            if (message.edited) {
                Text("（編集済み）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
