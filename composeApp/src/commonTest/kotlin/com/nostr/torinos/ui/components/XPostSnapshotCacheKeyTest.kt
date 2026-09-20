package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertNotEquals

class XPostSnapshotCacheKeyTest {
    @Test
    fun displayWidthParticipatesInCacheIdentity() {
        val portrait = XPostSnapshotCacheKey(
            postId = "123",
            darkTheme = false,
            widthPx = 720,
        )
        val landscape = portrait.copy(widthPx = 1_080)

        assertNotEquals(portrait, landscape)
    }
}
