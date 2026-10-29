package com.nostr.torinos.zap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ZapAmountEditTest {
    @Test
    fun addsAndSorts() {
        val result = addZapAmount(listOf(10, 100), "50")
        assertEquals(listOf(10L, 50L, 100L), assertIs<ZapAmountEdit.Updated>(result).amounts)
    }

    @Test
    fun acceptsSeparators() {
        assertEquals(listOf(10L, 1_000L), assertIs<ZapAmountEdit.Updated>(addZapAmount(listOf(10), " 1,000 ")).amounts)
    }

    @Test
    fun rejectsDuplicateOutOfRangeAndFull() {
        assertIs<ZapAmountEdit.Rejected>(addZapAmount(listOf(10, 100), "100"))
        assertIs<ZapAmountEdit.Rejected>(addZapAmount(listOf(10), "0"))
        assertIs<ZapAmountEdit.Rejected>(addZapAmount(listOf(10), "1000001"))
        assertIs<ZapAmountEdit.Rejected>(addZapAmount(listOf(10), ""))
        assertIs<ZapAmountEdit.Rejected>(addZapAmount(listOf(1, 2, 3, 4, 5, 6), "7"))
    }

    @Test
    fun removeKeepsLastOne() {
        assertEquals(listOf(10L), removeZapAmount(listOf(10, 100), 100))
        assertNull(removeZapAmount(listOf(10), 10))
        assertNull(removeZapAmount(listOf(10, 100), 50))
    }
}
