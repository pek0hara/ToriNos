package com.nostr.torinos.chat

import com.nostr.torinos.model.encodeNaddr
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupLinkExtractionTest {
    private val relayKey = "c".repeat(64)

    @Test
    fun groupNaddrAndBuzzLinksAreFound() {
        val naddr = encodeNaddr(GROUP_METADATA_KIND, relayKey, "g1", listOf("wss://g.example"))
        val article = encodeNaddr(30023, relayKey, "a", listOf("wss://g.example"))
        val id = "a".repeat(64)
        val text = "参加してね nostr:$naddr と buzz://message?channel=c1&id=$id 記事 nostr:$article"

        val links = extractGroupLinks(text)

        assertEquals(listOf("nostr:$naddr", "buzz://message?channel=c1&id=$id"), links.map { it.uri })
        assertEquals(text.indexOf("nostr:$naddr"), links[0].start)
        assertEquals(text.indexOf(" 記事"), links[1].endExclusive)
    }

    @Test
    fun otherBuzzLinksAreIgnored() {
        assertEquals(emptyList(), extractGroupLinks("buzz://plugin?id=x buzz://settings"))
    }

    @Test
    fun inviteSuffixStaysWithTheNaddr() {
        val naddr = encodeNaddr(GROUP_METADATA_KIND, relayKey, "g1", listOf("wss://g.example"))
        assertEquals(listOf("nostr:$naddr?invite=abc"), extractGroupLinks("nostr:$naddr?invite=abc。").map { it.uri })
    }

    @Test
    fun buzzLinkFollowedByJapaneseTextIsFound() {
        val id = "a".repeat(64)
        val text = "見て buzz://message?channel=c1&id=${id}だよ"
        assertEquals(listOf("buzz://message?channel=c1&id=$id"), extractGroupLinks(text).map { it.uri })
    }

    @Test
    fun onlySafeSchemesAreOpenedFromMessages() {
        assertEquals(true, isSafeExternalLink("https://a.example/x"))
        assertEquals(true, isSafeExternalLink("HTTP://a.example"))
        assertEquals(true, isSafeExternalLink("mailto:a@example.com"))
        assertEquals(false, isSafeExternalLink("javascript:alert(1)"))
        assertEquals(false, isSafeExternalLink("intent://scan#Intent;end"))
        assertEquals(false, isSafeExternalLink("file:///etc/passwd"))
    }
}
