package com.nostr.torinos.ui.settings

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.PublishedEmojiSet
import com.nostr.torinos.emoji.RegisteredEmojiSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiDiscoverySortTest {
    private val old = set("cats", "Cats", 1)
    private val new = set("dogs", "Dogs", 10)

    @Test
    fun adoptedOldSetComesBeforeUnusedNewSetAndNewestSortRemainsAvailable() {
        val counts = mapOf(old.address to 4)
        assertEquals(listOf(old, new), listOf(new, old).sortForDiscovery("", EmojiSetSort.Adoption, counts))
        assertEquals(listOf(new, old), listOf(old, new).sortForDiscovery("", EmojiSetSort.Newest, counts))
    }

    @Test
    fun exactSearchMatchOutranksPopularityAndColonsAreNormalized() {
        val exact = set("cat", "Cat", 1)
        val prefix = set("cat-house", "Cat House", 10).copy(
            emojis = listOf(CustomEmoji("cat_house", "https://example.com/cat-house.png")),
        )
        assertEquals(listOf(exact, prefix), listOf(prefix, exact)
            .sortForDiscovery(" :CAT: ", EmojiSetSort.Adoption, mapOf(prefix.address to 99)))
    }

    @Test
    fun knownAddressNeverFallsBackToAnotherSetWithTheSameEmoji() {
        assertNull(findRequestedEmojiSet("cat", "https://example.com/cat.png",
            EmojiSetAddress(old.address.author, "missing"), emptyList(), listOf(old), false))
    }

    @Test
    fun lateResultsAppendAndDoNotMoveAlreadyDisplayedRowsUntilApplied() {
        val first = reconcileEmojiSetOrder(listOf("a"), listOf("b", "a", "c"), apply = false)
        assertEquals(listOf("a", "b", "c"), first)
        val updatedRank = listOf("c", "b", "a")
        assertEquals(first, reconcileEmojiSetOrder(first, updatedRank, apply = false))
        assertEquals(updatedRank, reconcileEmojiSetOrder(first, updatedRank, apply = true))
        assertEquals(listOf("b", "c"), reconcileEmojiSetOrder(first, listOf("c", "b"), apply = false))
    }

    @Test
    fun registeredOnlyShowsEveryRegisteredSetEvenIfNotPublishedOnThisRelay() {
        val elsewhere = RegisteredEmojiSet(EmojiSetAddress("b".repeat(64), "birds"), "Birds",
            listOf(CustomEmoji("bird", "https://example.com/bird.png")))
        val registered = listOf(RegisteredEmojiSet(old.address, "Cats (local)", old.emojis), elsewhere)
        val result = listOf(old, new).filterEmojiSets("", registeredOnly = true, registered)
        // 公開一覧にあるものは公開側の情報を、無いものは登録時の内容を使う。
        assertEquals(listOf(old.address, elsewhere.address), result.map { it.address })
        assertEquals(old, result[0])
        assertEquals("Birds", result[1].name)
        assertEquals(elsewhere.emojis, result[1].emojis)
        assertEquals(listOf(elsewhere.address),
            listOf(old, new).filterEmojiSets(":BIRD:", registeredOnly = true, registered).map { it.address })
    }

    @Test
    fun withoutRegisteredOnlyOnlyPublishedSetsAreSearched() {
        val elsewhere = RegisteredEmojiSet(EmojiSetAddress("b".repeat(64), "birds"), "Birds", emptyList())
        assertEquals(listOf(new), listOf(old, new).filterEmojiSets("dog", registeredOnly = false, listOf(elsewhere)))
        assertEquals(listOf(old, new), listOf(old, new).filterEmojiSets(" ", registeredOnly = false, listOf(elsewhere)))
        assertEquals(emptyList(), listOf(old, new).filterEmojiSets("birds", registeredOnly = false, listOf(elsewhere)))
    }

    private fun set(id: String, name: String, time: Long) = PublishedEmojiSet(
        EmojiSetAddress("a".repeat(64), id), id, name, time,
        listOf(CustomEmoji("cat", "https://example.com/cat.png")),
    )
}
