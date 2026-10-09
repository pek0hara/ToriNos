package com.nostr.torinos.badge

import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import kotlin.test.*

class BadgeIssueEventsTest {
    private val own = BadgeAddress(30009, badgeOwner, "supporter")

    @Test fun identifierIsSluggedAndAvoidsTakenIdentifiers() {
        assertEquals("torinos-supporter", badgeIdentifierFrom("ToriNos Supporter!", emptySet()))
        assertEquals("badge", badgeIdentifierFrom("応援バッジ", emptySet()))
        assertEquals("badge-3", badgeIdentifierFrom("応援バッジ", setOf("badge", "badge-2")))
        assertTrue(isValidBadgeIdentifier(badgeIdentifierFrom("x".repeat(200), emptySet())))
    }

    @Test fun validationRejectsUnsafeIdentifiersAndChangedIdentifierOnEdit() {
        val draft = BadgeDefinitionDraft(identifier = "supporter", name = "Supporter")
        assertNull(validateBadgeDefinition(draft, emptySet()))
        assertNotNull(validateBadgeDefinition(draft.copy(identifier = "a:b"), emptySet()))
        assertNotNull(validateBadgeDefinition(draft.copy(identifier = ""), emptySet()))
        assertNotNull(validateBadgeDefinition(draft, setOf("supporter")))
        assertNotNull(validateBadgeDefinition(draft.copy(name = " "), emptySet()))
        assertNotNull(validateBadgeDefinition(draft.copy(name = "x".repeat(BADGE_NAME_MAX + 1)), emptySet()))
        assertNotNull(validateBadgeDefinition(draft.copy(description = "x".repeat(BADGE_DESCRIPTION_MAX + 1)), emptySet()))
        assertNotNull(validateBadgeDefinition(draft.copy(imageUrl = "javascript:alert(1)"), emptySet()))
        val baseline = badgeEvent(30009, tags = listOf(listOf("d", "supporter")))
        assertNull(validateBadgeDefinition(draft.copy(baseline = baseline), setOf("supporter")))
        assertNotNull(validateBadgeDefinition(draft.copy(identifier = "other", baseline = baseline), emptySet()))
    }

    @Test fun editKeepsUnknownTagsAndOtherClientsThumbnails() {
        val baseline = badgeEvent(30009, tags = listOf(listOf("d", "supporter"), listOf("name", "Old"), listOf("image", "https://x/1.png", "1024x1024"),
            listOf("thumb", "https://x/256.png", "256x256"), listOf("thumb", "https://x/64.png", "64x64"), listOf("custom", "keep"), listOf("client", "Other")))
        val edited = BadgeDefinitionDraft.from(baseline).copy(name = "New", description = "")
        val tags = badgeDefinitionTags(edited)
        assertEquals(listOf("name", "New"), tags.first { it[0] == "name" })
        assertTrue(tags.none { it[0] == "description" })
        assertEquals(2, tags.count { it[0] == "thumb" })
        assertTrue(listOf("custom", "keep") in tags)
        assertEquals(listOf(listOf("client", "ToriNos")), tags.filter { it[0] == "client" })
        assertEquals(1, tags.count { it[0] == "d" })

        val replaced = badgeDefinitionTags(edited.copy(imageUrl = "https://x/2.png", imageSize = null, thumbUrl = "https://x/new.png", thumbSize = "256x256"))
        assertEquals(listOf(listOf("thumb", "https://x/new.png", "256x256")), replaced.filter { it[0] == "thumb" })
        assertEquals(listOf("image", "https://x/2.png"), replaced.first { it[0] == "image" })
    }

    @Test fun awardNamesOneRecipientWithRelayHint() {
        val tags = badgeAwardTags(own, badgeIssuer, "wss://read.example")
        assertEquals(listOf(listOf("a", own.value), listOf("p", badgeIssuer, "wss://read.example"), listOf("client", "ToriNos")), tags)
        assertEquals(listOf("p", badgeIssuer), badgeAwardTags(own, badgeIssuer)[1])
        assertFailsWith<IllegalArgumentException> { badgeAwardTags(own, "npub") }
    }

    @Test fun deletionReferencesDefinitionByAddressAndIdAndSplitsLargeRequests() {
        val definition = badgeEvent(30009, tags = listOf(listOf("d", "supporter")), id = "1".repeat(64))
        val awards = (0 until 199).map { badgeEvent(8, tags = listOf(listOf("a", own.value)), id = it.toString(16).padStart(64, '0')) }
        val chunks = badgeDeletionTags(listOf(definition) + awards)
        assertEquals(3, chunks.size)
        val first = chunks[0]
        assertEquals(listOf("a", own.value), first[0])
        assertEquals(listOf("e", definition.id), first[1])
        assertEquals(100, first.count { it[0] == "a" || it[0] == "e" })
        assertEquals(listOf(listOf("k", "30009"), listOf("k", "8")).sortedBy { it[1] }, first.filter { it[0] == "k" }.sortedBy { it[1] })
        assertEquals(listOf(listOf("k", "8")), chunks[2].filter { it[0] == "k" })
        assertEquals(201, chunks.sumOf { chunk -> chunk.count { it[0] == "a" || it[0] == "e" } })
        assertEquals(listOf(listOf("e", awards[0].id), listOf("k", "8"), listOf("client", "ToriNos")), badgeDeletionTags(listOf(awards[0])).single())
    }

    @Test fun recipientsAcceptHexNpubAndNip05() {
        assertEquals(BadgeRecipientInput.Pubkey(badgeIssuer), parseBadgeRecipient(badgeIssuer.uppercase()))
        assertEquals(BadgeRecipientInput.Pubkey(badgeIssuer), parseBadgeRecipient("nostr:" + hexToNpub(badgeIssuer)))
        assertEquals(BadgeRecipientInput.Nip05("alice@example.com"), parseBadgeRecipient(" Alice@Example.com "))
        assertEquals(BadgeRecipientInput.Invalid, parseBadgeRecipient("npub1broken"))
        assertEquals(BadgeRecipientInput.Invalid, parseBadgeRecipient("hello"))
    }

