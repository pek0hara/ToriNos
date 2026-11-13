package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BadgeAwardeesTest {
    private val relays = setOf("wss://one.example")
    private val second = "1".repeat(64)
    private val third = "2".repeat(64)

    private fun award(id: String, time: Long, vararg recipients: String, a: List<String> = listOf(badgeAddress.value), pubkey: String = badgeIssuer) =
        badgeEvent(8, pubkey, a.map { listOf("a", it) } + recipients.map { listOf("p", it) }, time = time, id = id)

    @Test fun listsRecipientsNewestFirstWithTheirFirstAward() {
        val events = listOf(
            award("1".repeat(64), 10, badgeOwner, second),
            award("2".repeat(64), 20, second, third),
            award("3".repeat(64), 5, badgeOwner),
        )
        val result = badgeAwardees(badgeAddress, events)
        assertEquals(listOf(third, second, badgeOwner), result.map { it.pubkey })
        assertEquals(5, result.last().awardedAt)
        assertEquals(10, result[1].awardedAt)
    }

    @Test fun ignoresForgedOtherAndAmbiguousAwards() {
        val other = BadgeAddress(30009, badgeIssuer, "other").value
        val events = listOf(
            award("1".repeat(64), 1, badgeOwner, pubkey = badgeOwner),
            award("2".repeat(64), 1, second, a = listOf(other)),
            award("3".repeat(64), 1, third, a = listOf(badgeAddress.value, other)),
            award("4".repeat(64), 1, "npub-not-hex"),
        )
        assertTrue(badgeAwardees(badgeAddress, events).isEmpty())
    }

    @Test fun issuerDeletionRemovesTheAwardButOthersCannot() {
        val revoked = award("1".repeat(64), 1, badgeOwner)
        val kept = award("2".repeat(64), 1, second)
        val deletions = listOf(
            badgeEvent(5, badgeIssuer, listOf(listOf("e", revoked.id)), time = 2, id = "8".repeat(64)),
            badgeEvent(5, badgeOwner, listOf(listOf("e", kept.id)), time = 2, id = "9".repeat(64)),
        )
        assertEquals(listOf(second), badgeAwardees(badgeAddress, listOf(revoked, kept) + deletions).map { it.pubkey })
        // The award being viewed is kept when relays miss it, unless the issuer deleted it.
        val known = BadgeAwardee(badgeOwner, 1, revoked.id)
        assertEquals(listOf(second), badgeAwardees(badgeAddress, listOf(kept) + deletions, known).map { it.pubkey })
        assertEquals(listOf(third), badgeAwardees(badgeAddress, emptyList(), BadgeAwardee(third, 1, "7".repeat(64))).map { it.pubkey })
    }

    @Test fun repositoryAsksTheIssuerForAwardsAndDeletions() = runTest {
        val revoked = award("1".repeat(64), 1, badgeOwner)
        val data = listOf(revoked, award("2".repeat(64), 2, second),
            badgeEvent(5, badgeIssuer, listOf(listOf("e", revoked.id)), time = 3, id = "8".repeat(64)))
        val requests = mutableListOf<SubscriptionSpec>()
        val transport = BadgeTransport(open = { requests += it; badgeResponse(it, data) }, validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler))
        val repo = BadgeRepository(backgroundScope, transport, { relays })
        val result = repo.awardees(badgeAddress)
        assertEquals(listOf(second), result.awardees.map { it.pubkey })
        assertTrue(result.complete)
        assertFalse(result.truncated)
        val filters = requests.flatMap { it.filters }
        assertTrue(NostrFilter(kinds = listOf(8), authors = listOf(badgeIssuer), aTags = listOf(badgeAddress.value), limit = BADGE_AWARDEES_LIMIT) in filters)
        assertTrue(filters.any { it.kinds == listOf(5) && it.authors == listOf(badgeIssuer) })
        // Awards for other recipients do not enter the display cache.
        assertTrue(repo.events().none { it.kind == 8 })
        repo.close()
    }

    @Test fun reachingTheLimitIsReportedAsTruncated() = runTest {
        val data = (0 until BADGE_AWARDEES_LIMIT).map { i ->
            award(i.toString(16).padStart(64, '0'), i.toLong(), "e" + i.toString(16).padStart(63, '0'))
        }
        val transport = BadgeTransport(open = { badgeResponse(it, data) }, validate = { true },
            validationDispatcher = StandardTestDispatcher(testScheduler))
        val repo = BadgeRepository(backgroundScope, transport, { relays })
        val result = repo.awardees(badgeAddress)
        assertEquals(BADGE_AWARDEES_LIMIT, result.awardees.size)
        assertTrue(result.truncated)
        assertFalse(result.complete)
        repo.close()
    }
}
