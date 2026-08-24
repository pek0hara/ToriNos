package com.nostr.torinos.ui.thread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadControllerLifecycleTest {
    @Test
    fun closeIsIdempotentBeforeAndAfterSubscriptionStart() {
        val scope = CoroutineScope(Job())
        val controller = ThreadController(
            eventId = "event",
            scope = scope,
            autoStart = false,
        )

        controller.stopSubscriptions()
        controller.close()
        controller.close()

        assertEquals(true, controller.state.value.isLoading)
        scope.coroutineContext[Job]?.cancel()
    }
}
