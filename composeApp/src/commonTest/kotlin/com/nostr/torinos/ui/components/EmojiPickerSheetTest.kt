package com.nostr.torinos.ui.components

import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.RecentReaction
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
    fun recentReactionConvertsToAndFromReactionOption() {
        val custom = ReactionOption.Custom("unregistered", "https://example.com/unregistered.png")
        val unicode = ReactionOption.Unicode("🎉")

        assertEquals(RecentReaction.Custom(CustomEmoji(custom.shortcode, custom.imageUrl)), custom.toRecentReaction())
        assertEquals(custom, custom.toRecentReaction().toReactionOption())
        assertEquals(unicode, unicode.toRecentReaction().toReactionOption())
    }
}
