package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToriNosInviteLinkTest {
    private val code = "v2." + "Ab_-".repeat(10) + "xyz"
    private val relay = "wss://png.communities.buzz.xyz"

    @Test
    fun webLinkCarriesHostAndCodeInTheFragment() {
        val link = toriNosInviteLink(relay, code)
        assertEquals("https://pek0hara.github.io/ToriNos/app/#join?relay=png.communities.buzz.xyz&code=$code", link)
        assertEquals(GroupInput.BuzzInvite(relay, code), parseGroupInput(link))
    }

    @Test
    fun appLinkUsesTheSameQuery() {
        val link = toriNosAppInviteLink(relay, code)
        assertEquals("torinos://join?relay=png.communities.buzz.xyz&code=$code", link)
        assertEquals(GroupInput.BuzzInvite(relay, code), parseGroupInput(link))
    }

    @Test
    fun linkRoundTripsPortsAndEscapedCodes() {
        val local = "ws://localhost:3000"
        val odd = "a b&c=d"
        val link = toriNosInviteLink(local, odd)
        assertTrue("a%20b%26c%3Dd" in link || "a+b%26c%3Dd" in link, link)
        assertEquals(GroupInput.BuzzInvite(local, odd), parseGroupInput(link))
        assertEquals(GroupInput.BuzzInvite("wss://example.com:8443", "c"), parseGroupInput(toriNosInviteLink("wss://example.com:8443", "c")))
    }

    @Test
    fun acceptsPathVariantsOfTheWebApp() {
        val query = "join?relay=png.communities.buzz.xyz&code=abc"
        val expected = GroupInput.BuzzInvite(relay, "abc")
        assertEquals(expected, parseGroupInput("https://pek0hara.github.io/ToriNos/app#$query"))
        assertEquals(expected, parseGroupInput("https://PEK0HARA.github.io/ToriNos/app/index.html#$query"))
        assertEquals(expected, parseGroupInput("https://pek0hara.github.io/ToriNos/join#$query"))
        assertEquals(expected, parseGroupInput("https://pek0hara.github.io/ToriNos/join/#$query"))
        assertEquals(expected, parseGroupInput("torinos://join/?relay=png.communities.buzz.xyz&code=abc"))
    }

    @Test
    fun rejectsMalformedOrForeignLinks() {
        listOf(
            // コードかリレーが無い、重複している
            "https://pek0hara.github.io/ToriNos/app/#join?relay=png.communities.buzz.xyz",
            "https://pek0hara.github.io/ToriNos/app/#join?code=abc",
            "https://pek0hara.github.io/ToriNos/app/#join?relay=a.example.com&relay=b.example.com&code=abc",
            "https://pek0hara.github.io/ToriNos/app/#join?relay=png.communities.buzz.xyz&code=abc&code=def",
            // コードがクエリ側にある（サーバーやログに出る形は受け付けない）
            "https://pek0hara.github.io/ToriNos/app/?code=abc#join?relay=png.communities.buzz.xyz",
            // 別のホスト・http・ユーザー情報付き
            "https://evil.example.com/ToriNos/app/#join?relay=png.communities.buzz.xyz&code=abc",
            "http://pek0hara.github.io/ToriNos/app/#join?relay=png.communities.buzz.xyz&code=abc",
            "https://user@pek0hara.github.io/ToriNos/app/#join?relay=png.communities.buzz.xyz&code=abc",
            // リレーにパスや非 ASCII のホスト
            "https://pek0hara.github.io/ToriNos/app/#join?relay=https://png.communities.buzz.xyz/x&code=abc",
            "https://pek0hara.github.io/ToriNos/app/#join?relay=pаypal.com&code=abc",
            // 知らない動作
            "torinos://import?relay=png.communities.buzz.xyz&code=abc",
            "https://pek0hara.github.io/ToriNos/app/#transfer?relay=png.communities.buzz.xyz&code=abc",
            // 長すぎるコード
            "torinos://join?relay=png.communities.buzz.xyz&code=" + "a".repeat(257),
        ).forEach { assertEquals(GroupInput.Invalid, parseGroupInput(it), it) }
    }

    @Test
    fun ordinaryWebAppLinksAreNotInvites() {
        assertFalse(isToriNosInviteLink("https://pek0hara.github.io/ToriNos/app/#services"))
        assertTrue(isToriNosInviteLink("https://pek0hara.github.io/ToriNos/app/#join?relay=a.example.com&code=b"))
        assertTrue(isToriNosInviteLink("torinos://join?relay=a.example.com&code=b"))
    }

    @Test
    fun fragmentAloneIsParsedForTheWebEntry() {
        assertEquals(GroupInput.BuzzInvite(relay, "abc"), inviteFromFragment("#join?relay=png.communities.buzz.xyz&code=abc"))
        assertEquals(GroupInput.BuzzInvite(relay, "abc"), inviteFromFragment("join?relay=png.communities.buzz.xyz&code=abc"))
        assertNull(inviteFromFragment("#services"))
        assertNull(inviteFromFragment("#join?relay=png.communities.buzz.xyz"))
    }

    @Test
    fun mintedInviteBecomesAToriNosLink() {
        val minted = MintedInvite(code, "https://png.communities.buzz.xyz/invite/$code", 0, null, null)
        assertEquals("torinos://join?relay=png.communities.buzz.xyz&code=$code", toriNosInviteLinkFor(minted))
    }
}
