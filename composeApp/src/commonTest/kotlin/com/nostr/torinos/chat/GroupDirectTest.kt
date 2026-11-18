package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupDirectTest {
    private val relayKey = "f".repeat(64)
    private val me = "a".repeat(64)
    private val tanaka = "b".repeat(64)
    private val sato = "c".repeat(64)

    private fun metadata(vararg tags: List<String>, signer: String = relayKey) =
        parseGroupMetadata(NostrEvent("1".repeat(64), signer, 100, GROUP_METADATA_KIND, listOf(listOf("d", "g1")) + tags, "", "sig"), relayKey)!!

    @Test
    fun dmChannelIsListedAsDirectWithItsParticipants() {
        val dm = metadata(listOf("name", "dm"), listOf("hidden"), listOf("p", me), listOf("p", tanaka), listOf("p", "bad"), listOf("t", "dm"))

        assertEquals(GroupLayout.Direct, dm.layout)
        assertEquals(listOf(me, tanaka), dm.participants)
        assertTrue(dm.labels.isEmpty())
    }

    @Test
    fun workflowAndSystemChannelsStayHidden() {
        assertEquals(GroupLayout.Hidden, metadata(listOf("t", "workflow")).layout)
        assertEquals(GroupLayout.Hidden, metadata(listOf("t", "system")).layout)
    }

    @Test
    fun labelsAreTheTagsAfterTheChannelType() {
        val solo = metadata(listOf("t", "stream"), listOf("t", "buzz.solo"), listOf("t", "x"))

        assertEquals("stream", solo.channelType)
        assertEquals(listOf("buzz.solo", "x"), solo.labels)
        assertTrue(solo.isSolo)
        assertFalse(metadata(listOf("t", "stream")).isSolo)
    }

    @Test
    fun dmNameListsTheOthers() {
        val names = mapOf(tanaka to "tanaka", sato to "sato")
        fun name(pubkey: String) = names[pubkey]

        assertEquals("tanaka", dmDisplayName(listOf(me, tanaka), me, ::name))
        assertEquals("tanaka、sato", dmDisplayName(listOf(tanaka, me, sato), me, ::name))
        // 名前が分からない相手は公開鍵の先頭。
        assertEquals("dddddddd…", dmDisplayName(listOf(me, "d".repeat(64)), me, ::name))
        // 自分だけの DM。
        assertEquals("自分", dmDisplayName(listOf(me), me, ::name))
        assertEquals("DM", dmDisplayName(emptyList(), me, ::name))
    }

    @Test
    fun hiddenDmsComeFromTheNewestRelaySignedSnapshot() {
        fun snapshot(id: String, createdAt: Long, signer: String, vararg hidden: String) =
            NostrEvent(id.repeat(64), signer, createdAt, BUZZ_DM_VISIBILITY_KIND, listOf(listOf("p", me)) + hidden.map { listOf("h", it) }, "", "sig")

        val events = listOf(
            snapshot("1", 100, relayKey, "old"),
            snapshot("2", 200, relayKey, "dm1", "dm2"),
            snapshot("3", 300, me, "forged"),
        )

        assertEquals(setOf("dm1", "dm2"), hiddenDmIds(events, relayKey))
        assertTrue(hiddenDmIds(emptyList(), relayKey).isEmpty())
        // リレーの鍵が分からなければ、何も隠さない。
        assertTrue(hiddenDmIds(events, null).isEmpty())
    }
}
