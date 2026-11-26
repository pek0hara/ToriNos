package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.TargetFetchFailure
import com.nostr.torinos.network.TargetLoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingNotificationsTest {
    private val own = "1".repeat(64)
    private val other = "2".repeat(64)
    private val actor = "3".repeat(64)
    private val parentA = "a".repeat(64)
    private val parentB = "b".repeat(64)

    private fun target(id: String, pubkey: String) = NostrEvent(id, pubkey, 1, 1, emptyList(), "", "0".repeat(128))

    private fun reply(id: Char, parent: String, actorPubkey: String = actor): NotificationItem {
        val event = NostrEvent(id.toString().repeat(64), actorPubkey, 10, 1,
            listOf(listOf("e", parent, "", "reply"), listOf("p", own)), "hi", "0".repeat(128))
        return NotificationItem(event.id, NotificationType.Reply, actorPubkey, 10, 10, parent, event)
    }

    @Test fun waitsUntilTargetAuthorIsKnownThenShowsOnlyRepliesToOwnPosts() {
        val pending = PendingNotifications()
        val toMe = reply('c', parentA)
        val toOther = reply('d', parentB)
        pending.hold(toMe, setOf(parentA))
        pending.hold(toOther, setOf(parentB))
        assertEquals(setOf(parentA, parentB), pending.targetIds)

        val loading = pending.settle(mapOf(parentA to TargetLoadState.Loading), own, emptySet())
        assertFalse(loading.decided)
        assertEquals(2, pending.items.size)

        val settled = pending.settle(mapOf(
            parentA to TargetLoadState.Resolved(target(parentA, own)),
            parentB to TargetLoadState.Resolved(target(parentB, other)),
        ), own, emptySet())
        assertTrue(settled.decided)
        assertEquals(listOf(toMe), settled.shown)
        assertTrue(pending.isEmpty())
        assertTrue(pending.targetIds.isEmpty())
    }

    @Test fun knownOwnPostShowsWithoutFetching() {
        val pending = PendingNotifications()
        val item = reply('c', parentA)
        pending.hold(item, setOf(parentA))
        assertEquals(listOf(item), pending.settle(emptyMap(), own, ownPostIds = setOf(parentA)).shown)
        assertTrue(pending.isEmpty())
    }

    @Test fun unreachableTargetIsShownRatherThanDropped() {
        val pending = PendingNotifications()
        val item = reply('c', parentA)
        pending.hold(item, setOf(parentA))
        val settled = pending.settle(mapOf(parentA to TargetLoadState.Unavailable(TargetFetchFailure.NoRelay)), own, emptySet())
        assertEquals(listOf(item), settled.shown)
    }

    @Test fun mutedActorIsDroppedWhileWaiting() {
        val pending = PendingNotifications()
        pending.hold(reply('c', parentA, actorPubkey = other), setOf(parentA))
        pending.hold(reply('d', parentB), setOf(parentB))
        pending.removeMutedActors(setOf(other.uppercase()))
        assertEquals(listOf("d".repeat(64)), pending.items.map { it.id })
        assertEquals(setOf(parentB), pending.targetIds)
    }

    @Test fun storedNotificationsAreTriagedWithTheCurrentRules() {
        val spamEvent = NostrEvent("e".repeat(64), actor, 10, 1, listOf(listOf("p", own), listOf("p", other)), "airdrop", "0".repeat(128))
        val spam = NotificationItem(spamEvent.id, NotificationType.Reply, actor, 10, 10, null, spamEvent)
        val known = reply('c', parentA)
        val unknown = reply('d', parentB)
        val like = NotificationItem("f".repeat(64), NotificationType.Like, actor, 10, 10, parentA,
            NostrEvent("f".repeat(64), actor, 10, 7, listOf(listOf("e", parentA), listOf("p", own)), "+", "0".repeat(128)))
        val legacy = NotificationItem("9".repeat(64), NotificationType.Follow, actor, null, 10, null, null)

        val triage = triageStoredNotifications(listOf(spam, known, unknown, like, legacy), own, ownPostIds = setOf(parentA))

        assertEquals(listOf(known, like, legacy), triage.kept)
        assertEquals(listOf(unknown to setOf(parentB)), triage.held)
    }
}
