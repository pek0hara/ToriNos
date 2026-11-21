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
    fun bareBuzzAndNpubLinksBecomeLinks() {
        val npub = "npub1nl8kt3ww0dc53t8023up58scxrhjua3e24ppzjuex56h3jl3zndqfzmkhu"
        val text = "ふだんの雑談は タイムライン (buzz://channel/6405dd9c-0bb0-44cc-bcc7-2a027c380794) の方で。\nnostr:$npub"
        assertEquals(
            listOf(
                MdInline.Text("ふだんの雑談は タイムライン ("),
                MdInline.Link("buzz://channel/6405dd9c-0bb0-44cc-bcc7-2a027c380794", "buzz://channel/6405dd9c-0bb0-44cc-bcc7-2a027c380794"),
                MdInline.Text(") の方で。\n"),
                MdInline.Link("nostr:$npub", "nostr:$npub"),
            ),
            listOf(MdInline.Text(text)).withAutolinks(),
        )
        assertEquals(com.nostr.torinos.model.decodeNpub(npub), npubOfLink("nostr:$npub"))
        assertEquals(null, npubOfLink("https://example.com"))
    }

    @Test
    fun bareLinksUseSameLabelsAsFeed() {
        val npub = "npub1nl8kt3ww0dc53t8023up58scxrhjua3e24ppzjuex56h3jl3zndqfzmkhu"
        val pubkey = com.nostr.torinos.model.decodeNpub(npub)!!
        val buzz = "buzz://channel/6405dd9c-0bb0-44cc-bcc7-2a027c380794"
        assertEquals("💬 Buzz のメッセージ", MdInline.Link(buzz, buzz).displayText())
        assertEquals("タイムライン", MdInline.Link("タイムライン", buzz).displayText())
        assertEquals("@" + pubkey.take(8) + "…" + pubkey.takeLast(8), MdInline.Link(npub, "nostr:$npub").displayText())
        assertEquals("田中", MdInline.Link("田中", "nostr:$npub").displayText())
    }

    @Test
    fun unsafeSchemesStayText() {
        val text = "spotify:search:foo bar"
        assertEquals(listOf(MdInline.Text(text)), listOf(MdInline.Text(text)).withAutolinks())
    }

    @Test
    fun npubInsideWebUrlStaysPartOfUrl() {
        val npub = "npub1nl8kt3ww0dc53t8023up58scxrhjua3e24ppzjuex56h3jl3zndqfzmkhu"
        val url = "https://njump.me/$npub"
        assertEquals(listOf(MdInline.Link(url, url)), listOf(MdInline.Text(url)).withAutolinks())
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
