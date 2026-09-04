package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentWarningTest {
    @Test
    fun detectsContentWarningWithOrWithoutReason() {
        assertTrue(hasContentWarning(listOf(listOf("content-warning"))))
        assertTrue(hasContentWarning(listOf(listOf("content-warning", "暴力的な表現"))))
    }

    @Test
    fun ignoresOtherOrMalformedTags() {
        assertFalse(hasContentWarning(emptyList()))
        assertFalse(hasContentWarning(listOf(emptyList(), listOf("t", "content-warning"))))
    }
}
