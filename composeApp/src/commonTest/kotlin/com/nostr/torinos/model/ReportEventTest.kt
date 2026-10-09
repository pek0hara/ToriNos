package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportEventTest {

    private val note = NostrEvent(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = "0".repeat(128),
    )

    @Test
    fun reportNoteTags_putsReportTypeInThirdEntryOfETag() {
        assertEquals(
            listOf(
                listOf("e", note.id, "spam"),
                listOf("p", note.pubkey),
            ),
            reportNoteTags(note, "spam"),
        )
    }
}
