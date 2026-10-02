package com.nostr.torinos.emoji

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiAdoptionTest {
    private val user = "a".repeat(64)
    private val other = "b".repeat(64)
    private val cats = EmojiSetAddress("c".repeat(64), "cats")
    private val dogs = EmojiSetAddress("c".repeat(64), "dogs")

    @Test
    fun countsPeopleRatherThanEventsOrRepeatedTags() {
        val first = event(user, 1, cats, cats)
        val second = event(other, 1, cats)
        assertEquals(mapOf(cats to 2), emojiAdoptionCounts(listOf(first, first, second)))
    }

    @Test
    fun latestListRemovesOldReferencesAndUsesLowestIdOnTimestampTie() {
        val old = event(user, 1, cats)
        val newer = event(user, 2, dogs).copy(id = "bbbb")
        val empty = event(user, 2).copy(id = "aaaa")
        assertTrue(emojiAdoptionCounts(listOf(old, newer, empty)).isEmpty())
        assertTrue(emojiAdoptionCounts(listOf(empty, newer, old)).isEmpty())
    }

    @Test
    fun missingResponseKeepsPreviousListButEmptyLatestListClearsIt() {
        val state = EmojiAdoptionState(follows = setOf(user), latest = mapOf(user to event(user, 1, cats)))
        val noResponse = state.receive(emptyList())
        assertEquals(mapOf(cats to 1), noResponse.counts)
        val received = noResponse.receive(listOf(event(user, 2)))
        assertTrue(received.counts.isEmpty())
        assertEquals(2, received.latest.getValue(user).createdAt)
    }

    @Test
    fun ignoresUnknownAuthorsAndInlineEmojisForSetPopularity() {
        val state = EmojiAdoptionState(follows = setOf(user)).receive(listOf(
            event(other, 2, cats),
            event(user, 1).copy(tags = listOf(listOf("emoji", "cat", "https://example.com/cat.png"),
                listOf("a", "30023:${cats.author}:article"), listOf("a", "invalid"))),
        ))
        assertEquals(setOf(user), state.latest.keys)
        assertTrue(state.counts.isEmpty())
    }

    @Test
    fun olderRelayResponseDoesNotHideThatCountsUseANewerCachedList() {
        val state = EmojiAdoptionState(follows = setOf(user), latest = mapOf(user to event(user, 2, cats)))
            .receive(listOf(event(user, 1, dogs)))
        assertEquals(2, state.latest.getValue(user).createdAt)
        assertEquals(mapOf(cats to 1), state.counts)
    }

    private fun event(pubkey: String, time: Long, vararg addresses: EmojiSetAddress) =
        NostrEvent("event-$time", pubkey, time, 10030, addresses.map { listOf("a", it.value) }, "", "sig")
}
