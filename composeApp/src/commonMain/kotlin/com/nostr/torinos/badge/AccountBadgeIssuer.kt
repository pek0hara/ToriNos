package com.nostr.torinos.badge

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * A signed event waiting for relays; [targets] are the relays that have not accepted it yet. [own] are
 * this account's write relays: the issued list is read back from them, so one of them must hold it.
 */
@Serializable
internal data class BadgeOutboxEntry(
    val event: NostrEvent,
    val targets: Set<String>,
    val succeeded: Set<String> = emptySet(),
    val attempts: Int = 0,
    val own: Set<String> = targets,
)

@Serializable
internal data class BadgeIssuerSavedState(val outbox: List<BadgeOutboxEntry> = emptyList())

internal enum class BadgeSyncStatus { Synced, Pending, Partial }

internal data class IssuedAward(val event: NostrEvent, val recipients: List<String>, val sync: BadgeSyncStatus)

internal data class IssuedBadge(
    val address: BadgeAddress,
    val definition: NostrEvent,
    val awards: List<IssuedAward>,
    val sync: BadgeSyncStatus,
) {
    val draft: BadgeDefinitionDraft get() = BadgeDefinitionDraft.from(definition)
    val display: BadgeDisplayItem get() = badgeDefinitionDisplay(definition)
}

internal data class BadgeIssuerState(
    val ready: Boolean = false,
    /** Running operations; a count so an overlapping refresh cannot clear another operation's busy state. */
    val operations: Int = 0,
    val issued: List<IssuedBadge> = emptyList(),
    /** Identifiers that a new definition must not reuse silently, deleted ones included. */
    val usedIdentifiers: Set<String> = emptySet(),
    val deletedIdentifiers: Set<String> = emptySet(),
    val incomplete: Boolean = false,
    val canLoadMore: Boolean = false,
    val message: String? = null,
) {
    val busy: Boolean get() = operations > 0
}

/** Own definitions and awards with deletion requests and the outbox applied, newest first. */
internal fun issuedBadges(
    owner: String,
    events: Collection<NostrEvent>,
    outbox: List<BadgeOutboxEntry> = emptyList(),
): BadgeIssuerState {
    val own = (events + outbox.map { it.event }).filter { it.pubkey == owner }.distinctBy { it.id }
    val deletions = own.filter { it.kind == 5 }
    val latest = mutableMapOf<BadgeAddress, NostrEvent>()
    own.filter { it.kind == 30009 }.forEach { event ->
        val address = event.badgeAddress() ?: return@forEach
        val existing = latest[address]
        if (existing == null || event.badgeNewerThan(existing)) latest[address] = event
    }
    val deletedAddresses = deletions.flatMap { deletion -> deletion.tags.filter { it.firstOrNull() == "a" }.mapNotNull { it.getOrNull(1) } }
        .mapNotNull(BadgeAddress::parse).filter { it.kind == 30009 && it.pubkey == owner }.toSet()
    fun sync(event: NostrEvent): BadgeSyncStatus = outbox.firstOrNull { it.event.id == event.id }
        ?.let { if (it.succeeded.isEmpty()) BadgeSyncStatus.Pending else BadgeSyncStatus.Partial } ?: BadgeSyncStatus.Synced
    val awardsByAddress = own.filter { it.kind == 8 && !badgeDeleted(it, deletions) }.mapNotNull { award ->
        val addresses = award.tags.filter { it.firstOrNull() == "a" }
        val address = addresses.singleOrNull()?.getOrNull(1)?.let(BadgeAddress::parse)?.takeIf { it.pubkey == owner } ?: return@mapNotNull null
        address to award
    }.groupBy({ it.first }, { it.second })
    val issued = latest.filterValues { !badgeDeleted(it, deletions) }.map { (address, definition) ->
        val awards = awardsByAddress[address].orEmpty().sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).map { award ->
            IssuedAward(award, award.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }.filter(::badgeHex).distinct(), sync(award))
        }
        IssuedBadge(address, definition, awards, sync(definition))
    }.sortedWith(compareByDescending<IssuedBadge> { it.definition.createdAt }.thenBy { it.definition.id })
    val deleted = (deletedAddresses.map { it.identifier } + latest.filterValues { badgeDeleted(it, deletions) }.keys.map { it.identifier }).toSet()
    return BadgeIssuerState(issued = issued, usedIdentifiers = latest.keys.map { it.identifier }.toSet() + deleted, deletedIdentifiers = deleted)
}

