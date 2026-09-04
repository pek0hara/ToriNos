package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/** Owned by a UI scope. All methods and callbacks are confined to that scope's dispatcher. */
class TargetEventLoader(
    private val scope: CoroutineScope,
    private val fetcher: TargetEventFetcher = EventByIdFetcher(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableStates = MutableStateFlow<Map<String, TargetLoadState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val pending = linkedSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private val lastAttempt = mutableMapOf<String, Long>()
    private var worker: Job? = null
    private var generation = 0L

    fun request(id: String, retry: Boolean = false) {
        if (!isFullEventId(id)) return
        val state = states.value[id]
        if (state is TargetLoadState.Resolved || state == TargetLoadState.Loading || id in pending) return
        if (id in inFlight) {
            update(id, TargetLoadState.Loading)
            return
        }
        if (!retry && state != null && state != TargetLoadState.Idle &&
            nowMillis() - (lastAttempt[id] ?: 0) < 30_000) return
        pending.add(id)
        update(id, TargetLoadState.Idle)
        if (worker != null) return
        val owner = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Fixed window: continuous arrivals cannot postpone the first request.
                delay(400)
                while (pending.isNotEmpty()) {
                    val batches = pending.take(100).chunked(50)
                    pending.removeAll(batches.flatten().toSet())
                    coroutineScope {
                        batches.forEach { batch ->
                            launch { fetchBatch(batch.toSet(), owner) }
                        }
                    }
                }
            } finally {
                if (generation == owner) worker = null
            }
        }
        worker = job
        job.start()
    }

    private suspend fun fetchBatch(requestedIds: Set<String>, owner: Long) {
        val ids = requestedIds.filterTo(linkedSetOf()) {
            it in states.value && states.value[it] !is TargetLoadState.Resolved
        }
        if (ids.isEmpty() || generation != owner) return
        inFlight.addAll(ids)
        ids.forEach {
            lastAttempt[it] = nowMillis()
            update(it, TargetLoadState.Loading)
        }
        val result = try {
            fetcher.fetch(ids) { event ->
                if (generation == owner && event.id in ids && event.id in states.value) {
                    update(event.id, TargetLoadState.Resolved(event))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EventByIdResult(TargetLoadState.Unavailable(TargetFetchFailure.Unexpected))
        } finally {
            if (generation == owner) inFlight.removeAll(ids)
        }
        if (generation == owner) ids.forEach { id ->
            if (states.value[id] == TargetLoadState.Loading) update(id, result.missingState)
        }
    }

    /** Only pass events validated by the caller (e.g. a signed embedded repost). */
    fun seedValidated(event: NostrEvent) {
        pending.remove(event.id)
        update(event.id, TargetLoadState.Resolved(event))
    }

    fun retain(ids: Set<String>) {
        pending.retainAll(ids)
        lastAttempt.keys.retainAll(ids)
        mutableStates.value = states.value.filterKeys { it in ids }
    }

    suspend fun awaitIdle() { worker?.join() }

    fun stop() {
        generation++
        worker?.cancel()
        worker = null
        pending.clear()
        inFlight.clear()
        mutableStates.value = states.value.mapValues { (_, value) ->
            if (value == TargetLoadState.Loading) TargetLoadState.Idle else value
        }
    }

    private fun update(id: String, state: TargetLoadState) {
        mutableStates.value = states.value + (id to state)
    }
}
