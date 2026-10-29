package com.nostr.torinos.zap

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LnurlHttpTest {
    @Test
    fun readsBodyUpToLimit() = runTest {
        val body = "{\"tag\":\"payRequest\",\"comment\":\"ありがとう\"}"
        val size = body.encodeToByteArray().size
        assertEquals(body, ByteReadChannel(body.encodeToByteArray()).readTextAtMost(size))
        assertNull(ByteReadChannel(body.encodeToByteArray()).readTextAtMost(size - 1))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stopsReadingOnceLimitIsExceeded() = runTest {
        var written = 0L
        // 終わらない応答。上限を超えたところで読むのをやめなければ、このテストは終わらない。
        val endless = backgroundScope.writer {
            val chunk = ByteArray(1_024) { 'a'.code.toByte() }
            while (true) {
                channel.writeFully(chunk, 0, chunk.size)
                written += chunk.size
            }
        }.channel
        assertNull(endless.readTextAtMost(64 * 1_024))
        assertTrue(written < 1_024 * 1_024, "written=$written")
    }
}
