package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppMessageComposerLogicTest {
    @Test
    fun keyboardIsHiddenWhenAFocusedFieldLosesFocus() {
        assertTrue(
            shouldHideKeyboardOnFocusChange(
                dismissKeyboardOnFocusLoss = true,
                wasFocused = true,
                isFocused = false,
            ),
        )
    }

    @Test
    fun initialUnfocusedNotificationDoesNotTouchTheKeyboard() {
        assertFalse(
            shouldHideKeyboardOnFocusChange(
                dismissKeyboardOnFocusLoss = true,
                wasFocused = false,
                isFocused = false,
            ),
        )
    }

    @Test
    fun gainingFocusNeverHidesTheKeyboard() {
        assertFalse(
            shouldHideKeyboardOnFocusChange(
                dismissKeyboardOnFocusLoss = true,
                wasFocused = false,
                isFocused = true,
            ),
        )
        assertFalse(
            shouldHideKeyboardOnFocusChange(
                dismissKeyboardOnFocusLoss = true,
                wasFocused = true,
                isFocused = true,
            ),
        )
    }

    @Test
    fun composerWithoutTheOptionKeepsTheCurrentFocusBehavior() {
        // チャンネル投稿欄は既定値falseのまま。フォーカスを失ってもキーボードを閉じない。
        assertFalse(
            shouldHideKeyboardOnFocusChange(
                dismissKeyboardOnFocusLoss = false,
                wasFocused = true,
                isFocused = false,
            ),
        )
    }

    @Test
    fun maxLengthAllowsTheLimitAndRejectsOneOver() {
        assertTrue(isWithinMaxLength(length = 800, maxLength = 800))
        assertFalse(isWithinMaxLength(length = 801, maxLength = 800))
        assertTrue(isWithinMaxLength(length = 100_000, maxLength = null))
        assertEquals(true, isWithinMaxLength(length = 0, maxLength = 0))
    }
}
