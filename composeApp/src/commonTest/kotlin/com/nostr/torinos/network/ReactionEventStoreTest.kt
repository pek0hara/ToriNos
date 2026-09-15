package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionEventStoreTest {
    @AfterTest
    fun tearDown() {
        ReactionEventStore.clearForTest()
    }

    @Test
    fun cachedReactionCanBeReusedByEventTagSubscription() {
        val reaction = reaction(id = "reaction", targetId = "note", pTag = "author")
        ReactionEventStore.observe(reaction)

        val cached = ReactionEventStore.matching(
            listOf(NostrFilter(kinds = listOf(7), eTags = listOf("note"))),
        )

        assertEquals(listOf(reaction), cached)
    }

    @Test
    fun cachedReactionIsOnlyReplayedToItsSourceRelay() {
        val reaction = reaction(id = "reaction", targetId = "note", pTag = "author")
        ReactionEventStore.observe(reaction, setOf("wss://one.example"))

        val filters = listOf(NostrFilter(kinds = listOf(7), eTags = listOf("note")))

        assertEquals(
            listOf(reaction),
            ReactionEventStore.matching(filters, setOf("wss://one.example")),
        )
        assertTrue(
            ReactionEventStore.matching(filters, setOf("wss://two.example")).isEmpty(),
        )
    }

    @Test
    fun reactionObservedOnMultipleRelaysRemainsAvailableForBoth() {
        val reaction = reaction(id = "reaction", targetId = "note", pTag = "author")
        ReactionEventStore.observe(reaction, setOf("wss://one.example"))
        ReactionEventStore.observe(reaction, setOf("wss://two.example"))

        val filters = listOf(NostrFilter(kinds = listOf(7), eTags = listOf("note")))

        assertEquals(listOf(reaction), ReactionEventStore.matching(filters, setOf("wss://one.example")))
        assertEquals(listOf(reaction), ReactionEventStore.matching(filters, setOf("wss://two.example")))
    }

    @Test
    fun deletionOnlyRemovesReactionFromTheRelayThatDeliveredIt() {
        val reaction = reaction(id = "reaction", targetId = "note", pTag = "author")
        ReactionEventStore.observe(reaction, setOf("wss://one.example", "wss://two.example"))

        ReactionEventStore.observe(
            deletion(id = "deletion", author = "reactor", targetId = reaction.id),
            setOf("wss://one.example"),
        )

        val filters = listOf(NostrFilter(kinds = listOf(7), eTags = listOf("note")))
        assertTrue(ReactionEventStore.matching(filters, setOf("wss://one.example")).isEmpty())
        assertEquals(listOf(reaction), ReactionEventStore.matching(filters, setOf("wss://two.example")))
    }

    @Test
    fun knownTargetAuthorFindsReactionWithoutPubkeyTag() {
        ReactionEventStore.observe(note(id = "note", pubkey = "author"))
        val reaction = reaction(id = "reaction", targetId = "note", pTag = null)
        ReactionEventStore.observe(reaction)

        val cached = ReactionEventStore.matching(
            listOf(NostrFilter(kinds = listOf(7), pTags = listOf("author"))),
        )

        assertEquals(listOf(reaction), cached)
        assertTrue(ReactionEventStore.isAddressedTo(reaction, "author"))
    }

    @Test
    fun knownTargetAuthorTakesPriorityOverIncorrectPubkeyTag() {
        ReactionEventStore.observe(note(id = "note", pubkey = "actual-author"))
        ReactionEventStore.observe(reaction(id = "reaction", targetId = "note", pTag = "wrong-author"))

        val cached = ReactionEventStore.matching(
            listOf(NostrFilter(kinds = listOf(7), pTags = listOf("wrong-author"))),
        )

        assertTrue(cached.isEmpty())
    }

    @Test
    fun journalSnapshotReturnsCachedPositiveReactionsImmediately() {
        ReactionEventStore.observe(note(id = "note", pubkey = "author"))
        val like = reaction(id = "like", targetId = "note", pTag = null)
        val emoji = reaction(id = "emoji", targetId = "note", pTag = null, content = "🔥")
        val dislike = reaction(id = "dislike", targetId = "note", pTag = null, content = "-")
        ReactionEventStore.observe(like)
        ReactionEventStore.observe(emoji)
        ReactionEventStore.observe(dislike)

        val received = ReactionEventStore.receivedReactions(
            pubkey = "author",
            since = 0,
            until = 1_000,
        )

        assertEquals(setOf("like", "emoji"), received.mapTo(hashSetOf()) { it.id })
    }

    @Test
    fun deletionRemovesCachedReactionFromTheSameAuthor() {
        val reaction = reaction(id = "reaction", targetId = "note", pTag = "author")
        ReactionEventStore.observe(reaction)

        ReactionEventStore.observe(deletion(id = "deletion", author = "reactor", targetId = reaction.id))

        assertTrue(
            ReactionEventStore.matching(
                listOf(NostrFilter(kinds = listOf(7), eTags = listOf("note"))),
            ).isEmpty(),
        )
    }

    @Test
    fun deletionCannotRemoveAnotherAuthorsReaction() {
        val reaction = reaction(id = "reaction", targetId = "note", pTag = "author")
        ReactionEventStore.observe(reaction)

        ReactionEventStore.observe(deletion(id = "deletion", author = "attacker", targetId = reaction.id))

        assertEquals(
            listOf(reaction),
            ReactionEventStore.matching(
                listOf(NostrFilter(kinds = listOf(7), eTags = listOf("note"))),
            ),
        )
    }

    private fun note(id: String, pubkey: String) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = 100,
        kind = 1,
        tags = emptyList(),
        content = "note",
        sig = "signature",
    )

    private fun reaction(
        id: String,
        targetId: String,
        pTag: String?,
        content: String = "+",
    ) = NostrEvent(
        id = id,
        pubkey = "reactor",
        createdAt = 200,
        kind = 7,
        tags = buildList {
            add(listOf("e", targetId))
            pTag?.let { add(listOf("p", it)) }
        },
        content = content,
        sig = "signature",
    )

    private fun deletion(id: String, author: String, targetId: String) = NostrEvent(
        id = id,
        pubkey = author,
        createdAt = 300,
        kind = 5,
        tags = listOf(listOf("e", targetId)),
        content = "",
        sig = "signature",
    )
}