    @Test fun signedEventsPassReceiverValidationAndDeletion() {
        val key = "0".repeat(63) + "1"
        val issuer = derivePublicKey(key.fromHex()).toHex()
        val address = BadgeAddress(30009, issuer, "supporter")
        val definition = signEvent(privateKeyHex = key, content = "", kind = 30009,
            tags = badgeDefinitionTags(BadgeDefinitionDraft("supporter", "Supporter")), createdAt = 10)
        val award = signEvent(privateKeyHex = key, content = "", kind = 8, tags = badgeAwardTags(address, badgeOwner), createdAt = 11)
        assertTrue(isValidEvent(definition) && isValidEvent(award))
        assertEquals(address, definition.badgeAddress())
        assertTrue(isBadgeAwardFor(award, address, badgeOwner))
        assertEquals(address, badgeAwardAddress(award, badgeOwner))
        assertNull(badgeAwardAddress(award, badgeIssuer))
        val revoke = signEvent(privateKeyHex = key, content = "", kind = 5, tags = badgeDeletionTags(listOf(award)).single(), createdAt = 12)
        assertTrue(badgeDeleted(award, listOf(revoke)))
        assertFalse(badgeDeleted(definition, listOf(revoke)))
        val remove = signEvent(privateKeyHex = key, content = "", kind = 5, tags = badgeDeletionTags(listOf(definition)).single(), createdAt = 12)
        assertTrue(badgeDeleted(definition, listOf(remove)))
        // A newer definition at the same address is not covered by the earlier request.
        assertFalse(badgeDeleted(definition.copy(id = "f".repeat(64), createdAt = 13), listOf(remove)))
    }

    @Test fun issuedListAppliesDeletionsOutboxAndOwnership() {
        val supporter = badgeEvent(30009, tags = listOf(listOf("d", "supporter"), listOf("name", "S")), time = 10, id = "1".repeat(64))
        val newer = supporter.copy(id = "2".repeat(64), createdAt = 20, tags = listOf(listOf("d", "supporter"), listOf("name", "S2")))
        val gone = badgeEvent(30009, tags = listOf(listOf("d", "gone")), time = 10, id = "3".repeat(64))
        val goneDeletion = badgeEvent(5, tags = listOf(listOf("a", "30009:$badgeOwner:gone")), time = 30, id = "4".repeat(64))
        val award = badgeEvent(8, tags = listOf(listOf("a", own.value), listOf("p", badgeIssuer)), time = 21, id = "5".repeat(64))
        val revoked = badgeEvent(8, tags = listOf(listOf("a", own.value), listOf("p", "9".repeat(64))), time = 22, id = "6".repeat(64))
        val revocation = badgeEvent(5, tags = listOf(listOf("e", revoked.id)), time = 23, id = "7".repeat(64))
        val twoAddresses = badgeEvent(8, tags = listOf(listOf("a", own.value), listOf("a", "30009:$badgeOwner:gone"), listOf("p", badgeIssuer)), time = 24, id = "8".repeat(64))
        val foreign = badgeEvent(30009, pubkey = badgeIssuer, tags = listOf(listOf("d", "foreign")), id = "a".repeat(64))
        val pending = BadgeOutboxEntry(award, setOf("wss://one.example"))
        val state = issuedBadges(badgeOwner, listOf(supporter, newer, gone, goneDeletion, revoked, revocation, twoAddresses, foreign), listOf(pending))
        assertEquals(listOf(own), state.issued.map { it.address })
        val issued = state.issued.single()
        assertEquals(newer, issued.definition)
        assertEquals(listOf(award.id), issued.awards.map { it.event.id })
        assertEquals(BadgeSyncStatus.Pending, issued.awards.single().sync)
        assertEquals(listOf(badgeIssuer), issued.awards.single().recipients)
        assertEquals(setOf("gone"), state.deletedIdentifiers)
        assertEquals(setOf("supporter", "gone"), state.usedIdentifiers)
        assertEquals(BadgeSyncStatus.Partial, issuedBadges(badgeOwner, listOf(newer), listOf(BadgeOutboxEntry(newer, setOf("x"), setOf("y")))).issued.single().sync)
    }

    @Test fun followSearchMatchesNamesAndNip05WithPrefixMatchesFirst() {
        val alice = "1".repeat(64); val malice = "2".repeat(64); val bob = "3".repeat(64); val unknown = "4".repeat(64); val stranger = "5".repeat(64)
        val profiles = mapOf(
            alice to com.nostr.torinos.model.NostrProfile(displayName = "Alice", name = "al"),
            malice to com.nostr.torinos.model.NostrProfile(name = "Malice"),
            bob to com.nostr.torinos.model.NostrProfile(name = "ぼぶ", nip05 = "bob@alice.example"),
            stranger to com.nostr.torinos.model.NostrProfile(name = "Alice2"),
        )
        val follows = listOf(bob, malice, alice, unknown)
        assertEquals(listOf(alice, malice, bob), searchFollows("ALI", follows, profiles))
        assertEquals(listOf(bob), searchFollows("ぼ", follows, profiles))
        assertEquals(listOf(alice), searchFollows(" al ", follows, profiles, limit = 1))
        assertTrue(searchFollows("zzz", follows, profiles).isEmpty())
        // Empty query lists every follow, unnamed ones last; non-follows never appear.
        assertEquals(listOf(alice, malice, bob, unknown), searchFollows("", follows, profiles))
    }
}
