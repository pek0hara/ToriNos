package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListTouchKeyboardDismissalTest {
    @Test
    fun closesWhenTheUserDragsTheList() {
        val dismissal = ListTouchKeyboardDismissal()
        assertTrue(dismissal.onScroll(fromUser = true, delta = -12f))
    }

    @Test
    fun closesForEitherDirection() {
        val dismissal = ListTouchKeyboardDismissal()
        assertTrue(dismissal.onScroll(fromUser = true, delta = 12f))
    }

    @Test
    fun doesNotCloseForScrollsNotMadeByTheUser() {
        // 新着での自動スクロールや慣性スクロールでは閉じない。
        val dismissal = ListTouchKeyboardDismissal()
        assertFalse(dismissal.onScroll(fromUser = false, delta = -40f))
    }

    @Test
    fun doesNotCloseWithoutMovement() {
        val dismissal = ListTouchKeyboardDismissal()
        assertFalse(dismissal.onScroll(fromUser = true, delta = 0f))
    }

    @Test
    fun closesOnlyOncePerDrag() {
        val dismissal = ListTouchKeyboardDismissal()
        assertTrue(dismissal.onScroll(fromUser = true, delta = -5f))
        assertFalse(dismissal.onScroll(fromUser = true, delta = -5f))
        assertFalse(dismissal.onScroll(fromUser = true, delta = 5f))
    }

    @Test
    fun closesAgainOnTheNextDrag() {
        // 指を離した後にキーボードを開き直し、もう一度一覧を動かしたらまた閉じる。
        val dismissal = ListTouchKeyboardDismissal()
        assertTrue(dismissal.onScroll(fromUser = true, delta = -5f))
        dismissal.onDragEnd()
        assertTrue(dismissal.onScroll(fromUser = true, delta = -5f))
    }
}
