package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupLinkPreviewTest {
    @Test
    fun bareUrlsInTextBecomeLinks() {
        val inlines = listOf(MdInline.Text("ちょっと欲しい https://eiga.com/news/20261115/12/ だね"), MdInline.Bold("太字"))
        assertEquals(
            listOf(
                MdInline.Text("ちょっと欲しい "),
                MdInline.Link("https://eiga.com/news/20261115/12/", "https://eiga.com/news/20261115/12/"),
                MdInline.Text(" だね"),
                MdInline.Bold("太字"),
            ),
            inlines.withAutolinks(),
        )
    }

    @Test
    fun previewUsesFirstWebLinkInBuzzMarkdown() {
        val body = "buzzの自由さ\nhttps://x.com/Hello_World/status/2122023115815285147?a=E4&s=19\n[別](https://example.com)"
        assertEquals("https://x.com/Hello_World/status/2122023115815285147?a=E4&s=19", groupLinkPreviewUrl(body, GroupFlavor.Buzz))
    }

    @Test
    fun previewSkipsImagesBuzzLinksAndUnsafeSchemes() {
        val img = "![a](<https://png.communities.buzz.xyz/media/${"a".repeat(64)}.jpg>)"
        assertEquals(null, groupLinkPreviewUrl(img, GroupFlavor.Buzz))
        assertEquals(null, groupLinkPreviewUrl("[ch](buzz://channel/abc) [x](javascript:alert(1))", GroupFlavor.Buzz))
        assertEquals("https://example.com/page", groupLinkPreviewUrl("https://example.com/a.png https://example.com/page", GroupFlavor.Buzz))
    }

    @Test
    fun standardGroupsUsePlainText() {
        assertEquals("https://example.com/page", groupLinkPreviewUrl("見て https://example.com/page", GroupFlavor.Standard))
        assertEquals(null, groupLinkPreviewUrl("リンクなし", GroupFlavor.Standard))
    }
}
