package com.nostr.torinos.ui.components

import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.RecentReaction
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPickerSheetTest {
    @Test
    fun searchOptionsIncludePublishedEmojisThatAreNotRegistered() {
        val registered = CustomEmoji("registered", "https://example.com/registered.png")
        val unregistered = CustomEmoji("unregistered", "https://example.com/unregistered.png")

        assertEquals(
            listOf(
                ReactionOption.Custom(registered.shortcode, registered.imageUrl),
                ReactionOption.Custom(unregistered.shortcode, unregistered.imageUrl),
            ),
            customEmojiSearchOptions(
                registered = listOf(registered),
                published = listOf(unregistered),
            ),
        )
    }

    @Test
    fun searchOptionsDoNotDuplicateRegisteredEmojiFoundInPublishedSet() {
        val emoji = CustomEmoji("bird", "https://example.com/bird.png")

        assertEquals(
            listOf(ReactionOption.Custom(emoji.shortcode, emoji.imageUrl)),
            customEmojiSearchOptions(
                registered = listOf(emoji),
                published = listOf(emoji, emoji),
            ),
        )
    }

    @Test
    fun recentUnregisteredCustomEmojiUsesSavedHistoryUrl() {
        val recent = RecentReaction(
            kind = RecentReaction.CustomKind,
            value = "unregistered",
            imageUrl = "https://example.com/unregistered.png",
        )

        assertEquals(
            ReactionOption.Custom(recent.value, recent.imageUrl),
            recent.toReactionOption(emptyMap()),
        )
    }

    @Test
    fun legacyRecentCustomEmojiFallsBackToRegisteredEmoji() {
        val emoji = CustomEmoji("bird", "https://example.com/bird.png")
        val recent = RecentReaction(RecentReaction.CustomKind, emoji.shortcode)

        assertEquals(
            ReactionOption.Custom(emoji.shortcode, emoji.imageUrl),
            recent.toReactionOption(mapOf(emoji.shortcode to emoji)),
        )
    }
}
