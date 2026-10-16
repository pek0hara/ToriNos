package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayFetchPolicyTest {
    @Test
    fun outcomesAreSortedByWhetherToRetry() {
        assertEquals(RelayFetchVerdict.Succeeded, RelayOutcome.Eose.verdict())
        assertEquals(RelayFetchVerdict.RetryWithBackoff, RelayOutcome.Unavailable("dns").verdict())
        assertEquals(RelayFetchVerdict.RetryWithBackoff, RelayOutcome.TimedOut.verdict())
        assertEquals(RelayFetchVerdict.RetryWithBackoff, (null as RelayOutcome?).verdict())
        assertEquals(RelayFetchVerdict.RetryWithBackoff, RelayOutcome.Closed("rate-limited").verdict())
        assertEquals(
            RelayFetchVerdict.IsolateFilters,
            RelayOutcome.Closed("bad filter", RetryDisposition.RetryOnFilterChange).verdict(),
        )
        assertEquals(RelayFetchVerdict.Stop, RelayOutcome.Closed("auth", RetryDisposition.RetryAfterAuth).verdict())
        assertEquals(RelayFetchVerdict.Stop, RelayOutcome.Closed("blocked", RetryDisposition.DoNotRetry).verdict())
        assertEquals(RelayFetchVerdict.RetryWithBackoff, (null as RetryDisposition?).verdict())
    }

    @Test
    fun summaryCommitsWhenAnyRelayFinishedAndReplacesOnlyWhenAllFinished() {
        val partial = RelayFetchSummary(
            mapOf(
                "a" to RelayOutcome.Eose,
                "b" to RelayOutcome.Unavailable("dns"),
                "c" to RelayOutcome.Closed("blocked", RetryDisposition.DoNotRetry),
            ),
        )
        assertTrue(partial.anySucceeded)
        assertFalse(partial.allSucceeded)
        assertEquals(setOf("a"), partial.succeeded)
        assertEquals(setOf("b"), partial.retryable)
        assertEquals(setOf("b", "c"), partial.failed.keys)

        val all = RelayFetchSummary(mapOf("a" to RelayOutcome.Eose, "b" to RelayOutcome.Eose))
        assertTrue(all.allSucceeded)
        assertTrue(all.retryable.isEmpty())

        val none = RelayFetchSummary(emptyMap())
        assertFalse(none.anySucceeded)
        assertFalse(none.allSucceeded)
    }

    @Test
    fun retryDelayDoublesUpToTheCapAndGivesUpAtMaxFailures() {
        val policy = RelayRetryPolicy(baseDelayMillis = 2_000, maxDelayMillis = 30_000, maxFailures = 3)
        assertEquals(listOf(2_000L, 4_000L), listOf(1, 2).map { policy.delayAfter(it) })
        assertNull(policy.delayAfter(3))

        val unlimited = RelayRetryPolicy(baseDelayMillis = 2_000, maxDelayMillis = 30_000)
        assertEquals(listOf(2_000L, 2_000L, 16_000L, 30_000L, 30_000L), listOf(0, 1, 4, 5, 20).map { unlimited.delayAfter(it) })
    }
}
