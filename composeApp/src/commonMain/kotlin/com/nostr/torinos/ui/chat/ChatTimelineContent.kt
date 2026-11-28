package com.nostr.torinos.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupMessage
import com.nostr.torinos.chat.chatPostTime
import com.nostr.torinos.chat.dmDisplayName
import com.nostr.torinos.chat.splitTodayEntries
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.AppTab
import com.nostr.torinos.ui.components.BotBadge
import kotlinx.coroutines.flow.flowOf

/** 「チャンネル / タイムライン」の並び。タブと横スワイプのページで同じ順にする。 */
internal val chatViewPages = listOf(ChatListView.Groups, ChatListView.Timeline)

/** 「チャンネル / タイムライン」の切り替え。アプリ内の他のタブと同じ下線タブ（[AppTab]）。 */
@Composable
internal fun ChatViewToggle(view: ChatListView, onSelect: (ChatListView) -> Unit) {
    val options = chatViewPages.map { it to if (it == ChatListView.Groups) "チャンネル" else "タイムライン" }
    PrimaryTabRow(
        selectedTabIndex = options.indexOfFirst { it.first == view }.coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        options.forEach { (option, label) ->
            AppTab(
                selected = option == view,
                onClick = { onSelect(option) },
                label = label,
                selectedContentColor = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** 参加中のグループの会話を、最後の動きの新しい順に（docs/chat-joined-timeline-design.md 1章）。 */
@Composable
internal fun ChatTimelineContent(
    state: ChatTimelineState,
    /** 読み込み中（タイムラインか、その前の参加中の一覧）。空でも「投稿がありません」は出さず、前回の失敗も出さない。 */
    loading: Boolean,
    ownPubkey: String?,
    onOpen: (ChatTimelineRow) -> Unit,
    onLoadOlder: () -> Unit,
    /** null なら「bot を隠す」を出さない（Buzz のコミュニティだけで出す）。 */
    onHideBotsChange: ((Boolean) -> Unit)?,
    actions: TimelineBodyActions,
) {
    val listState = rememberLazyListState()
    // 「bot を隠す」を切り替えると、上に行や見出し（「今日」）が増減する。LazyColumn は見えていた行の key で
    // 位置を保つので、そのままだと先頭からずれる。切り替えは一番上の見出しにあるので、切り替えたら先頭へ戻す。
    // SideEffect は変更の反映後・同じフレームの配置の前に動くので、requestScrollToItem が新しい行の配置に効く
    // （ずれた位置が1フレームも出ない）。先頭なので行の数での丸めも起きない。
    val seenHideBots = remember { booleanArrayOf(state.hideBots) }
    SideEffect {
        if (seenHideBots[0] != state.hideBots) {
            seenHideBots[0] = state.hideBots
            listState.requestScrollToItem(0)
        }
    }
    val people = remember(state.rows) {
        state.rows.flatMap { row ->
            val posts = listOfNotNull(row.entry.conversation.root, row.entry.conversation.latestReply)
            // Buzz の本文の `@表示名` は、メンションされた人の名前と照らし合わせてリンクにする。
            val mentioned = if (row.group.flavor == com.nostr.torinos.chat.GroupFlavor.Buzz) {
                posts.flatMap { com.nostr.torinos.chat.buzzProfileTargets(it.event) }
            } else {
                emptyList()
            }
            posts.map { it.author } + row.group.metadata?.participants.orEmpty() + mentioned
        }.toSet()
    }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(emptyMap())
    // 読めなかったリレーは、読み直しが終わってからも答えなかったときだけ出す（読み直し中は前回の結果なので出さない）。
    val failedRelays = if (loading) emptyList() else state.failedRelays
    when {
        state.rows.isEmpty() && (!state.loaded || loading) -> ChatMessageSkeleton(alignBottom = false)
        state.rows.isEmpty() -> Column(Modifier.fillMaxSize()) {
            FailedRelaysNotice(failedRelays)
            onHideBotsChange?.let { BotFilterRow(state.hideBots, state.hiddenBotPosts, it) }
            Text(
                if (state.hideBots && onHideBotsChange != null) "bot 以外の投稿がありません" else "参加中のチャンネルに投稿がありません",
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (failedRelays.isNotEmpty()) item(key = "tl-failed") { FailedRelaysNotice(failedRelays) }
                val sections = listOf("今日" to today, "昨日以前" to earlier).filter { it.second.isNotEmpty() }
                sections.forEachIndexed { index, (label, entries) ->
                    item(key = "tl-h-$label") {
                        // 「bot を隠す」は一番上の見出しの右端に置く（専用の行を取らない）。
                        TimelineSectionHeader(label) {
                            if (index == 0) onHideBotsChange?.let { BotFilterToggle(state.hideBots, state.hiddenBotPosts, it) }
                        }
                    }
                    itemsIndexed(entries, key = { _, it -> "tl-" + it.key }) { i, entry ->
                        val row = byKey.getValue(entry.key)
                        // 同じチャンネルの会話が続くあいだは見出しを1回にし、区切り線も本文の列からにする。
                        val continues = entries.getOrNull(i - 1)?.let { byKey[it.key]?.group?.ref } == row.group.ref
                        if (i > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = if (continues) Modifier.padding(start = TimelinePostTextStart) else Modifier,
                            )
                        }
                        CompositionLocalProvider(LocalChatBots provides state.bots) {
                            TimelineRowItem(row, ownPubkey, profiles, actions, showHeader = !continues, onClick = { onOpen(row) })
                        }
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

/**
 * チャンネルのタブの絞り込み（Google Chat のホームのチップ。docs/chat-joined-timeline-design.md 2.2）。
 * どちらも選んでいなければ両方を混ぜて出す。選べるのはどちらか一方で、選んでいるものを押すと両方に戻る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListFilterChips(filter: ChatListFilter, onChange: (ChatListFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            Triple(ChatListFilter.Channels, "チャンネル", Icons.Filled.Tag),
            Triple(ChatListFilter.Threads, "スレッド", Icons.Outlined.ChatBubbleOutline),
        ).forEach { (option, label, icon) ->
            val selected = filter == option
            FilterChip(
                selected = selected,
                onClick = { onChange(if (selected) ChatListFilter.Both else option) },
                label = { Text(label) },
                leadingIcon = {
                    Icon(if (selected) Icons.Filled.Check else icon, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }
    }
}

/** 投稿の無いときの「bot を隠す」（見出しが無いので、右端に寄せた行に置く）。 */
@Composable
private fun BotFilterRow(hideBots: Boolean, hiddenCount: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        BotFilterToggle(hideBots, hiddenCount, onChange)
    }
}

/**
 * 「bot を隠す」の切り替え（docs/chat-joined-timeline-design.md 2.1）。塗らずに文字だけで小さく出す。
 * オンのときは色を付け、隠している数を出す（投稿が少ないと感じたときに、ここが原因だと分かるように）。
 */
@Composable
private fun BotFilterToggle(hideBots: Boolean, hiddenCount: Int, onChange: (Boolean) -> Unit) {
    val color = if (hideBots) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val label = when {
        !hideBots -> "bot を表示中"
        hiddenCount > 0 -> "bot ${hiddenCount}件を非表示"
        else -> "bot を非表示"
    }
    TextButton(
        onClick = { onChange(!hideBots) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        if (hideBots) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun FailedRelaysNotice(relays: List<String>) {
    if (relays.isEmpty()) return
    Text(
        "読めなかったリレーがあります: " + relays.joinToString("、"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TimelineSectionHeader(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** 会話の行の左右の余白（12dp）＋アイコン（40dp）＋間（8dp）。続きの会話の区切り線をここから引く。 */
private val TimelinePostTextStart = 60.dp

/** 画像の読み込みとリンク（行の中の本文から使う）。 */
internal class TimelineBodyActions(
    val imageAuth: suspend (String) -> ImageAuth,
    val imagesUnlocked: Boolean,
    val onUnlockImages: () -> Unit,
    val onBuzzLink: (link: String, relayUrl: String) -> Unit,
    /** 本文のメンションを押した（その人の pubkey）。 */
    val onUserClick: ((String) -> Unit)? = null,
    /** 本文の `buzz://channel/…` に出すチャンネル名（リレー, チャンネル ID → 名前）。 */
    val channelName: (relayUrl: String, channelId: String) -> String? = { _, _ -> null },
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
    /** false なら、直前の会話と同じチャンネルなのでチャンネル名の見出しを省く。 */
    showHeader: Boolean = true,
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
        if (showHeader) Row(verticalAlignment = Alignment.CenterVertically) {
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
            // 畳み方はチャンネルの画面のスレッドの表示と同じ（元の投稿は短め、最新の返信はさらに短く）。
            TimelinePost(root, group.flavor, group.ref.relayUrl, profiles, actions, avatarSize = 40, collapse = BodyCollapse.Channel)
        } else {
            Text(
                "元の投稿を読み込めません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
        conversation.latestReply?.let { reply ->
            // チャットの画面のスレッドの表示と同じ。行全体がスレッドを開く。
            ThreadReplySummary(count = conversation.replyCount, onOpen = null, modifier = Modifier.padding(start = 48.dp)) {
                TimelinePost(reply, group.flavor, group.ref.relayUrl, profiles, actions, avatarSize = 28, collapse = BodyCollapse.ReplyPreview)
            }
        }
    }
}

/** 投稿1件。チャットの画面の発言と同じく、アイコン・名前・時刻・本文（Markdown・画像）を出す。 */
@Composable
internal fun TimelinePost(
    message: GroupMessage,
    flavor: com.nostr.torinos.chat.GroupFlavor,
    relayUrl: String,
    profiles: Map<String, NostrProfile>,
    actions: TimelineBodyActions,
    avatarSize: Int,
    collapse: BodyCollapse = BodyCollapse.Feed,
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
                if (isChatBot(message, profile)) BotBadge(Modifier.padding(start = 4.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    chatPostTime(message.event.createdAt, now),
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
                    collapsible = true,
                    collapse = collapse,
                    mentionNames = rememberMentionNames(message, profiles),
                    onMentionClick = actions.onUserClick,
                    profileName = { profiles[it]?.bestName },
                    channelName = { actions.channelName(relayUrl, it) },
                )
                else -> StandardMessageBody(message, onUserClick = {})
            }
            if (message.edited) {
                Text("（編集済み）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 元の投稿の下に出すスレッドの要約。最新の返信のプレビュー（[preview]）と返信の件数。
 * 件数はプレビューと同じ列に下げ、プレビューで見えている分を除いて「他 n件」と出す。
 * プレビューは押せる行にする。返信がプレビューの1件だけなら件数は出さず、▸ を付ける。
 * [onOpen] が null なら（行全体がスレッドを開くとき）ここでは押せるようにしない。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ThreadReplySummary(
    count: Int,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    preview: (@Composable () -> Unit)?,
) {
    val label = com.nostr.torinos.chat.threadReplyCountLabel(count, previewShown = preview != null)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val openModifier = if (onOpen != null) {
        Modifier.combinedClickable(onClickLabel = "スレッドを開く", onClick = onOpen, onLongClick = onLongClick)
    } else Modifier
    Column(modifier) {
        if (preview != null) {
            val alone = label == null
            Row(modifier = openModifier) {
                Text("╰", style = MaterialTheme.typography.bodyMedium, color = muted)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) { preview() }
                if (alone) {
                    Text(
                        "▸",
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
                    )
                }
            }
        }
        if (label != null) {
            Row(modifier = Modifier.padding(top = 4.dp)) {
                if (preview != null) {
                    // プレビューの「╰」と同じ幅を空けて、返信のアイコンの列にそろえる。
                    Text("╰", style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.Transparent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = openModifier,
                )
            }
        }
    }
}
