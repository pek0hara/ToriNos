package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicGroupDirectoryTest {
    private val relayKey = "c".repeat(64)
    private val relay = "wss://groups.example.com"
    private var n = 0

    private fun meta(id: String, name: String, vararg flags: String, about: String = "", pubkey: String = relayKey, createdAt: Long = 10) =
        NostrEvent(
            (++n).toString().padStart(64, '0'), pubkey, createdAt, GROUP_METADATA_KIND,
            listOf(listOf("d", id), listOf("name", name), listOf("about", about)) + flags.map { listOf(it) },
            "", "sig",
        )

    @Test
    fun onlyNamedPublicGroupsSignedByTheRelayAreListed() {
        val entries = publicGroupEntries(
            relay,
            listOf(
                meta("open", "Open"),
                meta("priv", "Private", "private"),
                meta("hid", "Hidden", "hidden"),
                meta("noname", ""),
                meta("forged", "Forged", pubkey = "d".repeat(64)),
            ),
            relayKey,
        )
        assertEquals(listOf("open"), entries.map { it.ref.groupId })
        assertEquals(GroupRef(relay, "open"), entries.single().ref)
    }

    @Test
    fun latestMetadataWins() {
        val entries = publicGroupEntries(relay, listOf(meta("g", "Old", createdAt = 1), meta("g", "New", "private", createdAt = 2)), relayKey)
        assertEquals(emptyList(), entries)
    }

    @Test
    fun searchMatchesNameAboutAndIdIgnoringCaseAndSkipsJoined() {
        val entries = publicGroupEntries(
            relay,
            listOf(meta("bitcoin-walk", "Walk"), meta("g2", "Nostr Help", about = "Questions about BITCOIN"), meta("g3", "Cooking")),
            relayKey,
        )
        val joined = setOf(GroupRef(relay, "g3"))
        assertEquals(listOf("g2", "bitcoin-walk"), searchPublicGroups(entries, "bitcoin", joined).map { it.ref.groupId })
        assertEquals(listOf("g2", "bitcoin-walk"), searchPublicGroups(entries, "  ", joined).map { it.ref.groupId })
    }

    @Test
    fun nameMatchesComeBeforeDescriptionMatches() {
        val entries = publicGroupEntries(relay, listOf(meta("a", "Zeta", about = "nostr apps"), meta("b", "Nostr")), relayKey)
        assertEquals(listOf("b", "a"), searchPublicGroups(entries, "nostr", emptySet()).map { it.ref.groupId })
    }
}
