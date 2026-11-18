package com.nostr.torinos.network

import com.nostr.torinos.account.SignerException
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.RelayMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RelayAuthCoordinatorTest {
    private val relay = "wss://group.example"
    private val me = "a".repeat(64)

    private class FakeSender {
        val sent = mutableListOf<Triple<String, Long, NostrEvent>>()
        var response: (NostrEvent) -> RelayMessage.Ok? = { RelayMessage.Ok(it.id, true, "") }
        var pending: CompletableDeferred<RelayMessage.Ok?>? = null
        suspend fun send(url: String, generation: Long, event: NostrEvent): RelayMessage.Ok? {
            sent += Triple(url, generation, event)
            pending?.let { return it.await() }
            return response(event)
        }
    }

    private fun signer(pubkey: String = me, onSign: () -> Unit = {}): RelayAuthSigner =
        RelayAuthSigner { url, challenge ->
            onSign()
            NostrEvent(
                id = "auth-$challenge",
                pubkey = pubkey,
                createdAt = 1,
                kind = 22242,
                tags = authEventTags(url, challenge),
                content = "",
                sig = "sig",
            )
        }

    private fun TestScope.coordinator(sender: FakeSender) =
        RelayAuthCoordinator(backgroundScope) { url, gen, event -> sender.send(url, gen, event) }

    @Test
    fun authEventTagsCarryRelayAndChallenge() {
        assertEquals(
            listOf(listOf("relay", relay), listOf("challenge", "c1")),
            authEventTags(relay, "c1"),
        )
    }

    @Test
    fun allowedRelayIsAuthenticatedWhenChallenged() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        val successes = mutableListOf<RelayAuthResult>()
        backgroundScope.launchCollect(auth.results, successes)
        auth.install(me, signer()) { it == relay }

        auth.onChallenge(relay, generation = 1, challenge = "c1")
        runCurrent()

        assertEquals(1, sender.sent.size)
        assertEquals(1L, sender.sent.single().second)
        assertEquals(RelayAuthState.Authenticated(1, me), auth.states.value[relay])
        assertTrue(auth.isAuthenticated(relay, generation = 1))
        assertEquals(listOf<RelayAuthResult>(RelayAuthResult.Succeeded(relay, 1)), successes)
    }

    @Test
    fun relayOutsideAllowListIsNotAuthenticated() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        auth.install(me, signer()) { false }

        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        assertTrue(sender.sent.isEmpty())
        assertEquals(RelayAuthState.Challenged(1, "c1"), auth.states.value[relay])
        assertFalse(auth.canAuthenticate(relay))
    }

    @Test
    fun allowingLaterUsesStoredChallenge() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        val allowed = mutableSetOf<String>()
        auth.install(me, signer()) { it in allowed }
        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        allowed += relay
        auth.onPolicyChanged(relay)
        runCurrent()

        assertEquals("auth-c1", sender.sent.single().third.id)
        assertTrue(auth.isAuthenticated(relay, 1))
    }

    @Test
    fun rejectedAuthBecomesFailed() = runTest {
        val sender = FakeSender().apply { response = { RelayMessage.Ok(it.id, false, "restricted: not a relay member") } }
        val auth = coordinator(sender)
        val results = mutableListOf<RelayAuthResult>()
        backgroundScope.launchCollect(auth.results, results)
        auth.install(me, signer()) { true }

        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        assertEquals(RelayAuthState.Failed(1, me, "restricted: not a relay member"), auth.states.value[relay])
        assertEquals(listOf<RelayAuthResult>(RelayAuthResult.Failed(relay, 1, "restricted: not a relay member")), results)
        assertFalse(auth.isAuthenticated(relay, 1))
    }

    @Test
    fun missingResponseBecomesFailed() = runTest {
        val sender = FakeSender().apply { response = { null } }
        val auth = coordinator(sender)
        auth.install(me, signer()) { true }

        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        assertIs<RelayAuthState.Failed>(auth.states.value[relay])
    }

    @Test
    fun signerFailureBecomesFailedWithoutSending() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        auth.install(me, signer(onSign = { throw SignerException.AuthRequired(relay) })) { true }

        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        assertTrue(sender.sent.isEmpty())
        assertIs<RelayAuthState.Failed>(auth.states.value[relay])
    }

    @Test
    fun disconnectDiscardsInFlightResult() = runTest {
        val sender = FakeSender().apply { pending = CompletableDeferred() }
        val auth = coordinator(sender)
        val results = mutableListOf<RelayAuthResult>()
        backgroundScope.launchCollect(auth.results, results)
        auth.install(me, signer()) { true }
        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        auth.onDisconnected(relay)
        sender.pending!!.complete(RelayMessage.Ok("auth-c1", true, ""))
        runCurrent()

        assertNull(auth.states.value[relay])
        assertTrue(results.isEmpty())
    }

    @Test
    fun newChallengeOnNewConnectionAuthenticatesAgain() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        auth.install(me, signer()) { true }
        auth.onChallenge(relay, 1, "c1")
        runCurrent()
        auth.onDisconnected(relay)

        auth.onChallenge(relay, 2, "c2")
        runCurrent()

        assertEquals(listOf(1L, 2L), sender.sent.map { it.second })
        assertTrue(auth.isAuthenticated(relay, 2))
        assertFalse(auth.isAuthenticated(relay, 1))
    }

    @Test
    fun uninstallDropsSignerAndReportsAuthenticatedRelays() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        auth.install(me, signer()) { true }
        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        val stale = auth.uninstall()

        assertEquals(setOf(relay), stale)
        assertFalse(auth.canAuthenticate(relay))
        assertNull(auth.states.value[relay])
    }

    @Test
    fun reinstallingSameAccountDuringAuthRestartsIt() = runTest {
        val sender = FakeSender().apply { pending = CompletableDeferred() }
        val auth = coordinator(sender)
        auth.install(me, signer()) { true }
        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        // 同じアカウントで登録し直す（アカウントを切り替えて戻った）。認証中の試行は捨てられるので、やり直す。
        sender.pending = null
        auth.install(me, signer()) { true }
        runCurrent()

        assertEquals(2, sender.sent.size)
        assertTrue(auth.isAuthenticated(relay, 1))
    }

    @Test
    fun installingAnotherAccountReportsRelaysAuthenticatedAsPrevious() = runTest {
        val sender = FakeSender()
        val auth = coordinator(sender)
        auth.install(me, signer()) { true }
        auth.onChallenge(relay, 1, "c1")
        runCurrent()

        val other = "b".repeat(64)
        val stale = auth.install(other, signer(pubkey = other)) { true }

        assertEquals(setOf(relay), stale)
    }
}

