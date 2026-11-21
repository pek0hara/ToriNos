package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TextLinksTest {
    private val npub = "npub1nl8kt3ww0dc53t8023up58scxrhjua3e24ppzjuex56h3jl3zndqfzmkhu"

    @Test
    fun findsLinksInOrderWithoutOverlap() {
        val text = "見て https://njump.me/$npub と nostr:$npub と (buzz://channel/abc) #tag"
        val links = findTextLinks(text, hashtags = true)
        assertEquals(
            listOf("Uri", "Profile", "Group", "Hashtag"),
            links.map { it::class.simpleName },
        )
        assertEquals("https://njump.me/$npub", (links[0] as TextLink.Uri).uri)
        assertEquals(npub, (links[1] as TextLink.Profile).npub)
        assertEquals("buzz://channel/abc", (links[2] as TextLink.Group).uri)
    }

    @Test
    fun optionsTurnKindsOff() {
        val text = "https://example.com nostr:$npub buzz://channel/abc #tag"
        assertEquals(emptyList(), findTextLinks(text, web = false, profiles = false, groups = false, hashtags = false))
    }

    @Test
    fun labels() {
        assertEquals("💬 Buzz のメッセージ", groupLinkLabel("buzz://message?channel=a&id=b"))
        assertEquals("💬 グループへのリンク", groupLinkLabel("nostr:naddr1xyz"))
        assertEquals("@アリス", profileLinkLabel("0123456789abcdef0123", "アリス"))
        assertEquals("@01234567…cdef0123", profileLinkLabel("0123456789abcdef0123", " "))
        assertEquals(true, isGroupLinkUri("buzz://channel/abc"))
        assertEquals(false, isGroupLinkUri("buzz://channel/abc と"))
        assertEquals(false, isGroupLinkUri("https://example.com"))
    }
}
