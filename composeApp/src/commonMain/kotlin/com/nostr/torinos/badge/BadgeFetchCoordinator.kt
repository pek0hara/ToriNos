package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/**
 * Relay coverage is part of the query identity. Complete responses are reusable; partial responses
 * that still carry events are reusable for display but keep `complete = false`, so forced
 * confirmation (e.g. before saving) never treats them as checked.
 */
internal class BadgeFetchCoordinator(
    private val transport: BadgeTransport,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Key(val filter: NostrFilter, val relays: Set<String>)
    private data class Cached(val result: BadgeFetchResult, val fetchedAt: Long)
    private val mutex = Mutex()
    private val referencePermits = Semaphore(2)
    private val confirmationPermits = Semaphore(2)
    private val running = mutableMapOf<Key, CompletableDeferred<BadgeFetchResult>>()
    private val completed = linkedMapOf<Key, Cached>()
    private var cachedEventCount = 0

    // Called while holding mutex.
    private fun removeCached(key: Key) {
        completed.remove(key)?.let { cachedEventCount -= it.result.events.size }
    }

    suspend fun fetch(
        filters: List<NostrFilter>, relays: Set<String>, ttlMillis: Long,
        force: Boolean = false, onEvent: (NostrEvent) -> Unit = {},
        ttlForFilter: (NostrFilter) -> Long = { ttlMillis },
        forceFilter: (NostrFilter) -> Boolean = { force },
    ): BadgeFetchResult {
        if (filters.isEmpty()) return BadgeFetchResult(emptyList(), true)
        val owned = linkedMapOf<Key, CompletableDeferred<BadgeFetchResult>>()
        val waiting = mutex.withLock {
            filters.distinct().map { filter ->
                val key = Key(filter, relays.toSet())
                val ttl = ttlForFilter(filter)
                filter to (running[key] ?: completed[key]?.takeIf {
                    !forceFilter(filter) && now() - it.fetchedAt < if (it.result.events.isEmpty()) minOf(ttl, 30_000L) else ttl
                }?.let { CompletableDeferred(it.result) } ?: CompletableDeferred<BadgeFetchResult>().also {
                    running[key] = it
                    owned[key] = it
                })
            }
        }
        try {
            owned.entries.chunked(30).forEach { batch ->
                // Exact award queries can share one ids filter without changing per-ID cache keys.
                val queries = batch.map { it.key.filter }
                val awards = queries.filter { it.isExactIdLookup() }
                val wireFilters = queries - awards.toSet() + if (awards.isEmpty()) emptyList() else
                    listOf(NostrFilter(kinds = listOf(8), ids = awards.flatMap { it.ids.orEmpty() }.distinct()))
                // Slow metadata/deletion checks must not occupy every slot needed for display data.
                val permits = if (wireFilters.any { it.kinds?.any { it == 8 || it == 30009 || it == 30008 } == true })
                    referencePermits else confirmationPermits
                val result = permits.withPermit { transport.fetch(wireFilters, relays, onEvent) }
                mutex.withLock {
                    batch.forEach { (key, deferred) ->
                        val events = result.events.filter { matchesBadgeFilter(it, key.filter) }
                        // An ID names exactly one immutable event, so finding it on any relay is conclusive.
                        val found = key.filter.isExactIdLookup() && events.map { it.id }.containsAll(key.filter.ids.orEmpty())
                        val response = BadgeFetchResult(events, result.complete || found, result.answered)
                        removeCached(key)
                        if (response.complete || (response.answered.isNotEmpty() && response.events.isNotEmpty())) {
                            completed[key] = Cached(response, now())
                            cachedEventCount += response.events.size
                        }
                        running.remove(key)
                        deferred.complete(response)
                    }
                    while (completed.size > 5_000 || cachedEventCount > 5_000) {
                        removeCached(completed.keys.first())
                    }
                }
            }
            val results = waiting.map { (filter, deferred) ->
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    // Another caller owned this query and was cancelled; that must not cancel us.
                    currentCoroutineContext().ensureActive()
                    if (deferred in owned.values) throw e
                    fetch(listOf(filter), relays, ttlForFilter(filter), forceFilter(filter), onEvent)
                }
            }
            val events = results.flatMap { it.events }.distinctBy { it.id }
            // A joining caller also receives data that arrived before it joined.
            events.forEach(onEvent)
            return BadgeFetchResult(events, results.all { it.complete }, results.map { it.answered }.reduce { a, b -> a intersect b })
        } catch (e: Exception) {
            // Wake every waiter, including queries in chunks not yet opened.
            withContext(NonCancellable) {
                mutex.withLock {
                    owned.forEach { (key, deferred) ->
                        if (running[key] === deferred) running.remove(key)
                        deferred.completeExceptionally(e)
                    }
                }
            }
            throw e
        }
    }
}

internal fun NostrFilter.isExactIdLookup(): Boolean = ids != null && this == NostrFilter(kinds = listOf(8), ids = ids)
