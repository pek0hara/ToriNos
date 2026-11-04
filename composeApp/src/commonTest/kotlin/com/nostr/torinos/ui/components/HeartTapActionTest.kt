package com.nostr.torinos.ui.components

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiPreferences
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.RecentReaction
import com.nostr.torinos.emoji.RegisteredEmojiSet
import com.nostr.torinos.emoji.registerSet
import com.nostr.torinos.emoji.setDefaultReaction
import com.nostr.torinos.emoji.unregisterSet
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.eventTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeartTapActionTest {
    private val party = ReactionOption.Unicode("🎉")
    private val cat = ReactionOption.Custom("cat", "https://example.com/cat.png")

    @Test
    fun noOwnReactionSendsLikeWhenDefaultIsLike() {
        assertEquals(HeartTapAction.Like, heartTapAction(false, false, null, defaultReaction = null))
    }

    @Test
    fun noOwnReactionSendsDefaultEmoji() {
        assertEquals(HeartTapAction.React(party), heartTapAction(false, false, null, defaultReaction = party))
    }

    @Test
    fun likedTapRemovesLikeRegardlessOfDefault() {
        assertEquals(HeartTapAction.Unlike, heartTapAction(true, false, null, defaultReaction = party))
    }

    @Test
    fun emojiReactedTapRemovesThatEmoji() {
        assertEquals(HeartTapAction.Unreact(cat), heartTapAction(false, true, cat, defaultReaction = party))
    }

    @Test
    fun emojiReactedButUnknownDoesNothing() {
        assertEquals(HeartTapAction.None, heartTapAction(false, true, null, defaultReaction = party))
    }

    @Test
    fun defaultCustomEmojiUsesRegisteredSetAddress() {
        val address = EmojiSetAddress("a".repeat(64), "cats")
        val preferences = EmojiPreferences()
            .registerSet(RegisteredEmojiSet(address, "Cats", listOf(CustomEmoji(cat.shortcode, cat.imageUrl))))
            .setDefaultReaction(RecentReaction.Custom(CustomEmoji(cat.shortcode, cat.imageUrl)))

        assertEquals(cat.copy(setAddress = address), preferences.defaultReactionOption())
        assertNull(EmojiPreferences().defaultReactionOption())
    }

    @Test
    fun defaultCustomEmojiFromUnregisteredSetSendsTagWithoutAddress() {
        val address = EmojiSetAddress("a".repeat(64), "cats")
        val preferences = EmojiPreferences()
            .registerSet(RegisteredEmojiSet(address, "Cats", listOf(CustomEmoji(cat.shortcode, cat.imageUrl))))
            .setDefaultReaction(RecentReaction.Custom(CustomEmoji(cat.shortcode, cat.imageUrl)))
            .unregisterSet(address)

        val option = preferences.defaultReactionOption()

        assertEquals(cat, option)
        assertEquals(
            listOf(listOf("e", "id"), listOf("p", "pk"), listOf("emoji", "cat", cat.imageUrl)),
            option!!.eventTags("id", "pk"),
        )
    }
}
