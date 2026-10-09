package com.nostr.torinos.ui.notification

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationGroupingTest {
    private val day1 = 1_700_000_000L - 1_700_000_000L % DAY // UTC 0:00
    private val day2 = day1 + DAY

    @Test
    fun groupsLikesOnSameTargetAndDay() {
        val entries = groupNotifications(
            listOf(
                item("l3", NotificationType.Like, "c", day2 + 300, target = "t1"),
                item("l2", NotificationType.Like, "b", day2 + 200, target = "t1"),
                item("l1", NotificationType.Like, "a", day2 + 100, target = "t2"),
                item("l0", NotificationType.Like, "a", day1 + 100, target = "t1"),
            ),
            TimeZone.UTC,
        )

        assertEquals(
            listOf("date", "l3,l2", "l1", "date", "l0"),
            entries.map { it.describe() },
        )
    }

    @Test
    fun keepsLikesAndRepostsOfSameTargetSeparate() {
        val entries = groupNotifications(
            listOf(
                item("r1", NotificationType.Repost, "a", day1 + 300, target = "t1"),
                item("l1", NotificationType.Like, "b", day1 + 200, target = "t1"),
                item("r0", NotificationType.Repost, "c", day1 + 100, target = "t1"),
            ),
            TimeZone.UTC,
        )

        assertEquals(listOf("date", "r1,r0", "l1"), entries.map { it.describe() })
    }

    @Test
    fun groupsFollowsPerDayButNeverRepliesOrUnresolvedTargets() {
        val entries = groupNotifications(
            listOf(
                item("f2", NotificationType.Follow, "a", day2 + 300),
                item("p1", NotificationType.Reply, "b", day2 + 250, target = "t1"),
                item("p0", NotificationType.Reply, "c", day2 + 240, target = "t1"),
                item("f1", NotificationType.Follow, "b", day2 + 200),
                item("x1", NotificationType.Like, "b", day2 + 150),
                item("x0", NotificationType.Like, "c", day2 + 140),
                item("f0", NotificationType.Follow, "c", day1 + 100),
            ),
            TimeZone.UTC,
        )

        assertEquals(
            listOf("date", "f2,f1", "p1", "p0", "x1", "x0", "date", "f0"),
            entries.map { it.describe() },
        )
        assertEquals(
            listOf(LocalDate(2023, 11, 15), LocalDate(2023, 11, 14)),
            entries.filterIsInstance<NotificationListEntry.DateHeader>().map { it.date },
        )
    }

    private fun NotificationListEntry.describe(): String = when (this) {
        is NotificationListEntry.DateHeader -> "date"
        is NotificationListEntry.Group -> items.joinToString(",") { it.id }
    }

    private fun item(
        id: String,
        type: NotificationType,
        actor: String,
        receivedAt: Long,
        target: String? = null,
    ) = NotificationItem(
        id = id,
        type = type,
        actorPubkey = actor,
        createdAt = receivedAt,
        receivedAt = receivedAt,
        targetEventId = target,
        event = null,
    )

    private companion object {
        const val DAY = 24 * 60 * 60L
    }
}
