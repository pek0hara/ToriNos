package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatPresentationTest {
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val third = "e".repeat(64)
    private val relayKey = "c".repeat(64)
    private val tokyo = TimeZone.of("Asia/Tokyo")

    private fun msg(createdAt: Long, pubkey: String = other, kind: Int = GROUP_CHAT_KIND, tags: List<List<String>> = listOf(listOf("h", "g"))) =
        NostrEvent("e$createdAt-$pubkey", pubkey, createdAt, kind, tags, "hi", "s")

    // ---- 未読の件数 ----

    @Test
    fun unreadCountsOnlyOthersMessagesAfterReadPosition() {
        val events = listOf(msg(90), msg(100), msg(101), msg(102, pubkey = me), msg(103))
        assertEquals(2, countUnreadMessages(events, me, readUntil = 100))
    }

    @Test
    fun unreadSkipsMessagesTheRelaySignedForMe() {
        // Buzz ではリレーが署名し、本人は actor タグに入る。
        val relaySigned = msg(110, pubkey = relayKey, tags = listOf(listOf("h", "g"), listOf("actor", me)))
        val relaySignedOther = msg(111, pubkey = relayKey, tags = listOf(listOf("h", "g"), listOf("actor", other)))
        assertEquals(1, countUnreadMessages(listOf(relaySigned, relaySignedOther), me, readUntil = 100))
    }

    @Test
    fun unreadIgnoresDuplicatesFromSeveralFetches() {
        val event = msg(120)
        assertEquals(1, countUnreadMessages(listOf(event, event), me, readUntil = 100))
    }

    @Test
    fun unreadBadgeTextCapsLargeCounts() {
        assertEquals("3", unreadBadgeText(3))
        assertEquals("99+", unreadBadgeText(100))
        assertEquals("99", unreadBadgeText(99))
    }

    // ---- メンバー一覧 ----

    @Test
    fun memberListUsesTheNewestRelaySignedList() {
        val old = NostrEvent("m1", relayKey, 100, GROUP_MEMBERS_KIND, listOf(listOf("d", "g"), listOf("p", me)), "", "s")
        val new = NostrEvent("m2", relayKey, 200, GROUP_MEMBERS_KIND, listOf(listOf("d", "g"), listOf("p", me), listOf("p", other), listOf("p", other)), "", "s")
        val forged = NostrEvent("m3", other, 300, GROUP_MEMBERS_KIND, listOf(listOf("d", "g"), listOf("p", third)), "", "s")
        val elsewhere = NostrEvent("m4", relayKey, 400, GROUP_MEMBERS_KIND, listOf(listOf("d", "x"), listOf("p", third)), "", "s")
        assertEquals(listOf(me, other), groupMemberList(listOf(old, new, forged, elsewhere), "g", relayKey))
    }

    @Test
    fun memberListIsUnknownWithoutAList() {
        assertNull(groupMemberList(emptyList(), "g", relayKey))
    }

    // ---- 時刻と日付区切り ----

    @Test
    fun clockTimeIsHoursAndMinutesInLocalZone() {
        assertEquals("22:05", chatClockTime(epoch(2026, 11, 17, 22, 5), tokyo))
        assertEquals("09:03", chatClockTime(epoch(2026, 11, 17, 9, 3), tokyo))
    }

    @Test
    fun dayLabelsSayTodayYesterdayOrTheDate() {
        val today = LocalDate(2026, 11, 17)
        assertEquals("今日", chatDayLabel(today, today))
        assertEquals("昨日", chatDayLabel(LocalDate(2026, 11, 16), today))
        assertEquals("11月15日（日）", chatDayLabel(LocalDate(2026, 11, 15), today))
        assertEquals("2025年12月31日（水）", chatDayLabel(LocalDate(2025, 12, 31), today))
    }

    private data class Item(val author: String, val at: Long, val reply: Boolean = false)

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(tokyo).epochSeconds

    private fun timeline(items: List<Item>) = chatTimeline(items, tokyo, author = { it.author }, createdAt = { it.at }, startsNewGroup = { it.reply })

    @Test
    fun timelineInsertsDayHeadersAndGroupsConsecutiveMessages() {
        val a = Item(other, epoch(2026, 11, 16, 23, 58))
        val b = Item(other, epoch(2026, 11, 16, 23, 59))
        val c = Item(other, epoch(2026, 11, 17, 0, 1))
        val d = Item(me, epoch(2026, 11, 17, 0, 2))
        val e = Item(me, epoch(2026, 11, 17, 0, 10))
        val f = Item(me, epoch(2026, 11, 17, 0, 11), reply = true)
        assertEquals(
            listOf(
                ChatTimelineItem.Day(LocalDate(2026, 11, 16)),
                ChatTimelineItem.Message(a, grouped = false),
                ChatTimelineItem.Message(b, grouped = true),
                // 日付が変われば、同じ人でもまとめない。
                ChatTimelineItem.Day(LocalDate(2026, 11, 17)),
                ChatTimelineItem.Message(c, grouped = false),
                ChatTimelineItem.Message(d, grouped = false),
                // 5分より空いたら、まとめない。
                ChatTimelineItem.Message(e, grouped = false),
                // 返信は前とまとめない。
                ChatTimelineItem.Message(f, grouped = false),
            ),
            timeline(listOf(a, b, c, d, e, f)),
        )
    }

    @Test
    fun timelineGroupsWithinFiveMinutesOfThePreviousMessage() {
        val base = epoch(2026, 11, 17, 10, 0)
        val items = listOf(Item(other, base), Item(other, base + 299), Item(other, base + 598))
        assertEquals(listOf(false, true, true), timeline(items).filterIsInstance<ChatTimelineItem.Message<Item>>().map { it.grouped })
    }

    // ---- 一覧のプレビューの送信者 ----

    @Test
    fun previewPrefixesSenderNameInGroups() {
        assertEquals("しゅん: こんにちは", chatPreviewWithSender("こんにちは", sender = other, senderName = "しゅん", me = me, oneOnOne = false))
        assertEquals("自分: こんにちは", chatPreviewWithSender("こんにちは", sender = me, senderName = "わたし", me = me, oneOnOne = false))
    }

    @Test
    fun previewOmitsSenderInOneOnOneDmAndWhileNameIsUnknown() {
        assertEquals("やあ", chatPreviewWithSender("やあ", sender = other, senderName = "ぺこ", me = me, oneOnOne = true))
        assertEquals("自分: やあ", chatPreviewWithSender("やあ", sender = me, senderName = null, me = me, oneOnOne = true))
        assertEquals("やあ", chatPreviewWithSender("やあ", sender = other, senderName = null, me = me, oneOnOne = false))
        assertEquals("やあ", chatPreviewWithSender("やあ", sender = null, senderName = null, me = me, oneOnOne = false))
    }

    @Test
    fun senderOfRelaySignedBuzzMessageIsTheActor() {
        val relaySigned = msg(1, pubkey = relayKey, tags = listOf(listOf("h", "g"), listOf("actor", other)))
        assertEquals(other, groupMessageAuthor(relaySigned, GroupFlavor.Buzz, relayKey))
        // リレー以外の署名なら actor は信じない。
        val forged = msg(2, pubkey = third, tags = listOf(listOf("h", "g"), listOf("actor", other)))
        assertEquals(third, groupMessageAuthor(forged, GroupFlavor.Buzz, relayKey))
        assertEquals(relayKey, groupMessageAuthor(relaySigned, GroupFlavor.Standard, relayKey))
    }

    // ---- 一覧の時刻 ----

    @Test
    fun listTimeIsClockTodayThenYesterdayDateOrMonth() {
        val now = epoch(2026, 11, 18, 8, 30)
        assertEquals("00:05", chatListTime(epoch(2026, 11, 18, 0, 5), now, tokyo))
        assertEquals("昨日", chatListTime(epoch(2026, 11, 17, 23, 59), now, tokyo))
        assertEquals("11月15日", chatListTime(epoch(2026, 11, 15, 12, 0), now, tokyo))
        assertEquals("1月2日", chatListTime(epoch(2026, 1, 2, 12, 0), now, tokyo))
        assertEquals("2025年10月", chatListTime(epoch(2025, 10, 3, 12, 0), now, tokyo))
        // 時計のずれで少し未来の発言も、今日として時刻を出す。
        assertEquals("08:31", chatListTime(epoch(2026, 11, 18, 8, 31), now, tokyo))
    }
}
