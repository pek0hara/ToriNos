package com.nostr.torinos.ui.post

import com.nostr.torinos.emoji.CustomEmoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeNoteContentTest {
    @Test
    fun blankTextWithoutAttachmentsIsNothingToPost() {
        assertNull(composeNoteContent("  \n ", emptyList(), emptyList()))
    }

    @Test
    fun uploadedImagesAreAppendedAndPendingOnesIgnored() {
        val images = listOf(
            ImageAttachment(id = 0, previewBytes = null, uploadedUrl = "https://img/a.png", isUploading = false),
            ImageAttachment(id = 1, previewBytes = null, uploadedUrl = null, isUploading = true),
        )
        val composed = composeNoteContent(" hello ", images, emptyList())!!
        assertEquals("hello\nhttps://img/a.png", composed.content)
    }

    @Test
    fun customEmojiTagsAreDerivedFromTheFinalContent() {
        val composed = composeNoteContent(
            "hi :wave:",
            emptyList(),
            listOf(CustomEmoji("wave", "https://e/wave.png"), CustomEmoji("unused", "https://e/u.png")),
        )!!
        assertTrue(listOf("emoji", "wave", "https://e/wave.png") in composed.tags)
        assertTrue(composed.tags.none { it.getOrNull(1) == "unused" })
        // client タグや返信タグは呼び出し側が付ける。
        assertTrue(composed.tags.none { it.firstOrNull() == "client" || it.firstOrNull() == "e" })
    }
}
