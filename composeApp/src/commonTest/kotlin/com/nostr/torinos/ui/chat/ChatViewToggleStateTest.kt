package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.GroupConversation
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.chat.GroupMessage
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.JoinedTimelineEntry
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatViewToggleStateTest {
    private val relay = ChatSelection.Relay("wss://buzz.example")
    private val ref = GroupRef("wss://buzz.example", "g1")
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)

    @Test
    fun toggleIsShownOnlyForASelectedRelay() {
        assertTrue(ChatTabUiState(selection = relay).showsViewToggle)
        assertFalse(ChatTabUiState(selection = ChatSelection.All).showsViewToggle)
        assertFalse(ChatTabUiState(selection = ChatSelection.Direct).showsViewToggle)
        assertFalse(ChatTabUiState(selection = relay, hasSigner = false).showsViewToggle)
    }

    @Test
    fun savedTimelineViewFallsBackToGroupsOnAll() {
        assertFalse(ChatTabUiState(selection = ChatSelection.All, view = ChatListView.Timeline).showsTimeline)
        assertTrue(ChatTabUiState(selection = relay, view = ChatListView.Timeline).showsTimeline)
    }

    @Test
    fun channelsComeFirstAndAreTheDefault() {
        assertEquals(ChatListView.Groups, ChatTabUiState().view)
        assertEquals(ChatListView.Groups, chatViewPages.first())
    }

    @Test
    fun listFilterIsShownForARelayAndForAllWithChannels() {
        assertTrue(ChatTabUiState(selection = relay).showsListFilter)
        assertTrue(ChatTabUiState(selection = ChatSelection.All, groupsExpected = true).showsListFilter)
        // 「すべて」でチャンネルが無いときは DM の一覧になる。
        assertFalse(ChatTabUiState(selection = ChatSelection.All, hasDm = true).showsListFilter)
        assertFalse(ChatTabUiState(selection = ChatSelection.Direct).showsListFilter)
        assertFalse(ChatTabUiState(selection = relay, hasSigner = false).showsListFilter)
    }

    @Test
    fun bothChannelsAndThreadsAreShownByDefault() {
        val state = ChatTabUiState(selection = relay)
        assertEquals(ChatListFilter.Both, state.listFilter)
        assertTrue(state.showsChannelRows)
        assertTrue(state.showsThreadRows)
        assertTrue(state.needsTimeline)
    }

    @Test
    fun theFilterShowsOnlyOneKind() {
        val channels = ChatTabUiState(selection = relay, listFilter = ChatListFilter.Channels)
        assertTrue(channels.showsChannelRows)
        assertFalse(channels.showsThreadRows)
        assertFalse(channels.needsTimeline)
        val threads = ChatTabUiState(selection = relay, listFilter = ChatListFilter.Threads)
        assertFalse(threads.showsChannelRows)
        assertTrue(threads.showsThreadRows)
    }

    @Test
    fun theChannelPageMixesThreadsEvenWhileTheTimelineIsSelected() {
        // スワイプでチャンネルのページへ移る間は、表示がまだタイムライン。そのときもページはスレッドを混ぜて描く
        // （止まってから差し込まれて、チャンネル→スレッドの順に出て見えないように）。
        val swiping = ChatTabUiState(selection = relay, view = ChatListView.Timeline)
        assertTrue(swiping.channelPageShowsThreads)
        assertFalse(ChatTabUiState(selection = relay, view = ChatListView.Timeline, listFilter = ChatListFilter.Channels).channelPageShowsThreads)
        assertFalse(ChatTabUiState(selection = ChatSelection.Direct).channelPageShowsThreads)
    }

    @Test
    fun threadsAreNotMixedIntoTheTimelineTabOrTheDmList() {
        assertFalse(ChatTabUiState(selection = relay, view = ChatListView.Timeline).showsThreadRows)
        assertTrue(ChatTabUiState(selection = relay, view = ChatListView.Timeline).needsTimeline)
        assertFalse(ChatTabUiState(selection = ChatSelection.Direct).showsThreadRows)
        // 絞り込みが無いところでは、「スレッド」を保存していてもチャンネルを出す。
        assertTrue(ChatTabUiState(selection = ChatSelection.Direct, listFilter = ChatListFilter.Threads).showsChannelRows)
    }

    private fun message(n: Int, author: String, body: String) = GroupMessage(
        event = NostrEvent(n.toString().padStart(64, '0'), author, n.toLong(), 9, listOf(listOf("h", "g1")), body, "sig"),
        author = author,
        body = body,
        replyToId = null,
        replyTo = null,
        reactions = emptyList(),
    )

    private fun timelineRow(root: GroupMessage?, reply: GroupMessage?, rootId: String = root?.event?.id ?: "r"): ChatTimelineRow {
        val group = ChatGroupRow(ref, metadata = null, joined = true, pending = false, lastMessage = null, flavor = GroupFlavor.Buzz, unread = true, unreadCount = 3)
        val activity = maxOf(root?.event?.createdAt ?: 0, reply?.event?.createdAt ?: 0)
        val conversation = GroupConversation(rootId, root, reply, if (reply != null) 1 else 0, activity)
        return ChatTimelineRow(JoinedTimelineEntry(ref, conversation), group)
    }

    @Test
    fun aThreadCardShowsTheRootAndTheLatestReply() {
        val card = timelineRow(message(1, alice, "元の投稿"), message(2, bob, "返信")).threadCard()
        assertEquals("返信", card.preview)
        assertEquals(bob, card.lastAuthor)
        assertEquals("元の投稿", card.threadRootPreview)
        assertEquals(alice, card.threadRootAuthor)
        assertEquals(2L, card.lastActivity)
        // 未読はチャンネルのものなので、スレッドの行には出さない。
        assertFalse(card.unread)
        assertNull(card.unreadCount)
    }

    @Test
    fun aThreadCardWithoutRepliesShowsTheRootOnly() {
        val card = timelineRow(message(1, alice, "フォーラムの投稿"), null).threadCard()
        assertEquals("フォーラムの投稿", card.preview)
        assertNull(card.threadRootPreview)
    }

    @Test
    fun aThreadCardWithAnUnreadableRootShowsTheReplyOnly() {
        val card = timelineRow(null, message(2, bob, "返信")).threadCard()
        assertEquals("返信", card.preview)
        assertNull(card.threadRootPreview)
    }

    private fun channel(id: String, last: GroupMessage?, threadRoot: GroupMessage? = null) =
        ChatGroupRow(GroupRef("wss://buzz.example", id), metadata = null, joined = true, pending = false, lastMessage = last?.event, threadRoot = threadRoot?.event)

    @Test
    fun channelsAndThreadsAreMixedByLatestActivity() {
        val older = channel("c1", message(1, alice, "古い"))
        val newer = channel("c2", message(5, alice, "新しい"))
        val thread = timelineRow(message(2, alice, "元"), message(3, bob, "返信"))
        val items = mergeChannelsAndThreads(listOf(newer, older), listOf(thread))
        assertEquals(listOf("j-" + newer.ref.key, "t-" + thread.entry.key, "j-" + older.ref.key), items.map { it.key })
    }

    @Test
    fun aThreadThatAChannelAlreadyPreviewsIsNotRepeated() {
        val root = message(2, alice, "元")
        val reply = message(3, bob, "返信")
        val previewed = timelineRow(root, reply)
        val other = timelineRow(message(6, alice, "別の元"), message(7, bob, "別の返信"))
        // チャンネルの最新の発言がこのスレッドの返信（元の投稿も上に出している）。
        val channels = listOf(channel("g1", reply, threadRoot = root))
        assertEquals(listOf(other), threadsNotInChannelPreviews(listOf(previewed, other), channels))
        // 最新の発言がスレッドの元の投稿そのものでも重ねない。
        assertEquals(listOf(other), threadsNotInChannelPreviews(listOf(previewed, other), listOf(channel("g1", root))))
    }

    @Test
    fun timelineIsNotReloadedRightAfterComingBack() {
        // スレッドを開いて 10 秒で戻った。
        assertEquals(TimelineReload.None, timelineReload(sameGroups = true, loading = false, force = true, hasLoader = true, millisSinceLoaded = 10_000))
        // 少したってからタブに入った・バックグラウンドから戻った: 差分だけ読む。
        assertEquals(TimelineReload.Update, timelineReload(sameGroups = true, loading = false, force = true, hasLoader = true, millisSinceLoaded = 5 * 60_000))
        // 一覧の読み直しが終わっただけ（グループは同じ）: 読まない。
        assertEquals(TimelineReload.None, timelineReload(sameGroups = true, loading = false, force = false, hasLoader = true, millisSinceLoaded = 5 * 60_000))
        // 同じグループを読んでいる途中: 取り消さずに待つ。
        assertEquals(TimelineReload.None, timelineReload(sameGroups = true, loading = true, force = true, hasLoader = true, millisSinceLoaded = null))
        // グループが変わった: 前の結果があれば差分（変わったリレーだけいちから）、無ければいちから。
        assertEquals(TimelineReload.Update, timelineReload(sameGroups = false, loading = false, force = false, hasLoader = true, millisSinceLoaded = 10_000))
        assertEquals(TimelineReload.Full, timelineReload(sameGroups = false, loading = false, force = false, hasLoader = false, millisSinceLoaded = null))
    }
}
