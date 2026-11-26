package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadReplyLabelTest {
    @Test
    fun withoutPreviewShowsTotal() {
        assertEquals("💬 返信 3件 ▸", threadReplyCountLabel(count = 3, previewShown = false))
        assertEquals("💬 返信 1件 ▸", threadReplyCountLabel(count = 1, previewShown = false))
    }

    @Test
    fun withPreviewShowsTheRest() {
        assertEquals("💬 他 3件の返信 ▸", threadReplyCountLabel(count = 4, previewShown = true))
        assertEquals("💬 他 1件の返信 ▸", threadReplyCountLabel(count = 2, previewShown = true))
    }

    @Test
    fun previewOfTheOnlyReplyNeedsNoLabel() {
        assertNull(threadReplyCountLabel(count = 1, previewShown = true))
        // 件数がまだ数えられていなくても、プレビューがあれば出さない。
        assertNull(threadReplyCountLabel(count = 0, previewShown = true))
    }

    @Test
    fun noRepliesNoLabel() {
        assertNull(threadReplyCountLabel(count = 0, previewShown = false))
    }
}
