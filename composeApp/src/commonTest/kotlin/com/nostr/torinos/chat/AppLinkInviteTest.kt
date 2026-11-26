package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OS から渡されたリンク（torinos://join）を預かる（docs/buzz-invite-link-design.md 5.2）。 */
class AppLinkInviteTest {
    @Test
    fun keepsAnInviteFromTheAppScheme() {
        val store = PendingInviteStore()
        assertTrue(store.offerAppLink("torinos://join?relay=png.communities.buzz.xyz&code=secret-code", nowMillis = 0))
        val taken = store.take(nowMillis = 0)?.let(::parseGroupInput)
        assertEquals(GroupInput.BuzzInvite("wss://png.communities.buzz.xyz", "secret-code"), taken)
    }

    @Test
    fun acceptsTheWebInviteLinkToo() {
        // 段階3（Universal Links / App Links）では Web 版の URL のまま渡される。
        val store = PendingInviteStore()
        assertTrue(store.offerAppLink("https://pek0hara.github.io/ToriNos/join#join?relay=a.example.com&code=c", nowMillis = 0))
    }

    @Test
    fun ignoresLinksThatAreNotToriNosInvites() {
        val store = PendingInviteStore()
        listOf(
            "torinos://",
            "torinos://import?relay=a.example.com&code=c",
            "torinos://join?relay=a.example.com",
            // ほかのアプリの招待やリレーは、OS からの受け口では受け取らない（貼り付けなら今どおり受け付ける）。
            "buzz://join?relay=a.example.com&code=c",
            "https://png.communities.buzz.xyz/invite/abc",
            "wss://a.example.com",
        ).forEach { assertFalse(store.offerAppLink(it, nowMillis = 0), it) }
        assertNull(store.invite.value)
    }

    @Test
    fun androidIntentLinkOpensOnlyToriNos() {
        assertEquals(
            "intent://join?relay=png.communities.buzz.xyz&code=a%2Bb#Intent;scheme=torinos;package=com.nostr.torinos;end",
            toriNosAndroidIntentLink("wss://png.communities.buzz.xyz", "a+b"),
        )
    }

    @Test
    fun appLinkFromTheWebFragment() {
        assertEquals(
            "torinos://join?relay=a.example.com&code=c",
            inviteFromFragment("#join?relay=a.example.com&code=c")?.let { toriNosAppInviteLink(it.relayUrl, it.code) },
        )
    }
}
