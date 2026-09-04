package com.nostr.torinos.ui.post

import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.ui.profile.customEmojiTagsForContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString

class DraftCustomEmojisTest {
    private val bird = CustomEmoji("bird", "https://example.com/original.png")
    private val other = CustomEmoji("bird", "https://example.com/other.png")

    @Test
    fun selectedUrlSurvivesRegistryReplacementAndRemoval() {
        val retained = resolveDraftEmojis(":bird:", listOf(bird), listOf(other))
        assertEquals(listOf(bird), retained)
        assertEquals(listOf(bird), resolveDraftEmojis(":bird:", retained, emptyList()))
        assertEquals(
            listOf(listOf("emoji", "bird", bird.imageUrl)),
            customEmojiTagsForContent(":bird:", retained),
        )
    }

    @Test
    fun typedCodesResolveAndDeletedCodesAreRemoved() {
        val retained = resolveDraftEmojis(":bird: :bird: :unknown:", emptyList(), listOf(bird))
        assertEquals(listOf(bird), retained)
        assertEquals(emptyList(), resolveDraftEmojis(":unknown:", retained, listOf(bird)))
    }

    @Test
    fun conflictingSelectionUsesAnUnusedCodeAndKeepsBothImages() {
        val selected = uniqueDraftEmoji(other, listOf(bird), ":bird: :bird_2:")
        assertEquals("bird_3", selected.shortcode)
        val retained = resolveDraftEmojis(":bird: :bird_3:", listOf(bird, selected), listOf(other))
        assertEquals(listOf(bird, selected), retained)
        assertEquals(selected, uniqueDraftEmoji(other, retained, ":bird: :bird_3:"))
        assertEquals(bird, uniqueDraftEmoji(bird, retained, ":bird:"))
    }

    @Test
    fun memoRoundTripPreservesSelectedUrlAndSupportsOlderMemos() {
        val memo = PostMemoPayload(text = ":bird:", customEmojis = listOf(bird), updatedAt = 10)
        val restored = memoJson.decodeFromString<PostMemoPayload>(memoJson.encodeToString(memo))
            .toPostMemoData()
        assertEquals(listOf(bird), restored.customEmojis)
        val legacy = memoJson.decodeFromString<PostMemoPayload>("""{"text":":bird:","updatedAt":10}""")
        assertEquals(emptyList(), legacy.customEmojis)
    }
}
