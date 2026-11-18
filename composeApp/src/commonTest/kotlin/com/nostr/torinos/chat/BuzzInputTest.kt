package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class BuzzInputTest {
    private val code = "v2." + "A".repeat(43)

    @Test
    fun invitePageUrlCarriesCommunityAndCode() {
        assertEquals(
            GroupInput.BuzzInvite("wss://png.communities.buzz.xyz", code),
            parseGroupInput("https://png.communities.buzz.xyz/invite/$code"),
        )
    }

    @Test
    fun appInviteLinkCarriesCommunityAndCode() {
        assertEquals(
            GroupInput.BuzzInvite("wss://png.communities.buzz.xyz", "abc"),
            parseGroupInput("buzz://join?relay=https://png.communities.buzz.xyz&code=abc"),
        )
        assertEquals(
            GroupInput.BuzzInvite("wss://png.communities.buzz.xyz", "abc"),
            parseGroupInput("buzz://join/?relay=https%3A%2F%2Fpng.communities.buzz.xyz&code=abc"),
        )
    }

    @Test
    fun codeLessLinksOpenTheCommunity() {
        assertEquals(GroupInput.Relay("wss://png.communities.buzz.xyz"), parseGroupInput("buzz://connect?relay=https://png.communities.buzz.xyz"))
        assertEquals(GroupInput.Relay("wss://png.communities.buzz.xyz"), parseGroupInput("buzz://add-community?relay=png.communities.buzz.xyz"))
    }

    @Test
    fun bareCodeAsksForTheWholeLink() {
        assertEquals(GroupInput.BareBuzzCode, parseGroupInput(code))
    }

    @Test
    fun malformedInvitesAreInvalid() {
        assertEquals(GroupInput.Invalid, parseGroupInput("buzz://join?relay=https://a.example"))
        assertEquals(GroupInput.Invalid, parseGroupInput("buzz://join?code=abc"))
        assertEquals(GroupInput.Invalid, parseGroupInput("http://a.example/invite/abc"))
        assertEquals(GroupInput.Invalid, parseGroupInput("https://a.example/invite/abc?x=1"))
    }

    @Test
    fun buzzRelayIsDetectedFromExtensions() {
        assertEquals(GroupFlavor.Buzz, groupFlavorOf(listOf("nip-cl", "buzz-gif")))
        assertEquals(GroupFlavor.Standard, groupFlavorOf(listOf("nip-cl")))
        assertEquals(GroupFlavor.Standard, groupFlavorOf(emptyList()))
    }

    @Test
    fun buzzMessageLinksAreParsed() {
        val id = "a".repeat(64)
        val root = "b".repeat(64)
        assertEquals(
            BuzzLink.Message("c-1", id, root),
            parseBuzzLink("buzz://message?channel=c-1&id=$id&thread=$root"),
        )
        assertEquals(BuzzLink.Message("c-1", id, null), parseBuzzLink("buzz://channel/c-1/$id"))
        assertEquals(BuzzLink.Channel("c-1"), parseBuzzLink("buzz://channel/c-1"))
        assertEquals(null, parseBuzzLink("buzz://plugin?id=x"))
        assertEquals(null, parseBuzzLink("buzz://message?channel=c-1&id=nothex"))
    }
}
