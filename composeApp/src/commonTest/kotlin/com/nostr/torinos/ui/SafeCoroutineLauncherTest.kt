package com.nostr.torinos.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeCoroutineLauncherTest {
    @Test
    fun reportsOrdinaryExceptionsWithoutCancellingTheParentScope(): Unit = runBlocking {
        val parentJob = Job()
        val errors = mutableListOf<Throwable>()
        val launcher = SafeCoroutineLauncher(CoroutineScope(coroutineContext + parentJob), "Test") {
            errors += it
        }

        launcher.launch { throw TestException() }.join()

        assertEquals(1, errors.size)
        assertTrue(errors.single() is TestException)
        assertTrue(parentJob.isActive)
        parentJob.cancel()
    }

    @Test
    fun propagatesCancellationWithoutReportingIt(): Unit = runBlocking {
        val parentJob = Job()
        val errors = mutableListOf<Throwable>()
        val started = CompletableDeferred<Unit>()
        val launcher = SafeCoroutineLauncher(CoroutineScope(coroutineContext + parentJob), "Test") {
            errors += it
        }
        val job = launcher.launch {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(errors.isEmpty())
        parentJob.cancel()
    }

    @Test
    fun reportingFailureDoesNotEscapeTheLauncher(): Unit = runBlocking {
        val parentJob = Job()
        val launcher = SafeCoroutineLauncher(CoroutineScope(coroutineContext + parentJob), "Test") {
            throw ReportingException()
        }

        launcher.launch { throw TestException() }.join()

        assertTrue(parentJob.isActive)
        parentJob.cancel()
    }

    private class TestException : RuntimeException()
    private class ReportingException : RuntimeException()
}
