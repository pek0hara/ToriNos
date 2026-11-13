package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BadgeEventStoreTest {
    private val selection = badgeEvent(10008, tags = badgePair().tags, id = "1".repeat(64))

    @Test fun groupsSelectionAwardAndDefinitionUnderUser() {
        val store = BadgeEventStore()
        store.put(listOf(selection, badgeAward(), badgeDefinition()))

        assertEquals(setOf(selection, badgeAward(), badgeDefinition()), store.eventsFor(badgeOwner).toSet())
        assertEquals(1, store.badgeCount())
    }

    @Test fun unreferencedEventsAreNotKept() {
        val store = BadgeEventStore()
        store.put(listOf(badgeAward(), badgeDefinition()))
        assertTrue(store.eventsFor(badgeOwner).isEmpty())

        store.put(listOf(selection, badgeAward("2".repeat(64)), unrelatedDeletion()))
        assertEquals(listOf(selection), store.eventsFor(badgeOwner))
        // 参照中のバッジ単位は、定義が未着でも削除を受け取れるように残す。
        assertEquals(1, store.badgeCount())
    }

    @Test fun newerSelectionDropsReferencesThatAreNoLongerUsed() {
        val store = BadgeEventStore()
        store.put(listOf(selection, badgeAward(), badgeDefinition(), awardDeletion()))
        val cleared = badgeEvent(10008, time = 2, id = "3".repeat(64))

        store.put(listOf(cleared))

        assertEquals(listOf(cleared), store.eventsFor(badgeOwner))
        assertEquals(0, store.badgeCount())
    }

    @Test fun olderSelectionDoesNotReplaceNewerOne() {
        val store = BadgeEventStore()
        val newer = badgeEvent(10008, tags = badgePair().tags, time = 5, id = "4".repeat(64))
        store.put(listOf(newer))
        store.put(listOf(selection))

        assertEquals(listOf(newer), store.eventsFor(badgeOwner))
    }

    @Test fun deletionsAreKeptWithTheirTargets() {
        val store = BadgeEventStore()
        // The deletion arrives before the award body; the selection already references the award ID.
        store.put(listOf(selection, awardDeletion(), definitionDeletion(), badgeDefinition()))

        val events = store.eventsFor(badgeOwner).toSet()
        assertTrue(awardDeletion() in events)
        assertTrue(definitionDeletion() in events)
    }

    @Test fun ownSetIsExpandedAndItsAwardsAreKept() {
        val store = BadgeEventStore()
        val set = badgeEvent(30008, tags = listOf(listOf("d", "collection")) + badgePair().tags, id = "f".repeat(64))
        val profile = badgeEvent(10008, tags = listOf(listOf("a", "30008:$badgeOwner:collection")), id = "5".repeat(64))

        store.put(listOf(profile, set, badgeAward(), badgeDefinition()))

        assertEquals(setOf(profile, set, badgeAward(), badgeDefinition()), store.eventsFor(badgeOwner).toSet())
    }

    @Test fun relayListIsKeptOnlyForKnownUsers() {
        val store = BadgeEventStore()
        val issuerRelays = badgeEvent(10002, pubkey = badgeIssuer, id = "6".repeat(64))
        val ownerRelays = badgeEvent(10002, id = "7".repeat(64))

        store.put(listOf(issuerRelays, selection, ownerRelays))

        assertTrue(store.eventsFor(badgeIssuer).isEmpty())
        assertTrue(ownerRelays in store.eventsFor(badgeOwner))
    }

    @Test fun leastRecentlyUsedUnpinnedUserIsEvicted() {
        var now = 0L
        val store = BadgeEventStore(maxUsers = 2, nowMillis = { now })
        store.setPinnedUsers(setOf(user(1)))
        listOf(1, 2, 3).forEach { index ->
            now = index.toLong()
            store.put(listOf(badgeEvent(10008, pubkey = user(index), id = "$index".repeat(64))))
        }

        assertEquals(1, store.eventsFor(user(1)).size)
        assertTrue(store.eventsFor(user(2)).isEmpty())
        assertEquals(1, store.eventsFor(user(3)).size)
    }

    @Test fun touchKeepsRecentlyShownUser() {
        var now = 0L
        val store = BadgeEventStore(maxUsers = 2, nowMillis = { now })
        now = 1; store.put(listOf(badgeEvent(10008, pubkey = user(1), id = "1".repeat(64))))
        now = 2; store.put(listOf(badgeEvent(10008, pubkey = user(2), id = "2".repeat(64))))
        now = 3; store.touch(user(1))
        now = 4; store.put(listOf(badgeEvent(10008, pubkey = user(3), id = "3".repeat(64))))

        assertEquals(1, store.eventsFor(user(1)).size)
        assertTrue(store.eventsFor(user(2)).isEmpty())
    }

    @Test fun encodeKeepsPinnedUsersFirstWithinLimitAndRestoreRoundTrips() {
        var now = 0L
        val source = BadgeEventStore(nowMillis = { now })
        source.setPinnedUsers(setOf(badgeOwner))
        now = 1; source.put(listOf(selection, badgeAward(), badgeDefinition()))
        (2..4).forEach { index ->
            now = index.toLong()
            source.put(listOf(badgeEvent(10008, pubkey = user(index), id = "$index".repeat(64))))
        }

        val restored = BadgeEventStore()
        restored.restore(source.encode(maxUsers = 2))

        assertEquals(setOf(selection, badgeAward(), badgeDefinition()), restored.eventsFor(badgeOwner).toSet())
        assertEquals(1, restored.eventsFor(user(4)).size)
        assertTrue(restored.eventsFor(user(2)).isEmpty())
        assertTrue(restored.eventsFor(user(3)).isEmpty())
    }

    @Test fun restoreDoesNotReplaceNewerEventsAndIgnoresBrokenContent() {
        val store = BadgeEventStore()
        val newer = badgeEvent(10008, time = 9, id = "8".repeat(64))
        val stored = BadgeEventStore().apply { put(listOf(selection, badgeAward(), badgeDefinition())) }.encode(maxUsers = 10)
        store.put(listOf(newer))

        store.restore(stored)
        store.restore("not json")
        store.restore("""{"version":999,"users":[],"badges":[]}""")

        assertEquals(listOf(newer), store.eventsFor(badgeOwner))
    }

    @Test fun versionAdvancesForContentButNotForTouchOrRestore() {
        val store = BadgeEventStore()
        val stored = BadgeEventStore().apply { put(listOf(badgeEvent(10008, pubkey = user(1), id = "1".repeat(64)))) }.encode(10)

        store.put(listOf(selection))
        store.put(listOf(selection))
        store.touch(badgeOwner)
        store.restore(stored)

        assertEquals(1, store.version.value)
        assertEquals(1, store.eventsFor(user(1)).size)
    }

    private fun user(index: Int) = "$index".repeat(64)

    private fun awardDeletion(): NostrEvent =
        badgeEvent(5, badgeIssuer, listOf(listOf("e", "d".repeat(64))), time = 3, id = "9".repeat(64))

    private fun definitionDeletion(): NostrEvent =
        badgeEvent(5, badgeIssuer, listOf(listOf("a", badgeAddress.value)), time = 3, id = "0".repeat(64))

    private fun unrelatedDeletion(): NostrEvent =
        badgeEvent(5, badgeIssuer, listOf(listOf("e", "2".repeat(64))), time = 3, id = "ab".repeat(32))
}
