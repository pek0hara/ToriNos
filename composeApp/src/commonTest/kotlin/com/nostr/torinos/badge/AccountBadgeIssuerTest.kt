package com.nostr.torinos.badge

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountBadgeIssuerTest {
    private val recipient = "9".repeat(64)
    private val supporter = BadgeAddress(30009, badgeOwner, "supporter")

    private class Fixture(scope: CoroutineScope) {
        var events = listOf<NostrEvent>()
        var complete = true
        var active = true
        var writable = setOf("wss://one.example", "wss://two.example")
        val storage = mutableMapOf<String, String>()
        val sent = mutableListOf<Pair<NostrEvent, Set<String>>>()
        var send: suspend (NostrEvent, Set<String>) -> RelayPublishResult = { _, relays -> RelayPublishResult(relays, emptyMap()) }
        var counter = 0
        val signer = object : AccountSigner {
            override val pubkey = badgeOwner
            override fun encryptToSelf(plaintext: String) = plaintext
            override fun decrypt(content: String, peerPubkey: String) = content
            override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent =
                badgeEvent(kind, tags = tags, time = createdAt!!, id = (++counter).toString(16).padStart(64, '0'), content = content)
        }
        val transport = BadgeTransport(open = { badgeResponse(it, events, complete) }, validate = { true },
            send = { event, urls -> sent += event to urls; send(event, urls) },
            validationDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher)
        val repo = BadgeRepository(scope, transport, readRelays = { setOf("wss://one.example") })
        fun issuer(scope: CoroutineScope) = AccountBadgeIssuer(badgeOwner, signer, scope, repo,
            relays = { setOf("wss://one.example") }, writableRelays = { writable },
            ensureActive = { check(active) { "account switched" } },
            readStorage = { storage[it] }, writeStorage = { key, value -> if (value == null) storage.remove(key) else storage[key] = value },
            validate = { true }, now = { 100 }, sendInterval = 0)
    }

    private fun definition(time: Long = 10, id: String = "1".repeat(64), name: String = "Supporter") =
        badgeEvent(30009, tags = listOf(listOf("d", "supporter"), listOf("name", name)), time = time, id = id)

    @Test fun newDefinitionIsPersistedBeforeSendingAndLeavesOutboxOnceAccepted() = runTest {
        val f = Fixture(backgroundScope)
        var storedWhenSent = false
        f.send = { _, relays -> storedWhenSent = f.storage.values.single().contains("supporter"); RelayPublishResult(relays, emptyMap()) }
        val issuer = f.issuer(backgroundScope)
        assertTrue(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter")), issuer.state.value.message)
        assertEquals(listOf(supporter), issuer.state.value.issued.map { it.address })
        runCurrent()
        assertTrue(storedWhenSent)
        val (event, targets) = f.sent.single()
        assertEquals(30009, event.kind)
        assertEquals(100L, event.createdAt)
        assertEquals(f.writable, targets)
        assertTrue(issuer.state.value.issued.single().sync == BadgeSyncStatus.Synced)
        assertFalse(f.storage.values.single().contains("supporter"))
    }

    @Test fun unreadableSavedOutboxDoesNotDisableIssuing() = runTest {
        val f = Fixture(backgroundScope)
        f.storage["badge_issuer_outbox_v1_$badgeOwner"] = "{broken"
        val issuer = f.issuer(backgroundScope)
        issuer.initialize()
        assertTrue(issuer.state.value.ready)
        assertNotNull(issuer.state.value.message)
        assertTrue(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter")), issuer.state.value.message)
        runCurrent()
        assertEquals(30009, f.sent.single().first.kind)
    }

    @Test fun emptiedOutboxIsNotReportedAsUnreadable() = runTest {
        // すべて送り終えた送信箱は既定値を省いて保存される。読めない扱いにして毎回警告しない。
        val f = Fixture(backgroundScope)
        val first = f.issuer(backgroundScope)
        assertTrue(first.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter")), first.state.value.message)
        runCurrent()
        val next = f.issuer(backgroundScope)
        next.initialize()
        assertNull(next.state.value.message)
    }

    @Test fun failingToRewriteTheCleanedOutboxStillInitializes() = runTest {
        val f = Fixture(backgroundScope)
        f.storage["badge_issuer_outbox_v1_$badgeOwner"] = "{broken"
        var failWrites = true
        val issuer = AccountBadgeIssuer(badgeOwner, f.signer, backgroundScope, f.repo,
            relays = { setOf("wss://one.example") }, writableRelays = { f.writable }, ensureActive = {},
            readStorage = { f.storage[it] },
            writeStorage = { key, value -> check(!failWrites) { "disk full" }; if (value == null) f.storage.remove(key) else f.storage[key] = value },
            validate = { true }, now = { 100 }, sendInterval = 0)
        issuer.initialize()
        assertTrue(issuer.state.value.ready)
        failWrites = false
        assertTrue(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter")), issuer.state.value.message)
    }

    @Test fun unreadableOutboxEntryDoesNotDropTheOthers() = runTest {
        val f = Fixture(backgroundScope)
        val first = f.issuer(backgroundScope)
        f.send = { _, _ -> RelayPublishResult(emptySet(), mapOf("wss://one.example" to "offline")) }
        assertTrue(first.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter")), first.state.value.message)
        runCurrent()
        val key = "badge_issuer_outbox_v1_$badgeOwner"
        val saved = f.storage.getValue(key)
        f.storage[key] = saved.replace("\"outbox\":[", "\"outbox\":[{\"broken\":true},")
        val restored = f.issuer(backgroundScope)
        restored.initialize()
        assertEquals(listOf(supporter), restored.state.value.issued.map { it.address })
        assertNotNull(restored.state.value.message)
        // 読めなかった部分を除いて保存し直すので、次の起動では警告を出さない。
        assertFalse(f.storage.getValue(key).contains("broken"))
        val next = f.issuer(backgroundScope)
        next.initialize()
        assertFalse(next.state.value.message.orEmpty().contains("読み込めなかった"))
        assertEquals(listOf(supporter), next.state.value.issued.map { it.address })
    }

    @Test fun newDefinitionCannotTakeAnExistingOrUnconfirmedIdentifier() = runTest {
        val f = Fixture(backgroundScope)
        f.events = listOf(definition())
        val issuer = f.issuer(backgroundScope)
        assertFalse(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Again")))
        assertEquals("この識別子は使用済みです", issuer.state.value.message)
        f.events = emptyList(); f.complete = false
        assertFalse(issuer.saveDefinition(BadgeDefinitionDraft("fresh", "Fresh")))
        assertTrue(f.storage.isEmpty())
    }

    @Test fun editRequiresTheLatestVersionAndCreatesANewerOne() = runTest {
        val f = Fixture(backgroundScope)
        val baseline = definition(time = 150).copy(tags = definition().tags + listOf(listOf("custom", "keep")), content = "keep content")
        f.events = listOf(baseline)
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        val draft = issuer.state.value.issued.single().draft.copy(name = "Renamed")
        val remote = definition(time = 160, id = "2".repeat(64)).copy(tags = baseline.tags, content = "keep content")
        f.events = listOf(baseline, remote)
        assertFalse(issuer.saveDefinition(draft))
        assertTrue(f.storage.isEmpty())
        // The conflict sticks to the version that was seen, even if a relay later returns only the old one.
        f.events = listOf(baseline)
        assertFalse(issuer.saveDefinition(draft))
        val latest = issuer.state.value.issued.single().draft
        assertEquals(remote, latest.baseline)
        assertTrue(issuer.saveDefinition(latest.copy(name = "Renamed")), issuer.state.value.message)
        val saved = issuer.state.value.issued.single().definition
        assertEquals(161L, saved.createdAt)
        assertEquals("keep content", saved.content)
        assertTrue(listOf("custom", "keep") in saved.tags)
        assertEquals("Renamed", issuer.state.value.issued.single().display.name)
    }

    @Test fun deletedIdentifierComesBackOnlyWhenConfirmed() = runTest {
        val f = Fixture(backgroundScope)
        f.events = listOf(definition(), badgeEvent(5, tags = listOf(listOf("a", supporter.value)), time = 120, id = "4".repeat(64)))
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        assertEquals(setOf("supporter"), issuer.state.value.deletedIdentifiers)
        assertTrue(issuer.state.value.issued.isEmpty())
        assertFalse(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Again")))
        assertTrue(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Again"), allowRevive = true), issuer.state.value.message)
        // Must be newer than the deletion request, or it would be deleted on arrival.
        assertEquals(121L, issuer.state.value.issued.single().definition.createdAt)
    }

    @Test fun awardSendsOneEventPerRecipientIncludingTheirReadRelays() = runTest {
        val f = Fixture(backgroundScope)
        val second = "8".repeat(64)
        f.events = listOf(definition(),
            badgeEvent(10002, pubkey = recipient, tags = listOf(listOf("r", "wss://read.example", "read"), listOf("r", "wss://write.example", "write")), id = "5".repeat(64)))
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        assertTrue(issuer.award(supporter, listOf(recipient, second.uppercase(), recipient)), issuer.state.value.message)
        runCurrent()
        assertEquals(2, f.sent.size)
        val (first, targets) = f.sent.first { (event, _) -> event.tags.any { it == listOf("p", recipient, "wss://read.example") } }
        assertEquals(f.writable + "wss://read.example", targets)
        assertTrue(isBadgeAwardFor(first, supporter, recipient))
        assertEquals(f.writable, f.sent.first { (event, _) -> event.tags.any { it.getOrNull(1) == second } }.second)
        assertEquals(setOf(recipient, second), issuer.state.value.issued.single().awards.flatMap { it.recipients }.toSet())
        // The recipient's own badge view sees the award immediately.
        assertNotNull(f.repo.display(BadgeSelectionItem(listOf(listOf("a", supporter.value), listOf("e", first.id))), recipient))
    }

    @Test fun awardIsRefusedForForeignOrMissingDefinitionsAndWithoutWriteRelays() = runTest {
        val f = Fixture(backgroundScope)
        f.events = listOf(definition())
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        assertFalse(issuer.award(BadgeAddress(30009, badgeIssuer, "supporter"), listOf(recipient)))
        assertFalse(issuer.award(BadgeAddress(30009, badgeOwner, "missing"), listOf(recipient)))
        assertFalse(issuer.award(supporter, listOf("npub1x")))
        assertFalse(issuer.award(supporter, (0 until BADGE_AWARD_RECIPIENTS_MAX + 1).map { it.toString(16).padStart(64, '0') }))
        f.writable = emptySet()
        assertFalse(issuer.award(supporter, listOf(recipient)))
        assertEquals("書き込みリレーが設定されていません", issuer.state.value.message)
        assertTrue(f.storage.isEmpty())
    }

    @Test fun partialAcceptanceRetriesOnlyMissingRelaysAndStopsAfterAFewAttempts() = runTest {
        val f = Fixture(backgroundScope)
        f.send = { _, relays -> RelayPublishResult(relays - "wss://two.example", mapOf("wss://two.example" to "down")) }
        val issuer = f.issuer(backgroundScope)
        issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter"))
        runCurrent()
        assertEquals(BadgeSyncStatus.Partial, issuer.state.value.issued.single().sync)
        issuer.flush()
        assertEquals(setOf("wss://two.example"), f.sent.last().second)
        repeat(5) { issuer.flush() }
        assertEquals(BadgeSyncStatus.Synced, issuer.state.value.issued.single().sync)
        assertEquals(5, f.sent.size)
    }

    @Test fun unacceptedEventsKeepRetryingAndSurviveRestart() = runTest {
        val f = Fixture(backgroundScope)
        f.send = { _, relays -> RelayPublishResult(emptySet(), relays.associateWith { "down" }) }
        val issuer = f.issuer(backgroundScope)
        issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter"))
        runCurrent()
        repeat(6) { issuer.flush() }
        assertEquals(BadgeSyncStatus.Pending, issuer.state.value.issued.single().sync)
        assertNotNull(issuer.state.value.message)
        issuer.close()

        f.send = { _, relays -> RelayPublishResult(relays, emptyMap()) }
        val restarted = f.issuer(backgroundScope)
        restarted.initialize()
        assertEquals(BadgeSyncStatus.Pending, restarted.state.value.issued.single().sync)
        restarted.flush()
        assertEquals(BadgeSyncStatus.Synced, restarted.state.value.issued.single().sync)
    }

    @Test fun acceptanceOnlyByRecipientRelaysIsNotEnough() = runTest {
        val f = Fixture(backgroundScope)
        f.events = listOf(definition(), badgeEvent(10002, pubkey = recipient, tags = listOf(listOf("r", "wss://read.example")), id = "5".repeat(64)))
        f.send = { _, relays -> RelayPublishResult(relays - f.writable, f.writable.associateWith { "down" }) }
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        issuer.award(supporter, listOf(recipient))
        runCurrent()
        repeat(6) { issuer.flush() }
        assertEquals(BadgeSyncStatus.Partial, issuer.state.value.issued.single().awards.single().sync)
        assertEquals(f.writable, f.sent.last().second)
    }

    @Test fun revokingAQueuedAwardStopsItFromBeingSent() = runTest {
        val f = Fixture(backgroundScope)
        f.events = listOf(definition())
        f.send = { _, relays -> RelayPublishResult(emptySet(), relays.associateWith { "down" }) }
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        issuer.award(supporter, listOf(recipient))
        runCurrent()
        val award = issuer.state.value.issued.single().awards.single().event
        assertTrue(issuer.revokeAward(award))
        runCurrent()
        f.sent.clear()
        issuer.flush()
        assertEquals(listOf(5), f.sent.map { it.first.kind })
    }

    @Test fun relaysForbiddenByWritePolicyAreNotRetried() = runTest {
        val f = Fixture(backgroundScope)
        f.send = { _, relays -> RelayPublishResult(relays - "wss://two.example", mapOf("wss://two.example" to "read only"), setOf("wss://two.example")) }
        val issuer = f.issuer(backgroundScope)
        issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter"))
        runCurrent()
        assertEquals(BadgeSyncStatus.Synced, issuer.state.value.issued.single().sync)
    }

    @Test fun revokeIsSentWhereTheRecipientReadsAndHidesTheAward() = runTest {
        val f = Fixture(backgroundScope)
        val award = badgeEvent(8, tags = listOf(listOf("a", supporter.value), listOf("p", recipient)), time = 50, id = "6".repeat(64))
        f.events = listOf(definition(), award,
            badgeEvent(10002, pubkey = recipient, tags = listOf(listOf("r", "wss://read.example")), id = "5".repeat(64)))
        val issuer = f.issuer(backgroundScope)
        issuer.refresh()
        assertTrue(issuer.revokeAward(award), issuer.state.value.message)
        runCurrent()
        val (deletion, targets) = f.sent.single()
        assertEquals(listOf(listOf("e", award.id), listOf("k", "8"), listOf("client", "ToriNos")), deletion.tags)
        assertEquals(f.writable + "wss://read.example", targets)
        assertTrue(issuer.state.value.issued.single().awards.isEmpty())
        assertNull(f.repo.display(BadgeSelectionItem(listOf(listOf("a", supporter.value), listOf("e", award.id))), recipient))
        assertFalse(issuer.revokeAward(badgeEvent(8, pubkey = badgeIssuer, id = "7".repeat(64))))
    }

    @Test fun deletingADefinitionCanAlsoRevokeItsAwards() = runTest {
        val f = Fixture(backgroundScope)
        val award = badgeEvent(8, tags = listOf(listOf("a", supporter.value), listOf("p", recipient)), time = 50, id = "6".repeat(64))
        f.events = listOf(definition(), award)
        val issuer = f.issuer(backgroundScope)
        assertTrue(issuer.deleteDefinition(supporter, includeAwards = true), issuer.state.value.message)
        runCurrent()
        val deletion = f.sent.single().first
        assertEquals(5, deletion.kind)
        assertTrue(listOf("a", supporter.value) in deletion.tags)
        assertTrue(listOf("e", "1".repeat(64)) in deletion.tags)
        assertTrue(listOf("e", award.id) in deletion.tags)
        assertTrue(listOf("k", "30009") in deletion.tags && listOf("k", "8") in deletion.tags)
        assertTrue(issuer.state.value.issued.isEmpty())
        assertEquals(setOf("supporter"), issuer.state.value.deletedIdentifiers)

        val only = Fixture(backgroundScope)
        only.events = listOf(definition(), award)
        val other = only.issuer(backgroundScope)
        assertTrue(other.deleteDefinition(supporter, includeAwards = false))
        runCurrent()
        assertFalse(only.sent.single().first.tags.any { it == listOf("e", award.id) })
    }

    @Test fun accountSwitchStopsSigningAndPersisting() = runTest {
        val f = Fixture(backgroundScope)
        val issuer = f.issuer(backgroundScope)
        issuer.initialize()
        f.active = false
        assertFalse(issuer.saveDefinition(BadgeDefinitionDraft("supporter", "Supporter")))
        assertTrue(f.storage.isEmpty())
        assertEquals(0, f.counter)
    }

    @Test fun tamperedOutboxEntriesAreDropped() = runTest {
        val f = Fixture(backgroundScope)
        val foreign = badgeEvent(30009, pubkey = badgeIssuer, tags = listOf(listOf("d", "x")), id = "e".repeat(64))
        val note = badgeEvent(1, id = "f".repeat(64))
        f.storage["badge_issuer_outbox_v1_$badgeOwner"] = kotlinx.serialization.json.Json.encodeToString(BadgeIssuerSavedState.serializer(),
            BadgeIssuerSavedState(listOf(BadgeOutboxEntry(foreign, setOf("wss://one.example")), BadgeOutboxEntry(note, setOf("wss://one.example")))))
        val issuer = f.issuer(backgroundScope)
        issuer.initialize()
        issuer.flush()
        assertTrue(f.sent.isEmpty())
    }
}
