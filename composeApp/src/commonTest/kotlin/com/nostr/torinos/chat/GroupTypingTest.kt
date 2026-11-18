package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupTypingTest {
    private val group = "channel-1"
    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)
    private val carol = "d".repeat(64)
    private val root = "1".repeat(64)
    private val otherRoot = "2".repeat(64)
    private val parent = "3".repeat(64)

    /** 基準の時刻（ミリ秒）。イベントの created_at は秒。 */
    private val t0 = 1_700_000_000_000L
    private val s0 = t0 / 1000

    private var seq = 0

    private fun typing(
        author: String,
        at: Long = s0,
        tags: List<List<String>> = listOf(listOf("h", group)),
        content: String = "",
    ) = NostrEvent((++seq).toString().padStart(64, 'f'), author, at, BUZZ_TYPING_KIND, tags, content, "sig")

    private fun message(author: String, at: Long = s0, threadRoot: String? = null, kind: Int = GROUP_CHAT_KIND) =
        NostrEvent(
            (++seq).toString().padStart(64, 'e'),
            author,
            at,
            kind,
            listOf(listOf("h", group)) + (threadRoot?.let { listOf(listOf("e", it, "", "reply")) } ?: emptyList()),
            "hi",
            "sig",
        )

    // ---- 組み立て ----

    @Test
    fun channelTypingHasOnlyGroupTagAndEmptyContent() {
        val draft = buildBuzzTyping(group, threadRootId = null)
        assertEquals(BUZZ_TYPING_KIND, draft.kind)
        assertEquals(20002, draft.kind)
        assertEquals(listOf(listOf("h", group)), draft.tags)
        assertEquals("", draft.content)
    }

    @Test
    fun threadTypingAddsReplyTagToRoot() {
        val draft = buildBuzzTyping(group, threadRootId = root)
        assertEquals(listOf(listOf("h", group), listOf("e", root, "", "reply")), draft.tags)
    }

    @Test
    fun typingIsPublishableToTheGroup() {
        val event = NostrEvent("f".repeat(64), me, s0, BUZZ_TYPING_KIND, buildBuzzTyping(group, null).tags, "", "sig")
        assertNull(groupPublishViolation(event, group, setOf("wss://r"), "wss://r"))
    }

    // ---- 解析 ----

    @Test
    fun scopeOfChannelTypingIsTopLevel() {
        assertEquals(TypingScope(null), buzzTypingScope(typing(alice), group))
    }

    @Test
    fun scopeOfThreadTypingIsReplyTarget() {
        val event = typing(alice, tags = listOf(listOf("h", group), listOf("e", root, "", "reply")))
        assertEquals(TypingScope(root), buzzTypingScope(event, group))
    }

    @Test
    fun scopeOfAgentNestedTypingIsRootTag() {
        val event = typing(alice, tags = listOf(listOf("h", group), listOf("e", root, "", "root"), listOf("e", parent, "", "reply")))
        assertEquals(TypingScope(root), buzzTypingScope(event, group))
    }

    @Test
    fun malformedTypingIsIgnored() {
        val h = listOf("h", group)
        val reply = listOf("e", root, "", "reply")
        val cases = listOf(
            typing(alice, tags = listOf(listOf("h", "other"))),
            typing(alice, tags = listOf(h, listOf("h", group))),
            typing(alice, tags = emptyList()),
            typing(alice, tags = listOf(h, listOf("e", root, "", "root"))),
            typing(alice, tags = listOf(h, reply, listOf("e", parent, "", "reply"))),
            typing(alice, tags = listOf(h, listOf("e", root, "", "root"), listOf("e", otherRoot, "", "root"), reply)),
            typing(alice, tags = listOf(h, listOf("e", root, "", "mention"))),
            typing(alice, tags = listOf(h, listOf("e", "xyz", "", "reply"))),
            typing(alice, tags = listOf(h, listOf("e", root, "", "root"), listOf("e", otherRoot, "", "root"), listOf("e", parent, "", "reply"))),
            typing(alice, content = "下書き"),
            message(alice),
        )
        cases.forEach { assertNull(buzzTypingScope(it, group), it.tags.toString()) }
    }

    // ---- 記録 ----

    @Test
    fun typingShowsAndExpiresAfterEightSeconds() {
        val tracker = TypingTracker(me, group)
        assertTrue(tracker.receiveTyping(typing(alice), t0))
        assertEquals(listOf(alice), tracker.visible(null, t0))
        assertEquals(listOf(alice), tracker.visible(null, t0 + 7_999))
        assertEquals(emptyList(), tracker.visible(null, t0 + 8_000))
    }

    @Test
    fun expiryCountsFromReceiptNotSenderClock() {
        // 相手の時計が5秒遅れていても、受け取ってから8秒は出す。
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice, at = s0 - 5), t0)
        assertEquals(listOf(alice), tracker.visible(null, t0 + 7_000))
    }

    @Test
    fun newerTypingExtendsVisibility() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice, at = s0), t0)
        tracker.receiveTyping(typing(alice, at = s0 + 3), t0 + 3_000)
        assertEquals(listOf(alice), tracker.visible(null, t0 + 10_000))
        assertEquals(emptyList(), tracker.visible(null, t0 + 11_000))
    }

    @Test
    fun ownTypingIsIgnored() {
        val tracker = TypingTracker(me, group)
        assertFalse(tracker.receiveTyping(typing(me), t0))
        assertEquals(emptyList(), tracker.visible(null, t0))
    }

    @Test
    fun typingFarFromNowIsIgnored() {
        val tracker = TypingTracker(me, group)
        assertFalse(tracker.receiveTyping(typing(alice, at = s0 - 31), t0))
        assertFalse(tracker.receiveTyping(typing(bob, at = s0 + 31), t0))
        assertEquals(emptyList(), tracker.visible(null, t0))
    }

    @Test
    fun messageEndsTypingAndQuietsForTwoSeconds() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice, at = s0), t0)
        assertTrue(tracker.receiveMessage(message(alice, at = s0 + 1), t0 + 1_000))
        assertEquals(emptyList(), tracker.visible(null, t0 + 1_000))
        // 送信直後に遅れて届いた入力中は出さない。
        tracker.receiveTyping(typing(alice, at = s0 + 2), t0 + 2_000)
        assertEquals(emptyList(), tracker.visible(null, t0 + 2_000))
        // 静かな時間の後は出す。
        tracker.receiveTyping(typing(alice, at = s0 + 4), t0 + 4_000)
        assertEquals(listOf(alice), tracker.visible(null, t0 + 4_000))
    }

    @Test
    fun typingOlderThanLastMessageIsIgnored() {
        val tracker = TypingTracker(me, group)
        tracker.receiveMessage(message(alice, at = s0 + 10), t0)
        tracker.receiveTyping(typing(alice, at = s0 + 5), t0 + 5_000)
        assertEquals(emptyList(), tracker.visible(null, t0 + 5_000))
    }

    @Test
    fun agentMessageAlsoEndsTyping() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice), t0)
        tracker.receiveMessage(message(alice, kind = BUZZ_AGENT_MESSAGE_KIND), t0 + 500)
        assertEquals(emptyList(), tracker.visible(null, t0 + 500))
    }

    @Test
    fun messageFromOtherPersonOrGroupDoesNotEndTyping() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice), t0)
        tracker.receiveMessage(message(bob), t0 + 500)
        val elsewhere = NostrEvent("9".repeat(64), alice, s0, GROUP_CHAT_KIND, listOf(listOf("h", "other")), "hi", "sig")
        tracker.receiveMessage(elsewhere, t0 + 500)
        assertEquals(listOf(alice), tracker.visible(null, t0 + 500))
    }

    @Test
    fun threadReplyAlsoEndsChannelTypingButNotOtherThreads() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice), t0)
        tracker.receiveTyping(typing(alice, tags = listOf(listOf("h", group), listOf("e", root, "", "reply"))), t0)
        tracker.receiveTyping(typing(alice, tags = listOf(listOf("h", group), listOf("e", otherRoot, "", "reply"))), t0)
        tracker.receiveMessage(message(alice, threadRoot = root), t0 + 500)
        assertEquals(emptyList(), tracker.visible(null, t0 + 500))
        assertEquals(emptyList(), tracker.visible(root, t0 + 500))
        assertEquals(listOf(alice), tracker.visible(otherRoot, t0 + 500))
    }

    @Test
    fun channelAndThreadAreShownSeparately() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(alice), t0)
        tracker.receiveTyping(typing(bob, tags = listOf(listOf("h", group), listOf("e", root, "", "reply"))), t0)
        assertEquals(listOf(alice), tracker.visible(null, t0))
        assertEquals(listOf(bob), tracker.visible(root, t0))
    }

    @Test
    fun visibleIsOrderedByWhenTypingStarted() {
        val tracker = TypingTracker(me, group)
        tracker.receiveTyping(typing(bob), t0)
        tracker.receiveTyping(typing(alice), t0 + 100)
        tracker.receiveTyping(typing(bob, at = s0 + 3), t0 + 3_000)
        assertEquals(listOf(bob, alice), tracker.visible(null, t0 + 3_000))
    }

    @Test
    fun nextChangeAtIsEarliestExpiry() {
        val tracker = TypingTracker(me, group)
        assertNull(tracker.nextChangeAt(t0))
        tracker.receiveTyping(typing(alice), t0)
        tracker.receiveTyping(typing(bob), t0 + 1_000)
        assertEquals(t0 + 8_000, tracker.nextChangeAt(t0 + 1_000))
        assertEquals(t0 + 9_000, tracker.nextChangeAt(t0 + 8_000))
        assertNull(tracker.nextChangeAt(t0 + 9_000))
    }

    @Test
    fun recordsAreCapped() {
        val tracker = TypingTracker(me, group, maxRecords = 2)
        tracker.receiveTyping(typing(alice), t0)
        tracker.receiveTyping(typing(bob), t0)
        assertFalse(tracker.receiveTyping(typing(carol), t0))
        assertEquals(listOf(alice, bob), tracker.visible(null, t0))
        // 期限が切れて静かになった記録は消え、空きができる。
        assertTrue(tracker.receiveTyping(typing(carol, at = s0 + 20), t0 + 20_000))
        assertEquals(listOf(carol), tracker.visible(null, t0 + 20_000))
    }

    // ---- 間引き ----

    @Test
    fun throttleAllowsOncePerThreeSeconds() {
        val throttle = TypingThrottle()
        assertTrue(throttle.tryAcquire(t0))
        assertFalse(throttle.tryAcquire(t0 + 2_999))
        assertTrue(throttle.tryAcquire(t0 + 3_000))
    }

    // ---- 文言 ----

    @Test
    fun labelForOneTwoAndMany() {
        assertNull(typingLabel(emptyList()))
        assertEquals("Aさんが入力中…", typingLabel(listOf("A")))
        assertEquals("AさんとBさんが入力中…", typingLabel(listOf("A", "B")))
        assertEquals("Aさんほか2人が入力中…", typingLabel(listOf("A", "B", "C")))
    }
}
