package com.nostr.torinos.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class TextFilePickResultTest {
    @Test
    fun smallFileIsDecoded() {
        assertEquals(TextFilePickResult.Picked("nsec1"), textFilePickResult(5) { "nsec1" })
    }

    @Test
    fun fileAtTheLimitIsDecoded() {
        assertEquals(TextFilePickResult.Picked("x"), textFilePickResult(MAX_TEXT_FILE_BYTES.toLong()) { "x" })
    }

    @Test
    fun largerFileIsReportedWithoutReading() {
        var read = false
        val result = textFilePickResult(MAX_TEXT_FILE_BYTES + 1L) { read = true; "x" }
        assertEquals(TextFilePickResult.TooLarge, result)
        assertEquals(false, read)
    }

    @Test
    fun readFailureIsReported() {
        assertEquals(TextFilePickResult.Unreadable, textFilePickResult(5) { error("io") })
    }
}
