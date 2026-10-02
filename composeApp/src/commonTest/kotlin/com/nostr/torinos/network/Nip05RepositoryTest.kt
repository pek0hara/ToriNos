package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Nip05RepositoryTest {
    private val pubkey = "a".repeat(64)

    @Test
    fun parsesAddressAndRejectsUrlInjection() {
        assertEquals("https://example.com/.well-known/nostr.json?name=alice", parseNip05Address(" alice@EXAMPLE.COM ")?.url)
        assertEquals("_", parseNip05Address("_@example.com")?.name)
        for (value in listOf("alice", "a@@example.com", "a@https://example.com", "a@example.com/path", "a@example.com?x=1", "a@example.com:443", "a@-example.com", "a@exa_mple.com", "a+b@example.com")) {
            assertNull(parseNip05Address(value), value)
        }
    }

    @Test
    fun verifiesOnlyMatchingHexPublicKey() = runTest {
        assertEquals(Nip05Status.Verified, verifyNip05("alice@example.com", pubkey) {
            """{"names":{"alice":"$pubkey"},"relays":{}}"""
        })
        for (key in listOf("b".repeat(64), "npub1abc", "A".repeat(64))) {
            assertEquals(Nip05Status.Mismatch, verifyNip05("alice@example.com", pubkey) {
                """{"names":{"alice":"$key"}}"""
            })
        }
        assertEquals(Nip05Status.Mismatch, verifyNip05("alice@example.com", pubkey) { """{"names":{}}""" })
    }

    @Test
    fun invalidInputDoesNotFetchAndFailuresAreNotMismatch() = runTest {
        assertEquals(Nip05Status.InvalidAddress, verifyNip05("invalid", pubkey) { error("must not fetch") })
        assertEquals(Nip05Status.NetworkError, verifyNip05("a@example.com", pubkey) { error("offline") })
        assertEquals(Nip05Status.NetworkError, verifyNip05("a@example.com", pubkey) { "not JSON" })
        assertEquals(Nip05Status.NetworkError, verifyNip05("a@example.com", pubkey) {
            withTimeout(100) { awaitCancellation() }
        })
    }

    @Test
    fun cancellationPropagatesSoEditedAddressesDoNotKeepOldResults() = runTest {
        assertFailsWith<CancellationException> {
            verifyNip05("a@example.com", pubkey) { throw CancellationException() }
        }
    }

    @Test
    fun recheckingSameAddressUpdatesAllExistingObservers() = runTest {
        var result = Nip05Status.Verified
        val store = Nip05VerificationStore(backgroundScope, now = { testScheduler.currentTime }) { _, _ -> result }
        val profileStates = mutableListOf<Nip05Status>()
        val dialogStates = mutableListOf<Nip05Status>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.observe("a@example.com", pubkey).collect { profileStates += it }
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.observe(" a@example.com ", pubkey).collect { dialogStates += it }
        }
        store.verify("a@example.com", pubkey)
        runCurrent()
        assertEquals(Nip05Status.Verified, profileStates.last())
        result = Nip05Status.Mismatch
        store.verify("a@example.com", pubkey, forceRefresh = true)
        runCurrent()
        assertEquals(Nip05Status.Mismatch, profileStates.last())
        assertEquals(profileStates, dialogStates)
        result = Nip05Status.Verified
        store.verify("a@example.com", pubkey, forceRefresh = true)
        runCurrent()
        assertEquals(Nip05Status.Verified, profileStates.last())
    }

    @Test
    fun networkFailuresExpireAfterThirtySecondsAndReplaceVerifiedResults() = runTest {
        var time = 0L
        var calls = 0
        var result = Nip05Status.Verified
        val store = Nip05VerificationStore(backgroundScope, now = { time }) { _, _ ->
            calls++
            result
        }
        store.verify("a@example.com", pubkey)
        result = Nip05Status.NetworkError
        assertEquals(Nip05Status.NetworkError, store.verify("a@example.com", pubkey, forceRefresh = true))
        result = Nip05Status.Verified
        time = 29_999L
        assertEquals(Nip05Status.NetworkError, store.verify("a@example.com", pubkey))
        assertEquals(2, calls)
        time = 30_000L
        assertEquals(Nip05Status.Verified, store.verify("a@example.com", pubkey))
        assertEquals(3, calls)
        time = 329_999L
        store.verify("a@example.com", pubkey)
        assertEquals(3, calls)
        time = 330_000L
        store.verify("a@example.com", pubkey)
        assertEquals(4, calls)
    }

    @Test
    fun verificationCacheSeparatesPublicKeys() = runTest {
        var calls = 0
        val store = Nip05VerificationStore(backgroundScope) { _, key ->
            calls++
            if (key == pubkey) Nip05Status.Verified else Nip05Status.Mismatch
        }
        assertEquals(Nip05Status.Verified, store.verify("a@example.com", pubkey))
        assertEquals(Nip05Status.Mismatch, store.verify("a@example.com", "b".repeat(64)))
        assertEquals(2, calls)
    }

    @Test
    fun simultaneousVerificationIsSharedWhenAnObserverLeaves() = runTest {
        var calls = 0
        val response = CompletableDeferred<Nip05Status>()
        val store = Nip05VerificationStore(backgroundScope) { _, _ ->
            calls++
            response.await()
        }
        val first = async { store.verify("a@example.com", pubkey) }
        runCurrent()
        val second = async { store.verify("a@example.com", pubkey, forceRefresh = true) }
        runCurrent()
        first.cancel()
        response.complete(Nip05Status.Verified)
        assertEquals(Nip05Status.Verified, second.await())
        assertEquals(1, calls)
    }

}
