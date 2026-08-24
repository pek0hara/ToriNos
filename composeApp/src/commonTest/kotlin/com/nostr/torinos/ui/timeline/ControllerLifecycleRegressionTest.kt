package com.nostr.torinos.ui.timeline

import com.nostr.torinos.ui.channel.ChannelController
import com.nostr.torinos.ui.feed.FeedController
import com.nostr.torinos.ui.post.JournalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertNotNull

class ControllerLifecycleRegressionTest {
    @Test
    fun everyScreenControllerCanCloseRepeatedlyWithoutOwningANewScope() {
        val parentJob = Job()
        val scope = CoroutineScope(parentJob)
        val feed = FeedController(autoStart = false, scope = scope)
        val channel = ChannelController(channelId = "channel", scope = scope, autoStart = false)
        val journal = JournalController(scope = scope)

        repeat(2) {
            feed.close()
            channel.close()
            journal.close()
        }

        assertNotNull(feed.state.value)
        assertNotNull(channel.state.value)
        assertNotNull(journal.state.value)
        parentJob.cancel()
    }
}
