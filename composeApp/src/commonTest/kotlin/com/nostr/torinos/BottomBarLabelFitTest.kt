package com.nostr.torinos

import kotlin.test.Test
import kotlin.test.assertEquals

class BottomBarLabelFitTest {
    @Test
    fun keepsMaximumWhenItFits() {
        assertEquals(14.3f, fitFontSize(11f, 14.3f) { true })
    }

    @Test
    fun shrinksToLargestSizeThatFits() {
        assertEquals(12.8f, fitFontSize(11f, 14.3f) { it <= 13f }, absoluteTolerance = 0.001f)
    }

    @Test
    fun stopsAtMinimumWhenNothingFits() {
        assertEquals(11f, fitFontSize(11f, 14.3f) { false })
    }

    @Test
    fun usesSmallSettingAsIsWhenBelowMinimum() {
        var measured = false
        assertEquals(9.35f, fitFontSize(11f, 9.35f) { measured = true; false })
        assertEquals(false, measured)
    }
}
