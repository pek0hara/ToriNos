package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingInviteStoreTest {
    private val link = "https://pek0hara.github.io/ToriNos/app/#join?relay=png.communities.buzz.xyz&code=secret-code"

    @Test
    fun keepsAnInviteUntilTaken() {
        val store = PendingInviteStore()
        assertTrue(store.offer(link, nowMillis = 1_000))
        assertEquals("wss://png.communities.buzz.xyz", store.invite.value?.relayUrl)
        val taken = store.take(nowMillis = 2_000)
        assertEquals(GroupInput.BuzzInvite("wss://png.communities.buzz.xyz", "secret-code"), taken?.let(::parseGroupInput))
        assertNull(store.invite.value)
        assertNull(store.take(nowMillis = 2_000))
    }

    @Test
    fun dropsExpiredInvites() {
        val store = PendingInviteStore()
        store.offer(link, nowMillis = 0)
        assertNull(store.take(nowMillis = PENDING_INVITE_TTL_MILLIS + 1))
        assertNull(store.invite.value)
    }

    @Test
    fun acceptsOnlyInvitesWithACode() {
        val store = PendingInviteStore()
        assertFalse(store.offer("https://pek0hara.github.io/ToriNos/app/#services", nowMillis = 0))
        assertFalse(store.offer("wss://png.communities.buzz.xyz", nowMillis = 0))
        assertFalse(store.offer("naddr1invalid", nowMillis = 0))
        assertNull(store.invite.value)
        assertTrue(store.offer("https://png.communities.buzz.xyz/invite/abc", nowMillis = 0))
    }

    @Test
    fun clearForgetsTheInvite() {
        val store = PendingInviteStore()
        store.offer(link, nowMillis = 0)
        store.clear()
        assertNull(store.take(nowMillis = 0))
    }

    @Test
    fun descriptionNeverContainsTheCode() {
        val store = PendingInviteStore()
        store.offer(link, nowMillis = 0)
        val invite = store.invite.value!!
        assertFalse("secret-code" in invite.toString())
        assertEquals("png.communities.buzz.xyz", invite.host)
    }
}
