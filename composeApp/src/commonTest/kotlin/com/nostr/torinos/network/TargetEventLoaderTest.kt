package com.nostr.torinos.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TargetEventLoaderTest {
    @Test fun batchesDeduplicatesAndDoesNotRestartDebounceOrInflightRequests() = runTest {
        val calls = mutableListOf<Set<String>>()
        val gate = CompletableDeferred<Unit>()
        val loader = TargetEventLoader(this, TargetEventFetcher { ids, onEvent ->
            calls.add(ids)
            gate.await()
            ids.forEach { onEvent(targetEvent(it.toInt(16))) }
            EventByIdResult(TargetLoadState.NotFoundInQueriedRelays)
        }, { testScheduler.currentTime })
        loader.request(targetEvent(1).id)
        advanceTimeBy(300)
        loader.request(targetEvent(1).id)
        loader.request(targetEvent(2).id)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, calls.size)
        assertEquals(2, calls.single().size)
        loader.request(targetEvent(1).id)
        loader.request(targetEvent(3).id)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, calls.size)
        assertEquals(setOf(targetEvent(3).id), calls.last())
        assertTrue(loader.states.value.values.all { it is TargetLoadState.Resolved })
    }

    @Test fun limitsParallelismAndPreservesPartialSuccess() = runTest {
        var active = 0
        var maximum = 0
        val sizes = mutableListOf<Int>()
        val loader = TargetEventLoader(this, TargetEventFetcher { ids, onEvent ->
            sizes.add(ids.size)
            active++
            maximum = maxOf(maximum, active)
            delay(100)
            onEvent(targetEvent(ids.first().toInt(16)))
            active--
            EventByIdResult(TargetLoadState.Unavailable(TargetFetchFailure.Incomplete))
        })
        (1..120).forEach { loader.request(targetEvent(it).id) }
        advanceUntilIdle()
        assertEquals(2, maximum)
        assertEquals(listOf(50, 50, 20), sizes)
        assertEquals(3, loader.states.value.values.count { it is TargetLoadState.Resolved })
        assertEquals(117, loader.states.value.values.count { it is TargetLoadState.Unavailable })
    }

    @Test fun failedLookupHasCooldownAndExplicitRetryCanSucceed() = runTest {
        var attempts = 0
        val event = targetEvent(1, 42)
        val loader = TargetEventLoader(this, TargetEventFetcher { _, onEvent ->
            attempts++
            if (attempts > 1) onEvent(event)
            EventByIdResult(TargetLoadState.NotFoundInQueriedRelays)
        }, { testScheduler.currentTime })
        loader.request(event.id)
        advanceUntilIdle()
        loader.request(event.id)
        advanceUntilIdle()
        assertEquals(1, attempts)
        loader.request(event.id, retry = true)
        advanceUntilIdle()
        assertIs<TargetLoadState.Resolved>(loader.states.value[event.id])
    }

    @Test fun stopResetsLoadingAndOldCallbacksCannotAffectNewGeneration() = runTest {
        val event = targetEvent(1)
        var oldCallback: ((com.nostr.torinos.model.NostrEvent) -> Unit)? = null
        val loader = TargetEventLoader(this, TargetEventFetcher { _, onEvent ->
            oldCallback = onEvent
            delay(5_000)
            EventByIdResult(TargetLoadState.NotFoundInQueriedRelays)
        })
        loader.request(event.id)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(TargetLoadState.Loading, loader.states.value[event.id])
        loader.stop()
        oldCallback!!(event)
        assertEquals(TargetLoadState.Idle, loader.states.value[event.id])
        loader.request(event.id)
        advanceUntilIdle()
        assertEquals(TargetLoadState.NotFoundInQueriedRelays, loader.states.value[event.id])
        loader.retain(emptySet())
        assertTrue(loader.states.value.isEmpty())
    }

    @Test fun startupOwnerCanAwaitFullDeadlineAndSeededTargetsNeedNoNetwork() = runTest {
        val event = targetEvent(1)
        val loader = TargetEventLoader(this, TargetEventFetcher { _, _ ->
            delay(5_000)
            EventByIdResult(TargetLoadState.NotFoundInQueriedRelays)
        })
        loader.request(event.id)
        loader.awaitIdle()
        assertEquals(5_400L, testScheduler.currentTime)
        loader.seedValidated(event)
        loader.request(event.id)
        advanceUntilIdle()
        assertEquals(5_400L, testScheduler.currentTime)
        assertIs<TargetLoadState.Resolved>(loader.states.value[event.id])
    }

    @Test fun reopeningAfterCooldownRetriesOnceAndExceptionsBecomeUnavailable() = runTest {
        var attempts = 0
        val event = targetEvent(1)
        val loader = TargetEventLoader(this, TargetEventFetcher { _, _ ->
            attempts++
            error("transport failed")
        }, { testScheduler.currentTime })
        loader.request(event.id)
        advanceUntilIdle()
        assertIs<TargetLoadState.Unavailable>(loader.states.value[event.id])
        advanceTimeBy(30_000)
        loader.request(event.id)
        advanceUntilIdle()
        assertEquals(2, attempts)
        advanceTimeBy(60_000)
        assertEquals(2, attempts) // No polling while the UI stays open.
    }

    @Test fun retainingThenReaddingAnInflightIdDoesNotDuplicateLookup() = runTest {
        val event = targetEvent(1)
        var calls = 0
        val loader = TargetEventLoader(this, TargetEventFetcher { _, onEvent ->
            calls++
            delay(2_000)
            onEvent(event)
            EventByIdResult(TargetLoadState.NotFoundInQueriedRelays)
        })
        loader.request(event.id)
        advanceTimeBy(400)
        runCurrent()
        loader.retain(emptySet())
        loader.request(event.id)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertIs<TargetLoadState.Resolved>(loader.states.value[event.id])
    }
}
