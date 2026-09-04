package com.nostr.torinos.ui.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationMuteFilterTest {
    @Test
    fun removesEveryNotificationTypeFromMutedActors() {
        val mutedPubkey = "A".repeat(64)
        val visiblePubkey = "b".repeat(64)
        val items = NotificationType.entries.mapIndexed { index, type ->
            notificationItem("muted-$index", type, mutedPubkey)
        } + notificationItem("visible", NotificationType.Reply, visiblePubkey)

        val filtered = items.filterNotMutedActors(setOf(mutedPubkey.lowercase()))

        assertEquals(listOf("visible"), filtered.map { it.id })
    }

    @Test
    fun leavesItemsUnchangedWhenMuteListIsEmpty() {
        val items = listOf(notificationItem("visible", NotificationType.Like, "c".repeat(64)))

        assertEquals(items, items.filterNotMutedActors(emptySet()))
    }

    private fun notificationItem(
        id: String,
        type: NotificationType,
        actorPubkey: String,
    ) = NotificationItem(
        id = id,
        type = type,
        actorPubkey = actorPubkey,
        createdAt = 1,
        receivedAt = 1,
        targetEventId = null,
        event = null,
    )
}
