package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import kotlin.test.*

internal val badgeOwner = "a".repeat(64)
internal val badgeIssuer = "b".repeat(64)
internal val badgeAddress = BadgeAddress(30009, badgeIssuer, "award:one")
internal fun badgeEvent(kind: Int, pubkey: String = badgeOwner, tags: List<List<String>> = emptyList(), time: Long = 1, id: String = "c".repeat(64), content: String = "") =
    NostrEvent(id, pubkey, time, kind, tags, content, "sig")
internal fun badgePair(id: String = "d".repeat(64)) = BadgeSelectionItem(listOf(listOf("a", badgeAddress.value), listOf("e", id, "wss://hint.example")))
internal fun badgeAward(id: String = "d".repeat(64)) = badgeEvent(8, badgeIssuer, listOf(listOf("a", badgeAddress.value), listOf("p", badgeOwner)), id = id)
internal fun badgeDefinition() = badgeEvent(30009, badgeIssuer, listOf(listOf("d", "award:one"), listOf("name", "Badge")), id = "e".repeat(64))

class BadgeEventsTest {
    @Test fun addressKeepsColonsAndRejectsMalformedKeys() {
        assertEquals("award:one", BadgeAddress.parse(badgeAddress.value)?.identifier)
        assertEquals("", BadgeAddress.parse("30009:$badgeIssuer:")?.identifier)
        assertNull(BadgeAddress.parse("30009:npub:one"))
        assertNull(BadgeAddress.parse("1:$badgeIssuer:one"))
    }
    @Test fun modernEmptyProfileSuppressesLegacyRegardlessOfTimestamp() {
        val legacy = badgeEvent(30008, tags = listOf(listOf("d", "profile_badges")) + badgePair().tags, time = 200)
        val modern = badgeEvent(10008, time = 100, id = "0".repeat(64))
        assertEquals(modern, selectBadgeProfile(listOf(legacy, modern), badgeOwner))
    }
    @Test fun newestUsesLexicalIdForSameTimestamp() {
        val high = badgeEvent(10008, id = "f".repeat(64))
        val low = badgeEvent(10008, id = "0".repeat(64))
        assertTrue(low.badgeNewerThan(high))
        assertEquals(low, selectBadgeProfile(listOf(high, low), badgeOwner))
    }
    @Test fun selectionRequiresConsecutivePairsAndRecognizesSets() {
        val tags = listOf(listOf("a", badgeAddress.value), listOf("name", "gap"), listOf("e", "d".repeat(64))) +
            badgePair().tags + listOf(listOf("a", "30008:$badgeOwner:set"))
        assertEquals(2, BadgeSelection.parse(tags).items.size)
    }
    @Test fun reorderingPreservesUnknownTagsRelayHintsAndLegacyMigration() {
        val first = badgePair()
        val second = badgePair("f".repeat(64))
        val extra = listOf("unknown", "keep", "all")
        val tags = listOf(listOf("d", "profile_badges"), extra) + first.tags + second.tags
        val edited = BadgeSelection.parse(tags).editedTags(listOf(second, first), migrateLegacy = true)
        assertEquals(listOf(extra) + second.tags + first.tags, edited)
    }
    @Test fun removingPairCannotJoinOrphanReferences() {
        val orphanA = listOf("a", "30009:$badgeIssuer:orphan")
        val orphanE = listOf("e", "f".repeat(64))
        val tags = listOf(orphanA) + badgePair().tags + listOf(orphanE)
        val edited = BadgeSelection.parse(tags).editedTags(emptyList())
        assertTrue(orphanA in edited && orphanE in edited)
        assertTrue(BadgeSelection.parse(edited).items.isEmpty())
    }
    @Test fun awardMustMatchIssuerRecipientAndSingleDefinition() {
        val award = badgeAward()
        assertTrue(isBadgeAwardFor(award, badgeAddress, badgeOwner))
        assertFalse(isBadgeAwardFor(award.copy(pubkey = badgeOwner), badgeAddress, badgeOwner))
        assertFalse(isBadgeAwardFor(award, badgeAddress, "f".repeat(64)))
        assertFalse(isBadgeAwardFor(award.copy(tags = award.tags + listOf(listOf("a", badgeAddress.value))), badgeAddress, badgeOwner))
    }
    @Test fun deletionRequiresSameAuthorAndAddressCutoff() {
        val event = badgeDefinition().copy(createdAt = 10)
        val deletion = badgeEvent(5, badgeIssuer, listOf(listOf("a", badgeAddress.value)), time = 10)
        assertTrue(badgeDeleted(event, listOf(deletion)))
        assertFalse(badgeDeleted(event.copy(createdAt = 11), listOf(deletion)))
        assertFalse(badgeDeleted(event, listOf(deletion.copy(pubkey = badgeOwner))))
        assertTrue(badgeDeleted(event, listOf(deletion.copy(tags = listOf(listOf("e", event.id))))))
    }
    @Test fun imageFallbackAndThumbnailChooseSmallestSuitableCandidate() {
        val definition = badgeDefinition().copy(tags = badgeDefinition().tags + listOf(
            listOf("image", "https://image.example/full"), listOf("thumb", "https://image.example/small", "32x32"),
            listOf("thumb", "https://image.example/large", "128x128")))
        assertEquals("https://image.example/large", badgeDisplay(definition, badgeAward(), badgeOwner).thumbnailUrl(64))
        assertEquals("https://image.example/full", badgeDisplay(definition, badgeAward(), badgeOwner).thumbnailUrl(256))
    }
    @Test fun stripUsesOneDpGapsAndReservesNameSpace() {
        assertEquals(99f, badgeStripWidth(BadgeStripLayout(3, 2)))
        assertEquals(74f, badgeStripWidth(BadgeStripLayout(3, 0)))
        assertEquals(BadgeStripLayout(3, 2), badgeStripLayout(5, 203f))
        assertEquals(BadgeStripLayout(2, 3), badgeStripLayout(5, 202f))
        assertEquals(BadgeStripLayout(1, 4), badgeStripLayout(5, 153f))
        assertEquals(BadgeStripLayout(0, 5), badgeStripLayout(5, 128f))
        assertEquals(BadgeStripLayout(0, 0), badgeStripLayout(5, 127f))
        assertEquals(BadgeStripLayout(3, 0), badgeStripLayout(3, 178f))
        assertTrue(badgeStripLayout(5, 203f, 2f).visibleCount < 3)
        assertEquals(BadgeStripLayout(2, 998), badgeStripLayout(1000, 203f, overflowWidth = 40f))
        assertEquals(90f, badgeStripWidth(BadgeStripLayout(2, 998), 40f))
        assertEquals(0f, badgeStripWidth(BadgeStripLayout(0, 0)))
    }
}