private fun <T> CoroutineScope.launchCollect(flow: Flow<T>, into: MutableList<T>) {
    launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { into += it } }
}

class AuthClosedDecisionTest {
    private fun decide(
        disposition: RetryDisposition = RetryDisposition.RetryAfterAuth,
        canAuthenticate: Boolean = true,
        authenticatedNow: Boolean = false,
        authFailedNow: Boolean = false,
        alreadyRetried: Boolean = false,
    ) = decideAuthClosed(disposition, canAuthenticate, authenticatedNow, authFailedNow, alreadyRetried)

    @Test
    fun nonAuthRefusalIsLeftToExistingHandling() {
        assertEquals(AuthClosedDecision.NotAuth, decide(disposition = RetryDisposition.RetryWithBackoff, authenticatedNow = true))
    }

    @Test
    fun relayOutsideAllowListKeepsTheRefusal() {
        assertEquals(AuthClosedDecision.NotAuth, decide(canAuthenticate = false))
    }

    @Test
    fun refusalThatRacedAheadOfAuthIsRetriedAtOnce() {
        assertEquals(AuthClosedDecision.RetryNow, decide(authenticatedNow = true))
    }

    @Test
    fun allowedRelayWaitsForAuth() {
        assertEquals(AuthClosedDecision.AwaitAuth, decide())
    }

    @Test
    fun refusalAfterAuthWasRetriedOnceIsFinal() {
        // 認証済みでも auth-required を返すリレーで、REQ と CLOSED を繰り返さない。
        assertEquals(AuthClosedDecision.NotAuth, decide(authenticatedNow = true, alreadyRetried = true))
    }

    @Test
    fun failedAuthOnThisConnectionIsNotAwaited() {
        assertEquals(AuthClosedDecision.NotAuth, decide(authFailedNow = true))
    }
}

class AuthWaitVerdictTest {
    private val pk = "a".repeat(64)

    @Test
    fun relayThatNeverChallengesGivesUp() {
        assertEquals(AuthWaitVerdict.GiveUp, authWaitVerdict(state = null, generation = 1, waitedMillis = 20_000, maxMillis = 180_000))
        assertEquals(AuthWaitVerdict.GiveUp, authWaitVerdict(RelayAuthState.Challenged(1, "c"), 1, 20_000, 180_000))
    }

    @Test
    fun signerApprovalIsAwaitedUpToTheLimit() {
        val authenticating = RelayAuthState.Authenticating(1, "c", pk)
        assertEquals(AuthWaitVerdict.KeepWaiting, authWaitVerdict(authenticating, 1, 20_000, 180_000))
        assertEquals(AuthWaitVerdict.GiveUp, authWaitVerdict(authenticating, 1, 180_000, 180_000))
    }

    @Test
    fun settledOrNewerConnectionNeedsNothing() {
        assertEquals(AuthWaitVerdict.Done, authWaitVerdict(RelayAuthState.Authenticated(1, pk), 1, 20_000, 180_000))
        assertEquals(AuthWaitVerdict.Done, authWaitVerdict(RelayAuthState.Failed(1, pk, "x"), 1, 20_000, 180_000))
        assertEquals(AuthWaitVerdict.Done, authWaitVerdict(RelayAuthState.Challenged(2, "c"), 1, 20_000, 180_000))
    }
}
