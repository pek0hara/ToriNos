package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Clock

internal enum class BadgeReferenceStatus { Resolving, Valid, Invalid, Unavailable, Deleted }

internal data class BadgeProfileState(
    val badges: List<BadgeDisplayItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val partial: Boolean = false,
    val references: Map<String, BadgeReferenceStatus> = emptyMap(),
)

/** One coordinator per account host; mounted cards register demand, never relay subscriptions. */
internal class BadgeRepository(
    private val scope: CoroutineScope,
    internal val transport: BadgeTransport = BadgeTransport(),
    private val readRelays: () -> Set<String>,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // `lock` is not reentrant on iOS: never call a function that takes it while holding it.
    private val lock = SynchronousLock()
    private val recomputeLock = SynchronousLock()
    private val dependencies = mutableMapOf<String, Set<String>>()
    private val referenceOwners = mutableMapOf<String, MutableSet<String>>()
    private val queries = BadgeFetchCoordinator(transport, nowMillis)
    private val batchPermits = Semaphore(2)
    private val inFlight = mutableSetOf<String>()
    private val activeRefreshes = mutableMapOf<String, Int>()
    // Public events in least-recently-used order, with indexes so recomputation never scans the cache.
    private val cache = linkedMapOf<String, NostrEvent>()
    private val byAddress = mutableMapOf<BadgeAddress, NostrEvent>()
    private val modernProfiles = mutableMapOf<String, NostrEvent>()
    private val relayLists = mutableMapOf<String, NostrEvent>()
    private val deletions = linkedMapOf<String, NostrEvent>()
    private val deletionIndex = mutableMapOf<String, MutableList<NostrEvent>>()
    private val states = linkedMapOf<String, MutableStateFlow<BadgeProfileState>>()
    private val consumers = mutableMapOf<String, Int>()
    private val pending = linkedSetOf<String>()
    private val forced = mutableSetOf<String>()
    private val fetchedAt = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    private val incomplete = mutableSetOf<String>()
    private val resolutionLimits = mutableMapOf<String, Int>()
    private val relayMetadataFetchedAt = mutableMapOf<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (signal in wake) {
            delay(100)
            while (true) {
                batchPermits.acquire()
                val (batch, force) = lock.withLock {
                    // A pubkey already being fetched waits for that fetch to finish (see request).
                    val eligible = pending.filter { it !in inFlight }
                    val force = eligible.firstOrNull()?.let { it in forced } ?: false
                    val batch = eligible.filter { (it in forced) == force }.take(30).toSet()
                    pending.removeAll(batch); forced.removeAll(batch); inFlight.addAll(batch)
                    batch to force
                }
                if (batch.isEmpty()) { batchPermits.release(); break }
                launch {
                    try { refresh(batch, forceDependencies = force) } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        lock.withLock { batch.forEach { failures[it] = (failures[it] ?: 0) + 1 } }
                        batch.forEach { key -> updateState(key) { it.copy(loading = (activeRefreshes[key] ?: 0) > 0, partial = true, error = "バッジを取得できませんでした") } }
                    }
                    finally {
                        val deferred = lock.withLock { inFlight.removeAll(batch); pending.isNotEmpty() }
                        batchPermits.release()
                        if (deferred) wake.trySend(Unit)
                    }
                }
            }
        }
    }
    private val refreshJob = scope.launch {
        while (isActive) {
            delay(60_000)
            lock.withLock { consumers.keys.toList() }.forEach { request(it) }
        }
    }

    fun observe(pubkey: String): StateFlow<BadgeProfileState> = recomputeLock.withLock {
        val (flow, created) = lock.withLock {
            states[pubkey]?.let { it to false } ?: MutableStateFlow(BadgeProfileState()).let {
                states[pubkey] = it
                it to true
            }
        }
        if (created) recomputeUnlocked(setOf(pubkey))
        flow
    }

    fun badgeList(pubkey: String): Flow<List<BadgeDisplayItem>> = badgeListOf(observe(pubkey))

    fun acquire(pubkey: String): () -> Unit {
        if (!badgeHex(pubkey)) return {}
        lock.withLock { consumers[pubkey] = (consumers[pubkey] ?: 0) + 1 }
        request(pubkey)
        var released = false
        return {
            lock.withLock {
                if (!released) {
                    released = true
                    val remaining = (consumers[pubkey] ?: 1) - 1
                    if (remaining == 0) { consumers.remove(pubkey); pending.remove(pubkey); forced.remove(pubkey) } else consumers[pubkey] = remaining
                }
            }
        }
    }

    fun request(pubkey: String, force: Boolean = false) {
        if (!badgeHex(pubkey)) return
        val now = nowMillis()
        val enqueue = lock.withLock {
            val last = fetchedAt[pubkey]
            val fail = failures[pubkey] ?: 0
            val ttl = if (fail > 0) (5_000L * (1L shl fail.coerceAtMost(6))) else
                if (states[pubkey]?.value?.badges?.isEmpty() != false) 60_000L else 300_000L
            when {
                // The running fetch already covers a plain request; a forced one runs after it.
                pubkey in inFlight && !force -> false
                pubkey !in inFlight && !force && last != null && now - last < ttl -> false
                else -> {
                    val added = pending.add(pubkey)
                    val upgraded = force && forced.add(pubkey)
                    added || upgraded
                }
            }
        }
        if (enqueue) wake.trySend(Unit)
    }

    fun onForeground() { lock.withLock { consumers.keys.toList() }.forEach { request(it, force = true) } }
    fun resolveMore(pubkey: String) {
        lock.withLock { resolutionLimits[pubkey] = (resolutionLimits[pubkey] ?: 100) + 100 }
        request(pubkey, force = true)
    }

    internal fun events(): List<NostrEvent> = lock.withLock { cache.values.toList() }
    internal fun profile(pubkey: String): NostrEvent? = lock.withLock { profileLocked(pubkey) }

    // ---- Index maintenance; all of these are called while holding `lock`. ----
    private fun profileLocked(pubkey: String): NostrEvent? =
        selectBadgeProfile(listOfNotNull(modernProfiles[pubkey], byAddress[BadgeAddress(30008, pubkey, "profile_badges")]), pubkey)

    private fun deletedLocked(event: NostrEvent): Boolean {
        val candidates = deletionIndex["e:${event.id}"].orEmpty() +
            event.badgeAddress()?.let { deletionIndex["a:${it.value}"] }.orEmpty()
        return candidates.isNotEmpty() && badgeDeleted(event, candidates)
    }

    private fun putLocked(event: NostrEvent) {
        cache[event.id] = event
        when {
            event.kind == 10008 -> modernProfiles[event.pubkey] = event
            event.kind == 10002 -> relayLists[event.pubkey] = event
            else -> event.badgeAddress()?.let { byAddress[it] = event }
        }
    }

    private fun removeLocked(id: String): NostrEvent? = cache.remove(id)?.also { event ->
        when {
            event.kind == 10008 -> if (modernProfiles[event.pubkey]?.id == id) modernProfiles.remove(event.pubkey)
            event.kind == 10002 -> if (relayLists[event.pubkey]?.id == id) relayLists.remove(event.pubkey)
            else -> event.badgeAddress()?.let { if (byAddress[it]?.id == id) byAddress.remove(it) }
        }
    }

    private fun deletionTargets(deletion: NostrEvent): List<String> = deletion.tags.mapNotNull { tag ->
        when (tag.firstOrNull()) { "e" -> tag.getOrNull(1)?.let { "e:$it" }; "a" -> tag.getOrNull(1)?.let { "a:$it" }; else -> null }
    }.distinct()

    private fun ownerKey(target: String) = if (target.startsWith("e:")) "id:" + target.removePrefix("e:") else target

    /** Data that a mounted card is still showing must survive eviction. */
    private fun protectedLocked(event: NostrEvent): Boolean {
        if (event.isBadgeProfile() && event.pubkey in consumers) return true
        val owners = referenceOwners["id:${event.id}"].orEmpty() +
            event.badgeAddress()?.let { referenceOwners["a:${it.value}"] }.orEmpty()
        return owners.any { it in consumers }
    }

    private fun relayListFor(pubkey: String): NostrEvent? = lock.withLock { relayLists[pubkey] }

    private fun allowedHint(url: String): String? {
        val normalized = normalizeRelayUrl(url) ?: return null
        val entry = RelayStore.entries.value.firstOrNull { normalizeRelayUrl(it.url) == normalized }
        return normalized.takeIf { entry == null || entry.enabled }
    }

    internal fun relaysFor(pubkeys: Set<String>, readMentions: Boolean = false, base: Set<String> = readRelays()): Set<String> {
        val metadata = pubkeys.mapNotNull(::relayListFor)
        val hints = metadata.flatMap { event -> event.tags.filter { tag ->
            tag.firstOrNull() == "r" && (tag.getOrNull(2) == null || tag.getOrNull(2) == if (readMentions) "read" else "write")
        }.mapNotNull { it.getOrNull(1)?.let(::allowedHint) }.take(2) }.distinct().take(10)
        return base + hints
    }

    private suspend fun discoverRelays(pubkeys: Set<String>, base: Set<String>): BadgeFetchResult {
        val now = nowMillis()
        val missing = lock.withLock { pubkeys.filter { now - (relayMetadataFetchedAt[it] ?: Long.MIN_VALUE / 2) >= 300_000L } }
        if (missing.isEmpty()) return BadgeFetchResult(emptyList(), true)
        val result = queries.fetch(listOf(NostrFilter(authors = missing.sorted(), kinds = listOf(10002))), base, 300_000L,
            onEvent = { ingest(listOf(it)) })
        if (result.complete) lock.withLock { missing.forEach { relayMetadataFetchedAt[it] = now } }
        return result
    }

    internal fun display(item: BadgeSelectionItem, recipient: String): BadgeDisplayItem? = lock.withLock {
        val address = item.address ?: return@withLock null
        val award = item.awardId?.let { cache[it] } ?: return@withLock null
        val definition = byAddress[address] ?: return@withLock null
        if (!isBadgeAwardFor(award, address, recipient) || deletedLocked(award) || deletedLocked(definition)) return@withLock null
        badgeDisplay(definition, award, recipient)
    }

    internal fun ingest(events: List<NostrEvent>) = recomputeLock.withLock {
        val affected = lock.withLock {
            val affected = mutableSetOf<String>()
            fun mark(event: NostrEvent) {
                if (event.isBadgeProfile()) affected += event.pubkey
                affected += referenceOwners["id:${event.id}"].orEmpty()
                event.badgeAddress()?.let { affected += referenceOwners["a:${it.value}"].orEmpty() }
                if (event.kind == 5) deletionTargets(event).forEach { affected += referenceOwners[ownerKey(it)].orEmpty() }
            }
            events.forEach { event ->
                if (event.kind == 5) {
                    if (deletions[event.id] != event) {
                        deletions[event.id] = event
                        deletionTargets(event).forEach { deletionIndex.getOrPut(it) { mutableListOf() }.add(event) }
                        mark(event)
                    }
                } else {
                    val address = event.badgeAddress()
                    val existing = when {
                        event.kind == 10008 -> modernProfiles[event.pubkey]
                        event.kind == 10002 -> relayLists[event.pubkey]
                        address != null -> byAddress[address]
                        else -> cache[event.id]
                    }
                    when {
                        existing == null || event.badgeNewerThan(existing) -> {
                            existing?.let { removeLocked(it.id) }
                            putLocked(event); mark(event)
                        }
                        // Re-delivery refreshes recency so data still in use is evicted last.
                        existing == event -> { cache.remove(event.id); cache[event.id] = existing }
                        existing.id == event.id -> { removeLocked(existing.id); putLocked(event); mark(event) }
                    }
                }
            }
            while (cache.size > 5_000) {
                val victim = cache.values.firstOrNull { !protectedLocked(it) } ?: break
                removeLocked(victim.id); mark(victim)
            }
            // Dropping a tombstone also drops what it deleted, so the event cannot reappear without it.
            while (deletions.size > 5_000) {
                val tombstone = deletions.remove(deletions.keys.first()) ?: break
                deletionTargets(tombstone).forEach { target ->
                    deletionIndex[target]?.let { list -> list.remove(tombstone); if (list.isEmpty()) deletionIndex.remove(target) }
                    val victims = if (target.startsWith("e:")) listOfNotNull(cache[target.removePrefix("e:")])
                        else BadgeAddress.parse(target.removePrefix("a:"))?.let { listOfNotNull(byAddress[it]) }.orEmpty()
                    victims.filter { it.pubkey == tombstone.pubkey }.forEach { removeLocked(it.id); mark(it) }
                }
            }
            affected.filterTo(mutableSetOf()) { it in states }
        }
        if (affected.isNotEmpty()) recomputeUnlocked(affected)
    }

    private fun setDependencies(pubkey: String, keys: Set<String>) {
        dependencies.remove(pubkey)?.forEach { key ->
            referenceOwners[key]?.let { owners ->
                owners.remove(pubkey)
                if (owners.isEmpty()) referenceOwners.remove(key)
            }
        }
        if (keys.isNotEmpty()) {
            dependencies[pubkey] = keys
            keys.forEach { referenceOwners.getOrPut(it) { mutableSetOf() }.add(pubkey) }
        }
    }

    private fun recompute(pubkeys: Set<String>) = recomputeLock.withLock { recomputeUnlocked(pubkeys) }

    private fun recomputeUnlocked(pubkeys: Set<String>) {
        pubkeys.forEach { pubkey ->
            lock.withLock {
                val selected = profileLocked(pubkey)
                val profile = selected?.takeUnless { deletedLocked(it) }
                val limit = resolutionLimits[pubkey] ?: 100
                val expanded = profile?.let { expandLocked(it, limit + 1) }.orEmpty()
                val items = expanded.take(limit)
                val raw = profile?.let { BadgeSelection.parse(it.tags).items }.orEmpty()
                val dependencyKeys = buildSet {
                    selected?.let { add("id:${it.id}"); it.badgeAddress()?.let { address -> add("a:${address.value}") } }
                    (raw.filter { it.address?.kind == 30008 && it.address?.pubkey == pubkey }.take(10) + items).forEach { item ->
                        item.address?.let { address ->
                            add("a:${address.value}")
                            byAddress[address]?.let { add("id:${it.id}") }
                        }
                        item.awardId?.let { add("id:$it") }
                    }
                }
                setDependencies(pubkey, dependencyKeys)
                val flow = states.getOrPut(pubkey) { MutableStateFlow(BadgeProfileState()) }
                val loading = flow.value.loading
                val referenceStates = linkedMapOf<String, BadgeReferenceStatus>()
                val badges = items.mapNotNull { item ->
                    val address = item.address ?: return@mapNotNull null
                    val award = item.awardId?.let { cache[it] }
                    val definition = byAddress[address]
                    val status = when {
                        award == null || definition == null -> if (loading) BadgeReferenceStatus.Resolving else BadgeReferenceStatus.Unavailable
                        deletedLocked(award) || deletedLocked(definition) -> BadgeReferenceStatus.Deleted
                        !isBadgeAwardFor(award, address, pubkey) -> BadgeReferenceStatus.Invalid
                        else -> BadgeReferenceStatus.Valid
                    }
                    referenceStates[item.key] = status
                    if (status != BadgeReferenceStatus.Valid || definition == null || award == null) return@mapNotNull null
                    badgeDisplay(definition, award, pubkey)
                }.distinctBy { it.address }
                val unresolved = items.any { it.awardId !in cache || it.address !in byAddress } ||
                    raw.any { it.address?.kind == 30008 && it.address !in byAddress }
                val hasMore = expanded.size > limit
                flow.value = flow.value.copy(badges = badges, references = referenceStates,
                    partial = unresolved || hasMore || pubkey in incomplete || flow.value.loading || flow.value.error != null)
            }
        }
        lock.withLock {
            val evictable = states.keys.filter { it !in consumers && it !in pending && it !in inFlight && it !in activeRefreshes }.toMutableList()
            while (states.size > 1_000 && evictable.isNotEmpty()) {
                val key = evictable.removeAt(0); states.remove(key); fetchedAt.remove(key); failures.remove(key); resolutionLimits.remove(key); incomplete.remove(key)
                setDependencies(key, emptySet())
            }
        }
    }

    private fun expandLocked(profile: NostrEvent, limit: Int): List<BadgeSelectionItem> =
        BadgeSelection.parse(profile.tags).items.flatMap { item ->
            val address = item.address
            if (address?.kind == 30009) listOf(item)
            else if (address?.kind == 30008 && address.pubkey == profile.pubkey) {
                byAddress[address]?.takeUnless(::deletedLocked)?.let {
                    BadgeSelection.parse(it.tags).items.filter { pair -> pair.address?.kind == 30009 }
                }.orEmpty()
            } else emptyList()
        }.take(limit)

    internal fun expand(profile: NostrEvent, limit: Int? = null): List<BadgeSelectionItem> = lock.withLock {
        expandLocked(profile, limit ?: resolutionLimits[profile.pubkey] ?: 100)
    }

    private fun updateState(pubkey: String, transform: (BadgeProfileState) -> BadgeProfileState) = lock.withLock {
        val flow = states.getOrPut(pubkey) { MutableStateFlow(BadgeProfileState()) }
        flow.value = transform(flow.value)
    }

    suspend fun refresh(pubkeys: Set<String>, forceDependencies: Boolean = false): BadgeFetchResult = coroutineScope {
        lock.withLock { pubkeys.forEach { activeRefreshes[it] = (activeRefreshes[it] ?: 0) + 1 } }
        pubkeys.forEach { updateState(it) { state -> state.copy(loading = true, partial = true, error = null) } }
        try {
            val base = readRelays()
            val knownRelays = relaysFor(pubkeys, base = base)
            val changes = Channel<Unit>(Channel.CONFLATED)
            var dependenciesComplete = true
            val resolver = launch {
                var resolved: Pair<List<String>, Set<String>>? = null
                for (change in changes) {
                    val targets = relaysFor(pubkeys, base = base)
                    val selection = pubkeys.sorted().map { profile(it)?.id.orEmpty() } to targets
                    if (selection != resolved) {
                        dependenciesComplete = resolveProfiles(pubkeys, targets, forceDependencies)
                        resolved = selection
                    }
                }
            }
            fun received(event: NostrEvent) {
                ingest(listOf(event))
                changes.trySend(Unit)
            }
            val metadata = async { discoverRelays(pubkeys, base) }
            val filters = listOf(
                NostrFilter(authors = pubkeys.sorted(), kinds = listOf(10008)),
                NostrFilter(authors = pubkeys.sorted(), kinds = listOf(30008), dTags = listOf("profile_badges")),
            )
            // Cached profiles can resolve immediately while fresh selections are still in flight.
            changes.trySend(Unit)
            val initial = async { transport.fetch(filters, knownRelays, ::received) }
            // Outbox relays found by discovery are queried right away, not after the known relays finish.
            val discovery = metadata.await()
            val additional = relaysFor(pubkeys, base = base) - knownRelays
            val extra = if (additional.isEmpty()) BadgeFetchResult(emptyList(), true)
                else transport.fetch(filters, additional, ::received)
            val first = initial.await()
            changes.trySend(Unit)
            changes.close()
            resolver.join()
            val complete = first.complete && extra.complete && discovery.complete && dependenciesComplete
            // Only a selection query that no relay answered is a failure; partial coverage is normal.
            val failed = first.answered.isEmpty() && extra.answered.isEmpty()
            val now = nowMillis()
            lock.withLock {
                pubkeys.forEach {
                    fetchedAt[it] = now
                    failures[it] = if (failed) (failures[it] ?: 0) + 1 else 0
                    if (complete) incomplete.remove(it) else incomplete.add(it)
                }
            }
            pubkeys.forEach { updateState(it) { state -> state.copy(error = if (failed) "バッジを取得できませんでした" else null) } }
            BadgeFetchResult((first.events + extra.events).distinctBy { it.id }, complete, first.answered + extra.answered)
        } finally {
            lock.withLock {
                pubkeys.forEach { key ->
                    val remaining = (activeRefreshes[key] ?: 1) - 1
                    if (remaining == 0) activeRefreshes.remove(key) else activeRefreshes[key] = remaining
                }
            }
            pubkeys.forEach { key -> updateState(key) { state -> state.copy(loading = (activeRefreshes[key] ?: 0) > 0) } }
            recompute(pubkeys)
        }
    }

    internal suspend fun resolveProfiles(pubkeys: Set<String>, relays: Set<String> = readRelays(), force: Boolean = false): Boolean = coroutineScope {
        val profiles = pubkeys.mapNotNull(::profile)
        val setAddresses = profiles.flatMap { profile -> BadgeSelection.parse(profile.tags).items.mapNotNull { it.address }
            .filter { it.kind == 30008 && it.pubkey == profile.pubkey }.take(10) }.distinct()
        val deletion = async { fetchDeletions(profiles, relays, force) }
        val directItems = profiles.flatMap { expand(it) }
        val direct = async { resolveItems(directItems, relays, force) }
        val setResolvers = mutableListOf<Deferred<Boolean>>()
        val resolvedSets = mutableSetOf<String>()
        val sets = queries.fetch(setAddresses.map { address ->
            NostrFilter(kinds = listOf(30008), authors = listOf(address.pubkey), dTags = listOf(address.identifier))
        }, relays, 60_000L, force, onEvent = { event ->
            ingest(listOf(event))
            if (event.badgeAddress() in setAddresses && resolvedSets.add(event.id)) {
                val limit = lock.withLock { resolutionLimits[event.pubkey] ?: 100 }
                val newItems = BadgeSelection.parse(event.tags).items.filter { it.address?.kind == 30009 && it !in directItems }.take(limit)
                setResolvers += async { resolveItems(newItems, relays, force) }
            }
        })
        val setDeletions = async { fetchDeletions(lock.withLock { setAddresses.mapNotNull { byAddress[it] } }, relays, force) }
        val complete = deletion.await() && sets.complete
        val directComplete = direct.await()
        val setsComplete = setResolvers.map { it.await() }.all { it }
        val deletionsComplete = setDeletions.await()
        complete && directComplete && setsComplete && deletionsComplete
    }

    internal suspend fun resolveItems(items: List<BadgeSelectionItem>, relays: Set<String> = readRelays(), force: Boolean = false): Boolean = coroutineScope {
        val addresses = items.mapNotNull { it.address }.filter { it.kind == 30009 }.distinct()
        val issuers = addresses.map { it.pubkey }.toSet()
        val metadata = async { discoverRelays(issuers, relays) }
        val hintRelays = items.mapNotNull { it.tags.getOrNull(1)?.getOrNull(2)?.let(::allowedHint) }.distinct().take(4)
        val targets = relaysFor(issuers, base = relays) + hintRelays
        suspend fun retrieve(target: Set<String>): Boolean = coroutineScope {
            val invalidCachedAward = lock.withLock {
                items.any { item -> item.awardId?.let { cache[it] }?.let { it.pubkey != item.address?.pubkey } == true }
            }
            // Interleave each definition with its award so the first chunk can render complete pairs.
            val referenceFilters = items.flatMap { item ->
                val address = item.address?.takeIf { it.kind == 30009 } ?: return@flatMap emptyList()
                buildList {
                    add(NostrFilter(kinds = listOf(30009), authors = listOf(address.pubkey), dTags = listOf(address.identifier)))
                    item.awardId?.takeIf(::badgeHex)?.let { add(NostrFilter(kinds = listOf(8), ids = listOf(it))) }
                }
            }.distinct()
            val references = async {
                queries.fetch(referenceFilters, target, 300_000L, onEvent = { ingest(listOf(it)) },
                    ttlForFilter = { if (it.kinds == listOf(8)) Long.MAX_VALUE else 300_000L },
                    forceFilter = { if (it.kinds == listOf(8)) invalidCachedAward else force })
            }
            // Referenced IDs and issuer addresses already identify deletion query targets.
            val deletionFilters = addresses.groupBy { it.pubkey }.flatMap { (issuer, definitions) ->
                val awardIds = items.filter { it.address?.pubkey == issuer }.mapNotNull { it.awardId }.distinct()
                buildList {
                    if (awardIds.isNotEmpty()) add(NostrFilter(kinds = listOf(5), authors = listOf(issuer), eTags = awardIds.sorted()))
                    add(NostrFilter(kinds = listOf(5), authors = listOf(issuer), aTags = definitions.map { it.value }.sorted()))
                }
            }
            val deletion = async { queries.fetch(deletionFilters, target, 30_000L, force, onEvent = { ingest(listOf(it)) }) }
            val referenceResult = references.await()
            // Also check deletion by ID for the current definition version.
            val definitionDeletions = async { fetchDeletions(referenceResult.events.filter { it.kind == 30009 }, target, force) }
            val deletionResult = deletion.await()
            val definitionsDeleted = definitionDeletions.await()
            referenceResult.complete && deletionResult.complete && definitionsDeleted
        }
        val first = async { retrieve(targets) }
        val discovery = metadata.await()
        val extra = relaysFor(issuers, base = relays) + hintRelays - targets
        val more = if (extra.isEmpty()) true else retrieve(extra)
        first.await() && more && discovery.complete
    }

    private suspend fun fetchDeletions(relevant: List<NostrEvent>, relays: Set<String>, force: Boolean = false): Boolean {
        val deletionFilters = relevant.groupBy { it.pubkey }.flatMap { (author, events) ->
            buildList {
                add(NostrFilter(kinds = listOf(5), authors = listOf(author), eTags = events.map { it.id }.sorted()))
                val values = events.mapNotNull { it.badgeAddress()?.value }.sorted()
                if (values.isNotEmpty()) add(NostrFilter(kinds = listOf(5), authors = listOf(author), aTags = values))
            }
        }
        return queries.fetch(deletionFilters, relays, 30_000L, force, onEvent = { ingest(listOf(it)) }).complete
    }

    fun close() { worker.cancel(); refreshJob.cancel(); wake.close() }
}

/** Only the displayed list; equal lists are not re-emitted, so the first instance is kept. */
internal fun badgeListOf(state: StateFlow<BadgeProfileState>): Flow<List<BadgeDisplayItem>> =
    state.map { it.badges }.distinctUntilChanged()