/**
 * Badges this account issues: kind 30009 definitions, kind 8 awards and kind 5 deletion requests.
 * Signed events are persisted to an outbox before sending, and re-sent until enough relays accept them.
 */
internal class AccountBadgeIssuer(
    private val pubkey: String,
    private val signer: AccountSigner,
    private val scope: CoroutineScope,
    private val repository: BadgeRepository,
    private val relays: () -> Set<String>,
    private val writableRelays: () -> Set<String>,
    private val ensureActive: () -> Unit,
    private val readStorage: suspend (String) -> String? = LocalSettingsStorage::getString,
    private val writeStorage: suspend (String, String?) -> Unit = LocalSettingsStorage::putString,
    private val validate: (NostrEvent) -> Boolean = ::isValidEvent,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
    /** Spacing between sends so a large award does not trip relay rate limits. */
    private val sendInterval: Long = 250,
) {
    private val mutex = Mutex()
    private val sendMutex = Mutex()
    private val pageMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "badge_issuer_outbox_v1_$pubkey"
    private var saved = BadgeIssuerSavedState()
    private var initialized = false
    private var started = false
    private var job: Job? = null
    private val known = linkedMapOf<String, NostrEvent>()
    private var boundary: Long? = null
    private var pageLimit = 100
    private val mutableState = MutableStateFlow(BadgeIssuerState())
    val state: StateFlow<BadgeIssuerState> = mutableState.asStateFlow()

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        ensureActive()
        var unreadable = false
        readStorage(key)?.let { text ->
            val restored = decodeSavedState(text)
            unreadable = restored.unreadable
            // A tampered outbox must not make this account sign or display someone else's events.
            saved = BadgeIssuerSavedState(outbox = restored.outbox.filter {
                it.event.pubkey == pubkey && it.event.kind in setOf(5, 8, 30009) && validate(it.event)
            })
            // Rewrite without the unreadable parts so the warning is not shown again on every launch.
            // If that fails the warning simply comes back next time; it must not block initialization.
            if (unreadable) {
                try { persist(saved) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    com.nostr.torinos.util.logException("AccountBadgeIssuer", e, "Could not rewrite the cleaned badge outbox")
                }
            }
            mergeLocked(saved.outbox.map { it.event })
            repository.ingest(saved.outbox.map { it.event })
        }
        initialized = true
        publishStateLocked(if (unreadable) UNREADABLE_OUTBOX_MESSAGE else syncMessage())
    }

    fun start() {
        if (started) return
        started = true
        job = scope.launch {
            try {
                // The issued list is fetched when its tab opens; at startup only unsent events matter.
                initialize()
                flush()
                while (isActive) { delay(30_000); flush() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    fun onForeground() {
        if (!started) return
        scope.launch { try { flush() } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(e) } }
    }

    private fun publishStateLocked(message: String? = syncMessage()) {
        val computed = issuedBadges(pubkey, known.values, saved.outbox)
        mutableState.update { it.copy(ready = initialized, issued = computed.issued, usedIdentifiers = computed.usedIdentifiers,
            deletedIdentifiers = computed.deletedIdentifiers, message = message) }
    }

    private fun syncMessage(): String? = if (saved.outbox.any { it.succeeded.isEmpty() && it.attempts > 0 }) "未送信のバッジ操作があります。自動で再送します" else null

    private fun mergeLocked(events: List<NostrEvent>) {
        events.filter { it.pubkey == pubkey && it.kind in setOf(5, 8, 30009) }.forEach { known[it.id] = it }
    }

    private suspend fun merge(events: List<NostrEvent>) {
        repository.ingest(events)
        mutex.withLock { mergeLocked(events); publishStateLocked() }
    }

    /**
     * Definitions, deletions and the newest page of awards. Returns whether every relay answered.
     * Called from screens, so failures become the state message instead of escaping.
     */
    suspend fun refresh(): Boolean = guarded(false) { pageMutex.withLock {
        initialize()
        busy {
            boundary = null; pageLimit = 100
            val targets = relays()
            // Awards are paged per relay, so they need their own request: other kinds would distort the page counts.
            val (definitions, awards) = coroutineScope {
                val definitions = async { repository.transport.fetch(listOf(
                    NostrFilter(authors = listOf(pubkey), kinds = listOf(30009)),
                    NostrFilter(authors = listOf(pubkey), kinds = listOf(5)),
                ), targets) }
                val awards = async { fetchAwardPage(targets) }
                definitions.await() to awards.await()
            }
            ensureActive()
            merge(definitions.events + awards.events)
            advancePage(awards, definitions.complete)
            definitions.complete && awards.complete
        }
    } }

    /** The next page of older awards. */
    suspend fun loadMore() = guarded(Unit) { pageMutex.withLock {
        initialize()
        busy {
            val result = fetchAwardPage(relays())
            ensureActive()
            merge(result.events)
            advancePage(result, true)
        }
    } }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(e); fallback }

    private suspend fun fetchAwardPage(targets: Set<String>): BadgeFetchResult =
        repository.transport.fetch(listOf(NostrFilter(authors = listOf(pubkey), kinds = listOf(8), until = boundary, limit = pageLimit)), targets)

    // `limit` applies per relay: only relays that filled the page may hold older awards (see AccountBadgeStore.loadAwards).
    private fun advancePage(result: BadgeFetchResult, othersComplete: Boolean) {
        val requestedLimit = pageLimit
        val next = result.pages.values.filter { it.count >= minOf(requestedLimit, 100) }.maxOfOrNull { it.oldest }
        if (next != null) {
            if (next == boundary) pageLimit = (pageLimit * 2).coerceAtMost(2_000)
            else { boundary = next; pageLimit = 100 }
        }
        val complete = result.complete && othersComplete
        mutableState.update { it.copy(incomplete = !complete || pageLimit == 2_000, canLoadMore = !result.complete || next != null,
            message = if (!complete) "発行したバッジを一部取得できませんでした" else syncMessage()) }
    }

    private suspend fun <T> busy(block: suspend () -> T): T {
        mutableState.update { it.copy(operations = it.operations + 1) }
        try { return block() } finally { mutableState.update { it.copy(operations = it.operations - 1) } }
    }

    private fun requireWritable(): Set<String> = writableRelays().also { check(it.isNotEmpty()) { "書き込みリレーが設定されていません" } }

    /**
     * Creates or edits a definition. The current version is fetched first: a newer remote version is a
     * conflict, and a new definition may only take an identifier confirmed to be unused. A deleted
     * identifier comes back only with [allowRevive], because its old awards would become valid again.
     */
    suspend fun saveDefinition(draft: BadgeDefinitionDraft, allowRevive: Boolean = false): Boolean = attempt {
        initialize()
        val address = BadgeAddress(30009, pubkey, draft.identifier)
        check(isValidBadgeIdentifier(draft.identifier)) { validateBadgeDefinition(draft, emptySet())!! }
        val result = repository.transport.fetch(listOf(
            NostrFilter(authors = listOf(pubkey), kinds = listOf(30009), dTags = listOf(draft.identifier)),
            NostrFilter(authors = listOf(pubkey), kinds = listOf(5), aTags = listOf(address.value)),
        ), relays())
        ensureActive()
        merge(result.events)
        check(result.complete) { "最新情報を取得できませんでした。再試行してください" }
        val event = mutex.withLock {
            val versions = (known.values + saved.outbox.map { it.event }).filter { it.kind == 30009 && it.badgeAddress() == address }
            val latest = versions.reduceOrNull { a, b -> if (b.badgeNewerThan(a)) b else a }
            val deletions = (known.values + saved.outbox.map { it.event }).filter { it.kind == 5 }
            val addressDeletions = deletions.filter { deletion -> deletion.tags.any { it.firstOrNull() == "a" && it.getOrNull(1) == address.value } }
            val baseline = draft.baseline
            if (baseline == null) {
                val latestDeleted = latest != null && badgeDeleted(latest, deletions)
                check(latest == null || latestDeleted) { "この識別子は使用済みです" }
                // Awards point at the address, so a new definition there would make old awards valid again.
                check((addressDeletions.isEmpty() && !latestDeleted) || allowRevive) { "削除済みのバッジと同じ識別子です" }
            } else {
                check(latest?.id == baseline.id) { "別の端末で更新されました。最新版を読み込んで編集し直してください" }
                check(!badgeDeleted(baseline, deletions)) { "削除済みのバッジは編集できません" }
            }
            validateBadgeDefinition(draft, emptySet())?.let { error(it) }
            val floor = maxOf(latest?.createdAt ?: 0, addressDeletions.maxOfOrNull { it.createdAt } ?: 0)
            check(floor <= now() + 300) { "未来時刻のバッジと競合しています" }
            signer.sign(content = baseline?.content.orEmpty(), kind = 30009, tags = badgeDefinitionTags(draft),
                createdAt = maxOf(now(), floor + 1))
        }
        val writable = requireWritable()
        enqueue(listOf(Triple(event, writable, writable)))
    }

    /** One award per recipient, sent to own write relays and each recipient's read relays. */
    suspend fun award(address: BadgeAddress, recipients: List<String>): Boolean = attempt {
        initialize()
        val targets = recipients.map { it.lowercase() }.distinct()
        check(targets.isNotEmpty()) { "受賞者を追加してください" }
        check(targets.size <= BADGE_AWARD_RECIPIENTS_MAX) { "一度に授与できるのは${BADGE_AWARD_RECIPIENTS_MAX}人までです" }
        check(targets.all(::badgeHex)) { "受賞者の公開鍵が正しくありません" }
        check(address.kind == 30009 && address.pubkey == pubkey) { "自分のバッジだけ授与できます" }
        check(state.value.issued.any { it.address == address }) { "バッジが見つからないか、削除されています" }
        val writable = requireWritable()
        val readRelays = repository.readRelaysOf(targets.toSet(), relays())
        ensureActive()
        val events = mutex.withLock {
            val time = now()
            targets.map { recipient ->
                val hints = readRelays[recipient].orEmpty()
                Triple(signer.sign(content = "", kind = 8, tags = badgeAwardTags(address, recipient, hints.firstOrNull()), createdAt = time),
                    writable + hints, writable)
            }
        }
        enqueue(events)
    }

    /** Deletion request for one own award, also sent where its recipients read (NIP-09 rebroadcast). */
    suspend fun revokeAward(award: NostrEvent): Boolean = attempt {
        initialize()
        check(award.kind == 8 && award.pubkey == pubkey) { "自分の授与だけ取り消せます" }
        enqueue(deletionEvents(listOf(award), award.createdAt))
    }

    /**
     * Deletion request for a definition, optionally with every award made for it so other clients do not
     * keep awards that point to a missing definition.
     */
    suspend fun deleteDefinition(address: BadgeAddress, includeAwards: Boolean): Boolean = attempt {
        initialize()
        check(address.kind == 30009 && address.pubkey == pubkey) { "自分のバッジだけ削除できます" }
        val result = repository.transport.fetch(listOf(
            NostrFilter(authors = listOf(pubkey), kinds = listOf(30009), dTags = listOf(address.identifier)),
            NostrFilter(authors = listOf(pubkey), kinds = listOf(8), aTags = listOf(address.value), limit = 5_000),
        ), relays())
        ensureActive()
        merge(result.events)
        val badge = state.value.issued.firstOrNull { it.address == address } ?: error("バッジが見つからないか、削除済みです")
        val targets = listOf(badge.definition) + if (includeAwards) badge.awards.map { it.event } else emptyList()
        enqueue(deletionEvents(targets, targets.maxOf { it.createdAt }, recipientsOf = badge.awards.map { it.event }))
    }

    private suspend fun deletionEvents(targets: List<NostrEvent>, notBefore: Long, recipientsOf: List<NostrEvent> = targets): List<Triple<NostrEvent, Set<String>, Set<String>>> {
        val writable = requireWritable()
        val recipients = recipientsOf.flatMap { award -> award.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) } }
            .filter(::badgeHex).toSet()
        val extra = repository.readRelaysOf(recipients, relays()).values.flatten().distinct().take(10)
        ensureActive()
        return mutex.withLock {
            val time = maxOf(now(), notBefore)
            badgeDeletionTags(targets).map { tags -> Triple(signer.sign(content = "", kind = 5, tags = tags, createdAt = time), writable + extra, writable) }
        }
    }

    /**
     * Persists before anything is sent; the display updates immediately from the signed events. Queued
     * events that a new deletion request covers are dropped, so a revoked award is not sent afterwards.
     */
    private suspend fun enqueue(events: List<Triple<NostrEvent, Set<String>, Set<String>>>): Boolean {
        mutex.withLock {
            ensureActive()
            val deletions = events.map { it.first }.filter { it.kind == 5 }
            val kept = saved.outbox.filter { it.event.kind == 5 || !badgeDeleted(it.event, deletions) }
            persist(saved.copy(outbox = kept + events.map { (event, targets, own) -> BadgeOutboxEntry(event, targets, own = own) }))
            mergeLocked(events.map { it.first })
            publishStateLocked()
        }
        repository.ingest(events.map { it.first })
        scope.launch { try { flush() } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(e) } }
        return true
    }

    private class RestoredOutbox(val outbox: List<BadgeOutboxEntry>, val unreadable: Boolean)

    /**
     * Reads the saved outbox entry by entry. A damaged file or entry must not stop every later badge
     * operation, so unreadable parts are dropped (the next save overwrites them) and reported once.
     */
    private fun decodeSavedState(text: String): RestoredOutbox {
        val entries = try {
            // An empty outbox is saved without the field (defaults are not encoded).
            json.parseToJsonElement(text).jsonObject["outbox"]?.jsonArray ?: return RestoredOutbox(emptyList(), false)
        } catch (e: Exception) {
            return RestoredOutbox(emptyList(), true)
        }
        val outbox = entries.mapNotNull { entry ->
            try { json.decodeFromJsonElement(BadgeOutboxEntry.serializer(), entry) } catch (e: Exception) { null }
        }
        return RestoredOutbox(outbox, unreadable = outbox.size != entries.size)
    }

    private suspend fun persist(next: BadgeIssuerSavedState) {
        ensureActive()
        writeStorage(key, json.encodeToString(BadgeIssuerSavedState.serializer(), next))
        ensureActive()
        saved = next
    }

    /**
     * Sends each outbox entry to the relays that have not accepted it. An entry is done when every
     * relay accepted it, or after a few attempts once one of the account's own write relays holds it;
     * otherwise it keeps retrying. Relays our write policy forbids are dropped instead of retried.
     */
    suspend fun flush() = sendMutex.withLock {
        initialize()
        val pending = mutex.withLock { saved.outbox.filter { it.targets.isNotEmpty() } }
        pending.forEachIndexed { index, entry ->
            if (index > 0) delay(sendInterval)
            ensureActive()
            val result = repository.transport.publish(entry.event, entry.targets)
            mutex.withLock {
                ensureActive()
                val current = saved.outbox.firstOrNull { it.event.id == entry.event.id } ?: return@withLock
                val succeeded = current.succeeded + result.succeededRelays
                val remaining = current.targets - result.succeededRelays - result.rejectedRelays
                val next = current.copy(targets = remaining, succeeded = succeeded, attempts = current.attempts + 1)
                val done = remaining.isEmpty() || (succeeded.any { it in current.own } && next.attempts >= MAX_PARTIAL_ATTEMPTS)
                persist(saved.copy(outbox = saved.outbox.mapNotNull { if (it.event.id != entry.event.id) it else next.takeUnless { done } }))
                if (done && succeeded.isEmpty()) {
                    // Every relay refused it: there is nothing left to retry, so stop showing it as issued.
                    known.remove(entry.event.id)
                    publishStateLocked("バッジ操作がリレーに拒否されました: " +
                        result.failedRelays.values.firstOrNull().orEmpty().take(200))
                } else publishStateLocked()
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Boolean): Boolean {
        mutableState.update { it.copy(operations = it.operations + 1, message = null) }
        return try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError(e); false }
        finally { mutableState.update { it.copy(operations = it.operations - 1) } }
    }

    private fun showError(error: Exception) { mutableState.update { it.copy(message = error.message ?: "バッジ処理に失敗しました") } }

    fun close() { job?.cancel() }

    private companion object {
        const val MAX_PARTIAL_ATTEMPTS = 5
        const val UNREADABLE_OUTBOX_MESSAGE = "保存されていた未送信のバッジ操作の一部を読み込めなかったため破棄しました"
    }
}
