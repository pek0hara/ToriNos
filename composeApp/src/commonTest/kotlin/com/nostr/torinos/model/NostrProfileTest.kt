package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NostrProfileTest {

    private fun makeEvent(content: String) = NostrEvent(
        id = "0".repeat(64),
        pubkey = "0".repeat(64),
        createdAt = 0L,
        kind = 0,
        tags = emptyList(),
        content = content,
        sig = "0".repeat(128),
    )

    // ---- toProfile ----

    @Test
    fun toProfile_fullJson_parsesAllFields() {
        val event = makeEvent(
            """{"name":"alice","display_name":"Alice A","picture":"https://example.com/pic.jpg","banner":"https://example.com/banner.jpg","about":"bio","nip05":"alice@example.com"}"""
        )
        val profile = event.toProfile()
        assertEquals("alice", profile?.name)
        assertEquals("Alice A", profile?.displayName)
        assertEquals("https://example.com/pic.jpg", profile?.picture)
        assertEquals("https://example.com/banner.jpg", profile?.banner)
        assertEquals("bio", profile?.about)
        assertEquals("alice@example.com", profile?.nip05)
    }

    @Test
    fun toProfile_partialJson_missingFieldsAreNull() {
        val event = makeEvent("""{"name":"bob"}""")
        val profile = event.toProfile()
        assertEquals("bob", profile?.name)
        assertNull(profile?.displayName)
        assertNull(profile?.picture)
        assertNull(profile?.banner)
        assertNull(profile?.about)
        assertNull(profile?.nip05)
    }

    @Test
    fun toProfile_unknownFields_ignored() {
        val event = makeEvent("""{"name":"carol","unknown_field":"value"}""")
        val profile = event.toProfile()
        assertEquals("carol", profile?.name)
    }

    @Test
    fun toProfile_emptyJson_returnsDefaultProfile() {
        val profile = makeEvent("{}").toProfile()
        assertNull(profile?.name)
        assertNull(profile?.displayName)
    }

    @Test
    fun toProfile_invalidJson_returnsNull() {
        assertNull(makeEvent("not json").toProfile())
    }

    @Test
    fun toProfile_emptyContent_returnsNull() {
        assertNull(makeEvent("").toProfile())
    }

    @Test
    fun toProfile_wrongTypedLud16_onlyThatFieldIsDropped() {
        // NIP-01 の置き換えでは新しい版が有効。型の違う項目のために版ごと捨てると、前の版（古い受け取り先）が残る。
        listOf(
            "[\"a@a.example\",\"b@b.example\"]",
            "123",
            "false",
            "{\"a\":\"b\"}",
        ).forEach { value ->
            val profile = makeEvent("""{"name":"alice","about":"bio","lud16":$value}""").toProfile()
            assertEquals("alice", profile?.name, value)
            assertEquals("bio", profile?.about, value)
            assertNull(profile?.lud16, value)
        }
    }

    @Test
    fun toProfile_wrongTypedName_keepsOtherFields() {
        val profile = makeEvent("""{"name":123,"display_name":["x"],"about":"bio","lud16":"a@example.com"}""").toProfile()
        assertNull(profile?.name)
        assertNull(profile?.displayName)
        assertEquals("bio", profile?.about)
        assertEquals("a@example.com", profile?.lud16)
    }

    @Test
    fun toProfile_nullFieldIsAbsent() {
        val profile = makeEvent("""{"name":"alice","lud16":null}""").toProfile()
        assertEquals("alice", profile?.name)
        assertNull(profile?.lud16)
    }

    @Test
    fun toProfile_nonObjectJson_returnsNull() {
        listOf("[]", "\"alice\"", "123", "null").forEach { assertNull(makeEvent(it).toProfile(), it) }
    }

    @Test
    fun toProfile_customEmojisComeOnlyFromTags() {
        val event = makeEvent("""{"name":"alice","customEmojis":{"x":"https://evil.example/x.png"}}""")
            .copy(tags = listOf(listOf("emoji", "wave", "https://example.com/wave.png")))
        assertEquals(mapOf("wave" to "https://example.com/wave.png"), event.toProfile()?.customEmojis)
    }

    // ---- bestName ----

    @Test
    fun bestName_prefersDisplayName() {
        val profile = NostrProfile(name = "alice", displayName = "Alice A")
        assertEquals("Alice A", profile.bestName)
    }

    @Test
    fun bestName_fallsBackToName_whenDisplayNameBlank() {
        val profile = NostrProfile(name = "alice", displayName = "  ")
        assertEquals("alice", profile.bestName)
    }

    @Test
    fun bestName_fallsBackToName_whenDisplayNameNull() {
        val profile = NostrProfile(name = "alice", displayName = null)
        assertEquals("alice", profile.bestName)
    }

    @Test
    fun bestName_isNull_whenBothBlank() {
        val profile = NostrProfile(name = "", displayName = "")
        assertNull(profile.bestName)
    }

    @Test
    fun bestName_isNull_whenBothNull() {
        val profile = NostrProfile()
        assertNull(profile.bestName)
    }

    @Test
    fun bestName_isNull_whenNameBlankAndDisplayNameNull() {
        val profile = NostrProfile(name = "  ", displayName = null)
        assertNull(profile.bestName)
    }
}
