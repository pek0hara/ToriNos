package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzzPresenceTest {
    private val relay = "f".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val t0 = 1_700_000_000_000L
    private var seq = 0

    private fun live(author: String, content: String, kind: Int = BUZZ_PRESENCE_KIND) =
        NostrEvent((++seq).toString().padStart(64, '0'), author, t0 / 1000, kind, emptyList(), content, "sig")

    private fun snapshotEvent(subject: String?, content: String, signer: String = relay, extraP: String? = null, kind: Int = BUZZ_PRESENCE_KIND): String {
        val tags = buildList {
            subject?.let { add("""["p","$it"]""") }
            extraP?.let { add("""["p","$it"]""") }
        }.joinToString(",")
        return """{"id":"${(++seq).toString().padStart(64, '0')}","pubkey":"$signer","created_at":${t0 / 1000},"kind":$kind,"tags":[$tags],"content":"$content","sig":"s"}"""
    }

    // ---- 組み立て・解析 ----

    @Test
    fun presenceEventIsOnlineWithoutTags() {
        val draft = buildBuzzPresence(PresenceStatus.Online)
        assertEquals(20001, draft.kind)
        assertEquals(BUZZ_PRESENCE_KIND, draft.kind)
        assertEquals(emptyList(), draft.tags)
        assertEquals("online", draft.content)
    }

    @Test
    fun contentParsesPlainAndLegacyJson() {
        assertEquals(PresenceStatus.Online, parsePresenceContent("online"))
        assertEquals(PresenceStatus.Away, parsePresenceContent("away"))
        assertEquals(PresenceStatus.Offline, parsePresenceContent("offline"))
        assertEquals(PresenceStatus.Away, parsePresenceContent("""{"status":"away"}"""))
        assertNull(parsePresenceContent("busy"))
        assertNull(parsePresenceContent("{broken"))
        assertNull(parsePresenceContent("""{"status":1}"""))
    }

    @Test
    fun queryBodyAsksForEachAuthor() {
        assertEquals(
            """[{"kinds":[20001],"authors":["$alice","$bob"],"limit":2}]""",
            buzzPresenceQueryBody(listOf(alice, bob)),
        )
    }

    // ---- 一括取得の応答 ----

    @Test
    fun snapshotListsStatusesAndMissingAreOffline() {
        val body = "[" + snapshotEvent(alice, "online") + "," + snapshotEvent(bob, "busy") + "]"
        val values = parseBuzzPresenceSnapshot(body, listOf(alice, bob, carol), relay)
        assertEquals(mapOf(alice to PresenceStatus.Online, bob to null, carol to PresenceStatus.Offline), values)
    }

    @Test
    fun emptySnapshotMeansAllOffline() {
        assertEquals(mapOf(alice to PresenceStatus.Offline), parseBuzzPresenceSnapshot("[]", listOf(alice), relay))
    }

    @Test
    fun untrustedSnapshotIsRejectedWhole() {
        val bad = listOf(
            "[" + snapshotEvent(alice, "online", signer = bob) + "]",
            "[" + snapshotEvent(null, "online") + "]",
            "[" + snapshotEvent(alice, "online", extraP = bob) + "]",
            "[" + snapshotEvent(carol, "online") + "]",
            "[" + snapshotEvent(alice, "online") + "," + snapshotEvent(alice, "away") + "]",
            "[" + snapshotEvent(alice, "online", kind = 1) + "]",
            """{"error":"x"}""",
            "not json",
        )
        bad.forEach { assertNull(parseBuzzPresenceSnapshot(it, listOf(alice, bob), relay), it) }
    }

    // ---- 記録 ----

    @Test
    fun liveEvidenceShowsAndExpiresAfter75Seconds() {
        val book = PresenceBook()
        assertTrue(book.receiveLive(live(alice, "online"), t0))
        assertEquals(PresenceStatus.Online, book.status(alice, t0 + 74_999))
        assertNull(book.status(alice, t0 + 75_000))
        assertNull(book.status(bob, t0))
    }

    @Test
    fun liveChangeReplacesStatus() {
        val book = PresenceBook()
        book.receiveLive(live(alice, "online"), t0)
        assertTrue(book.receiveLive(live(alice, "away"), t0 + 1_000))
        assertEquals(PresenceStatus.Away, book.status(alice, t0 + 1_000))
        assertFalse(book.receiveLive(live(alice, "away"), t0 + 2_000))
        book.receiveLive(live(alice, "offline"), t0 + 3_000)
        assertEquals(PresenceStatus.Offline, book.status(alice, t0 + 3_000))
    }

    @Test
    fun unknownContentClearsAndOtherKindsAreIgnored() {
        val book = PresenceBook()
        book.receiveLive(live(alice, "online"), t0)
        assertFalse(book.receiveLive(live(alice, "away", kind = 1), t0))
        assertEquals(PresenceStatus.Online, book.status(alice, t0))
        assertTrue(book.receiveLive(live(alice, "busy"), t0))
        assertNull(book.status(alice, t0))
    }

    @Test
    fun snapshotSetsStatusesFromResponseTime() {
        val book = PresenceBook()
        assertTrue(book.applySnapshot(mapOf(alice to PresenceStatus.Online, bob to PresenceStatus.Offline), requestedAtMillis = t0, nowMillis = t0 + 1_000))
        assertEquals(PresenceStatus.Online, book.status(alice, t0 + 75_999))
        assertEquals(PresenceStatus.Offline, book.status(bob, t0 + 1_000))
        assertNull(book.status(alice, t0 + 76_000))
    }

    @Test
    fun snapshotDoesNotOverrideNewerLiveEvidence() {
        val book = PresenceBook()
        book.receiveLive(live(alice, "away"), t0 + 500)
        book.applySnapshot(mapOf(alice to PresenceStatus.Online, bob to PresenceStatus.Online), requestedAtMillis = t0, nowMillis = t0 + 1_000)
        assertEquals(PresenceStatus.Away, book.status(alice, t0 + 1_000))
        assertEquals(PresenceStatus.Online, book.status(bob, t0 + 1_000))
    }

    @Test
    fun snapshotOverridesOlderLiveEvidence() {
        val book = PresenceBook()
        book.receiveLive(live(alice, "away"), t0)
        book.applySnapshot(mapOf(alice to PresenceStatus.Online), requestedAtMillis = t0 + 100, nowMillis = t0 + 1_000)
        assertEquals(PresenceStatus.Online, book.status(alice, t0 + 1_000))
    }

    @Test
    fun nextExpiryIsEarliest() {
        val book = PresenceBook()
        assertNull(book.nextExpiryAt(t0))
        book.receiveLive(live(alice, "online"), t0)
        book.receiveLive(live(bob, "online"), t0 + 10_000)
        assertEquals(t0 + 75_000, book.nextExpiryAt(t0 + 10_000))
        assertEquals(t0 + 85_000, book.nextExpiryAt(t0 + 75_000))
        assertNull(book.nextExpiryAt(t0 + 85_000))
    }

    // ---- 対象 ----

    @Test
    fun subjectsArePriorityThenNewestAuthorsCapped() {
        val authorsOldestFirst = listOf(alice, bob, alice, carol)
        assertEquals(listOf(bob, carol, alice), presenceSubjects(authorsOldestFirst, priority = listOf(bob)))
        assertEquals(listOf(bob, carol), presenceSubjects(authorsOldestFirst, priority = listOf(bob), limit = 2))
        assertEquals(listOf(carol, alice), presenceSubjects(authorsOldestFirst, priority = emptyList(), limit = 2))
    }

    @Test
    fun memberListIsPrioritizedWhenOpen() {
        val members = (1..300).map { it.toString().padStart(64, '0') }
        // 一覧を開いている間は、一覧の上から（管理者が先）を発言者より優先する。上限で切る。
        val targets = presenceMembersSubjects(authorsOldestFirst = listOf(alice), participants = listOf(bob), members = members, memberListOpen = true)
        assertEquals(PRESENCE_MAX_SUBJECTS, targets.size)
        assertEquals(listOf(bob) + members.take(PRESENCE_MAX_SUBJECTS - 1), targets)
        // 閉じていればメンバーは入れない。
        assertEquals(listOf(bob, alice), presenceMembersSubjects(listOf(alice), listOf(bob), members, memberListOpen = false))
        // 一覧にない発言者は、空きがあれば後ろに入る。
        assertEquals(listOf(bob, carol, alice), presenceMembersSubjects(listOf(alice), listOf(bob), listOf(carol), memberListOpen = true))
    }

    // ---- 送り直し ----

    private fun TestScope.publisher(results: ArrayDeque<Boolean> = ArrayDeque(), log: MutableList<Pair<Long, String>>) =
        PresencePublisher(backgroundScope, { url -> log += testScheduler.currentTime to url; results.removeFirstOrNull() ?: true }, Random(1))

    @Test
    fun holdPublishesNowAndRenewsEvery60To65Seconds() = runTest {
        val log = mutableListOf<Pair<Long, String>>()
        val p = publisher(log = log)
        val release = p.hold("wss://a")
        runCurrent()
        assertEquals(listOf(0L to "wss://a"), log)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1, log.size)
        advanceTimeBy(5_002); runCurrent()
        assertEquals(2, log.size)
        assertTrue(log[1].first in 60_000L..65_000L)
        release()
        advanceTimeBy(200_000); runCurrent()
        assertEquals(2, log.size)
    }

    @Test
    fun holdsAreCountedPerCommunity() = runTest {
        val log = mutableListOf<Pair<Long, String>>()
        val p = publisher(log = log)
        val first = p.hold("wss://a")
        val second = p.hold("wss://a")
        val other = p.hold("wss://b")
        runCurrent()
        assertEquals(listOf("wss://a", "wss://b"), log.map { it.second })
        first()
        first() // 2回目は数えない
        advanceTimeBy(65_001); runCurrent()
        assertEquals(2, log.count { it.second == "wss://a" })
        second()
        other()
        advanceTimeBy(200_000); runCurrent()
        assertEquals(2, log.count { it.second == "wss://a" })
        assertEquals(2, log.count { it.second == "wss://b" })
    }

    @Test
    fun failedPublishRetriesAfterFiveSeconds() = runTest {
        val log = mutableListOf<Pair<Long, String>>()
        val p = publisher(results = ArrayDeque(listOf(false, true)), log = log)
        p.hold("wss://a")
        runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf(0L, 5_000L), log.map { it.first })
    }
}
