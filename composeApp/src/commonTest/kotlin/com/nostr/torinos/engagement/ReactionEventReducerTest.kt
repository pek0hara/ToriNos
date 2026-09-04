package com.nostr.torinos.engagement

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionEventReducerTest {
    @Test
    fun addIgnoresAnEventAlreadyPresent() {
        val reaction = event(id = "reaction", pubkey = "alice")

        assertEquals(listOf(reaction), ReactionEventReducer.add(listOf(reaction), reaction))
    }

    @Test
    fun removeDeletesOnlyTheSpecifiedReaction() {
        val first = event(id = "first", pubkey = "alice")
        val second = event(id = "second", pubkey = "bob")

        assertEquals(listOf(second), ReactionEventReducer.remove(listOf(first, second), first.id))
    }

    @Test
    fun latestByPubkeyUsesCreationTimeAndIdAsTieBreaker() {
        val older = event(id = "older", pubkey = "alice", createdAt = 1)
        val sameTimeEarlierId = event(id = "a", pubkey = "bob", createdAt = 2)
        val latest = event(id = "latest", pubkey = "alice", createdAt = 2)
        val sameTimeLaterId = event(id = "b", pubkey = "bob", createdAt = 2)

        assertEquals(
            mapOf("alice" to latest, "bob" to sameTimeLaterId),
            ReactionEventReducer.latestByPubkey(
                listOf(older, sameTimeEarlierId, latest, sameTimeLaterId),
            ),
        )
    }

    private fun event(id: String, pubkey: String, createdAt: Long = 1) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 7,
        tags = emptyList(),
        content = "+",
        sig = "signature",
    )
}
