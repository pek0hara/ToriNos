package com.nostr.torinos.journal

import com.nostr.torinos.journal.JournalActivityKind.Like
import com.nostr.torinos.journal.JournalActivityKind.Post
import com.nostr.torinos.journal.JournalActivityKind.ReceivedLike
import com.nostr.torinos.journal.JournalActivityKind.Reply
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JournalTimelineTest {
    private val sep15 = LocalDate(2026, 9, 15)
    private val sep16 = LocalDate(2026, 9, 16)

    @Test
    fun entriesAreGroupedByDateInChronologicalOrderWithoutDuplicates() {
        val late = post("late", createdAt = epochOf(sep15, hour = 20))
        val early = post("early", createdAt = epochOf(sep15, hour = 8))
        val timeline = JournalTimeline.Empty
            .upsert(listOf(activity(late)))
            .upsert(listOf(activity(early), activity(late)))

        assertEquals(listOf("early", "late"), timeline.entries(sep15, setOf(Post)).map { it.event.id })
        assertEquals(2, timeline.size)
    }

    @Test
    fun dateBoundaryFollowsTheClockZone() {
        val lastSecond = post("last", createdAt = utcClock().endOfDay(sep15))
        val firstSecond = post("first", createdAt = utcClock().startOfDay(sep16))
        val timeline = JournalTimeline.Empty.upsert(listOf(activity(lastSecond), activity(firstSecond)))

        assertEquals(listOf("last"), timeline.entries(sep15, setOf(Post)).map { it.event.id })
        assertEquals(listOf("first"), timeline.entries(sep16, setOf(Post)).map { it.event.id })
    }

    @Test
    fun entriesAndCountsAreFilteredBySelectedKinds() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("p")),
                activity(reply("r", parentId = "p")),
                activity(reaction("rl", targetId = "p", createdAt = epochOf(sep16))),
            ),
        )
        val month = LocalDate(2026, 9, 1)

        assertEquals(listOf("p"), timeline.entries(sep15, setOf(Post)).map { it.event.id })
        assertEquals(mapOf(sep15 to 2), timeline.countsByDate(month, setOf(Post, Reply)))
        assertEquals(mapOf(sep16 to 1), timeline.countsByDate(month, setOf(ReceivedLike)))
        assertEquals(listOf(sep15, sep16), timeline.datesWithEntries(month, setOf(Post, ReceivedLike)))
        assertEquals(listOf("p", "r", "rl"), timeline.monthEntries(month, setOf(Post, Reply, ReceivedLike)).map { it.event.id })
    }

    @Test
    fun ownLikesDoNotShowUpWhenOnlyReceivedLikesAreSelected() {
        // 以前「したいいね」を取得していても、「もらったいいね」だけを選んだ日付移動・件数には出さない。
        val ownLike = reaction("own", targetId = "t", pubkey = OWNER, targetAuthor = OTHER)
        val timeline = JournalTimeline.Empty.upsert(listOf(activity(ownLike)))

        assertEquals(emptyList(), timeline.datesWithEntries(LocalDate(2026, 9, 1), setOf(ReceivedLike)))
        assertEquals(listOf(sep15), timeline.datesWithEntries(LocalDate(2026, 9, 1), setOf(Like)))
    }

    @Test
    fun removeMatchingOnlyDropsRequestedKindsOnThatDate() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(
                activity(post("p15")),
                activity(reply("r15", parentId = "x")),
                activity(post("p16", createdAt = epochOf(sep16))),
            ),
        )

        val removed = timeline.removeMatching(sep15, setOf(Post))

        assertEquals(listOf("r15"), removed.entries(sep15, setOf(Post, Reply)).map { it.event.id })
        assertEquals(listOf("p16"), removed.entries(sep16, setOf(Post)).map { it.event.id })
        assertSame(timeline, timeline.removeMatching(sep15, setOf(Like)))
    }

    @Test
    fun removeDeletesASingleEvent() {
        val timeline = JournalTimeline.Empty.upsert(listOf(activity(post("a")), activity(post("b"))))
        val removed = timeline.remove("a")

        assertEquals(listOf("b"), removed.entries(sep15, setOf(Post)).map { it.event.id })
        assertSame(removed, removed.remove("missing"))
        assertTrue(JournalTimeline.Empty.remove("a").isEmpty())
        assertFalse(removed.isEmpty())
    }

    @Test
    fun incrementalUpsertMatchesBuildingAllAtOnce() {
        val activities = (0 until 30).map { n ->
            activity(post("n$n", createdAt = epochOf(LocalDate(2026, 9, 1 + n % 15), hour = n % 24)))
        }
        val incremental = activities.chunked(4).fold(JournalTimeline.Empty) { timeline, chunk -> timeline.upsert(chunk) }
        val bulk = JournalTimeline.Empty.upsert(activities)
        val month = LocalDate(2026, 9, 1)

        assertEquals(
            bulk.monthEntries(month, setOf(Post)).map { it.event.id },
            incremental.monthEntries(month, setOf(Post)).map { it.event.id },
        )
        assertEquals(bulk.countsByDate(month, setOf(Post)), incremental.countsByDate(month, setOf(Post)))
    }

    @Test
    fun eventIdsOnReturnsIdsForTheGivenDates() {
        val timeline = JournalTimeline.Empty.upsert(
            listOf(activity(post("a")), activity(post("b", createdAt = epochOf(sep16)))),
        )
        assertEquals(setOf("a"), timeline.eventIdsOn(listOf(sep15)))
    }
}
