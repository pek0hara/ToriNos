package com.nostr.torinos.chat

import com.nostr.torinos.model.encodeNaddr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GroupInputTest {
    private val relayKey = "c".repeat(64)

    @Test
    fun naddrOfGroupMetadataOpensTheGroup() {
        val naddr = encodeNaddr(GROUP_METADATA_KIND, relayKey, "abc123", listOf("wss://Groups.Example.com/"))

        val input = parseGroupInput(naddr)

        assertEquals(
            GroupInput.Group(GroupRef("wss://groups.example.com", "abc123"), inviteCode = null, relayPubkey = relayKey),
            input,
        )
    }

    @Test
    fun naddrWithNostrPrefixAndInviteSuffix() {
        val naddr = encodeNaddr(GROUP_METADATA_KIND, relayKey, "abc", listOf("wss://groups.example.com"))

        val input = parseGroupInput("  nostr:$naddr?invite=secret-code ")

        assertIs<GroupInput.Group>(input)
        assertEquals("secret-code", input.inviteCode)
    }

    @Test
    fun naddrOfAnotherKindOrWithoutRelayIsInvalid() {
        assertEquals(
            GroupInput.Invalid,
            parseGroupInput(encodeNaddr(30023, relayKey, "abc", listOf("wss://groups.example.com"))),
        )
        assertEquals(GroupInput.Invalid, parseGroupInput(encodeNaddr(GROUP_METADATA_KIND, relayKey, "abc")))
        assertEquals(
            GroupInput.Invalid,
            parseGroupInput(encodeNaddr(GROUP_METADATA_KIND, relayKey, "", listOf("wss://groups.example.com"))),
        )
    }

    @Test
    fun hostApostropheIdOpensTheGroup() {
        assertEquals(
            GroupInput.Group(GroupRef("wss://groups.example.com", "nostr-jp"), null, null),
            parseGroupInput("groups.example.com'nostr-jp"),
        )
        assertEquals(
            GroupInput.Group(GroupRef("wss://groups.example.com:4443", "x"), null, null),
            parseGroupInput("wss://groups.example.com:4443'x"),
        )
    }

    @Test
    fun hostApostropheWithoutIdIsInvalid() {
        assertEquals(GroupInput.Invalid, parseGroupInput("groups.example.com'"))
        assertEquals(GroupInput.Invalid, parseGroupInput("groups.example.com'a b"))
    }

    @Test
    fun relayAddressesOpenTheRelay() {
        assertEquals(GroupInput.Relay("wss://groups.example.com"), parseGroupInput("wss://groups.example.com/"))
        assertEquals(GroupInput.Relay("wss://groups.example.com"), parseGroupInput("Groups.Example.com"))
        assertEquals(GroupInput.Relay("wss://groups.example.com"), parseGroupInput("https://groups.example.com"))
        assertEquals(GroupInput.Relay("ws://localhost:7777"), parseGroupInput("ws://localhost:7777"))
    }

    @Test
    fun otherTextIsInvalid() {
        assertEquals(GroupInput.Invalid, parseGroupInput(""))
        assertEquals(GroupInput.Invalid, parseGroupInput("hello world"))
        assertEquals(GroupInput.Invalid, parseGroupInput("localhost"))
        assertEquals(GroupInput.Invalid, parseGroupInput("https://groups.example.com/other/abc"))
        assertEquals(GroupInput.Invalid, parseGroupInput("naddr1invalid"))
    }

    @Test
    fun groupLinkUsesNaddrWithRelayKey() {
        val ref = GroupRef("wss://groups.example.com", "abc")
        val link = groupShareLink(ref, relayKey)

        assertEquals(GroupInput.Group(ref, null, relayKey), parseGroupInput(link))
    }

    @Test
    fun describesWhatTheInputWasReadAs() {
        // 貼った直前に「何として読めたか」と行き先を見せる。読めないものは何も出さない。
        assertEquals(
            "Buzz の招待 · png.communities.buzz.xyz",
            describeGroupInput(GroupInput.BuzzInvite("wss://png.communities.buzz.xyz", "abc")),
        )
        assertEquals(
            "グループ · groups.example.com",
            describeGroupInput(GroupInput.Group(GroupRef("wss://groups.example.com", "g1"), inviteCode = null, relayPubkey = null)),
        )
        assertEquals(
            "グループ（招待コードあり） · groups.example.com",
            describeGroupInput(GroupInput.Group(GroupRef("wss://groups.example.com", "g1"), inviteCode = "x", relayPubkey = null)),
        )
        assertEquals("リレーのグループ一覧 · relay.example.com", describeGroupInput(GroupInput.Relay("wss://relay.example.com")))
        assertEquals(null, describeGroupInput(GroupInput.Invalid))
        assertEquals(null, describeGroupInput(GroupInput.BareBuzzCode))
    }
}
