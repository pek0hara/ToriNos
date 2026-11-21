package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.GroupFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatRelaySummaryTest {
    @Test
    fun buzzUsesSubdomainAsCommunityName() {
        val relay = ChatRelaySummary("wss://png.communities.buzz.xyz", "Buzz Relay", GroupFlavor.Buzz)
        assertEquals("png", relay.label)
        assertEquals("png.communities.buzz.xyz", relay.host)
    }

    @Test
    fun buzzWithoutSubdomainFallsBackToName() {
        assertEquals("Buzz Relay", ChatRelaySummary("wss://buzz.xyz", "Buzz Relay", GroupFlavor.Buzz).label)
        assertNull(buzzCommunityName("buzz.xyz"))
    }

    @Test
    fun standardRelayKeepsNip11Name() {
        assertEquals("やぶみ", ChatRelaySummary("wss://yabu.me", "やぶみ").label)
        assertEquals("groups.0xchat.com", ChatRelaySummary("wss://groups.0xchat.com", null).label)
    }

    @Test
    fun dataUrlIconIsUsedAsIs() {
        val icon = "data:image/png;base64,AAAA"
        assertEquals(icon, ChatRelaySummary("wss://png.communities.buzz.xyz", null, GroupFlavor.Buzz, icon).iconUrl)
        assertEquals("https://yabu.me/favicon.ico", ChatRelaySummary("wss://yabu.me", null).iconUrl)
    }
}
