package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListPreviewTest {
    private val img = "![image-1794814096.jpeg](<https://png.communities.buzz.xyz/media/${"a".repeat(64)}.jpg>)"

    @Test
    fun imageOnlyMessageReadsAsImage() {
        assertEquals("📷 画像", chatListPreview(img))
        assertEquals("📷 画像 2枚", chatListPreview("$img\n\n$img"))
    }

    @Test
    fun textWithImageShowsTheTextWithAMark() {
        assertEquals("📷 作業中", chatListPreview("作業中\n\n$img"))
    }

    @Test
    fun markdownDecorationsAreRemoved() {
        assertEquals("太字 と リンク と code", chatListPreview("**太字** と [リンク](https://example.com) と `code`"))
        // ネタバレは中身を見せない。
        assertEquals("答えは (ネタバレ)", chatListPreview("答えは ||犯人はヤス||"))
    }

    @Test
    fun firstMeaningfulLineIsUsedAndWhitespaceCollapsed() {
        assertEquals("見出し", chatListPreview("\n\n# 見出し\n本文"))
        assertEquals("a b", chatListPreview("a\nb"))
    }

    @Test
    fun videoAndEmpty() {
        assertEquals("🎬 動画", chatListPreview("![clip.mp4](<https://png.communities.buzz.xyz/media/${"b".repeat(64)}.mp4>)"))
        assertEquals(null, chatListPreview("   "))
    }
}
