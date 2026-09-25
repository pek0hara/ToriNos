package com.nostr.torinos.ui.status

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusExpirationTextTest {
    @Test
    fun remainingText_usesLargestWholeUnit() {
        assertEquals("まもなく終了", statusExpirationRemainingText(NOW + 59L, NOW))
        assertEquals("あと1分", statusExpirationRemainingText(NOW + 60L, NOW))
        assertEquals("あと59分", statusExpirationRemainingText(NOW + 3599L, NOW))
        assertEquals("あと9時間", statusExpirationRemainingText(NOW + 9L * 3600L + 59L * 60L, NOW))
        assertEquals("あと47時間", statusExpirationRemainingText(NOW + 2L * 86400L - 1L, NOW))
        assertEquals("あと2日", statusExpirationRemainingText(NOW + 2L * 86400L, NOW))
    }

    @Test
    fun remainingText_showsEndedWhenExpired() {
        assertEquals("終了", statusExpirationRemainingText(NOW, NOW))
        assertEquals("終了", statusExpirationRemainingText(NOW - 10L, NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000L
    }
}
