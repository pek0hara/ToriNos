package com.nostr.torinos.ui.components

import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.PublishedEmojiSet
import com.nostr.torinos.emoji.RecentReaction
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPickerSheetTest {
    private val setAddress = EmojiSetAddress("a".repeat(64), "birds")

    private fun published(vararg emojis: CustomEmoji) =
        PublishedEmojiSet(setAddress, "event", "Birds", 1, emojis.toList())

    @Test
    fun searchOptionsIncludePublishedEmojisThatAreNotRegisteredWithTheirSetAddress() {
        val registered = CustomEmoji("registered", "https://example.com/registered.png")
        val unregistered = CustomEmoji("unregistered", "https://example.com/unregistered.png")

        assertEquals(
            listOf(
                ReactionOption.Custom(registered.shortcode, registered.imageUrl),
                ReactionOption.Custom(unregistered.shortcode, unregistered.imageUrl, setAddress),
            ),
            customEmojiSearchOptions(
                registered = listOf(registered),
                published = listOf(published(unregistered)),
            ),
        )
    }

    @Test
    fun searchOptionsDoNotDuplicateRegisteredEmojiFoundInPublishedSet() {
        val emoji = CustomEmoji("bird", "https://example.com/bird.png")

        assertEquals(
            listOf(ReactionOption.Custom(emoji.shortcode, emoji.imageUrl, setAddress)),
            customEmojiSearchOptions(
                registered = listOf(emoji),
                published = listOf(published(emoji, emoji)),
                setAddressOf = { setAddress },
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

    @Test
    fun allFilterSkipsEmptyPersonalSectionsAndKeepsOrder() {
        val favorite = ReactionOption.Custom("fav", "https://example.com/fav.png")
        val custom = ReactionOption.Custom("mine", "https://example.com/mine.png")
        val titles = emojiPickerSections("", EmojiPickerFilter.All, listOf(favorite), emptyList(), listOf(custom), emptyList())
            .map { it.title }
        assertEquals(listOf("お気に入り", "カスタム絵文字") + STANDARD_EMOJI_CATEGORIES.map { it.label }, titles)
    }

    @Test
    fun queryOverridesFilterAndListsCustomMatchesBeforeUnicode() {
        val bird = ReactionOption.Custom("party_bird", "https://example.com/bird.png")
        val other = ReactionOption.Custom("cat", "https://example.com/cat.png")
        val sections = emojiPickerSections("party", EmojiPickerFilter.Favorites, emptyList(), emptyList(), emptyList(),
            listOf(other, bird))
        assertEquals(listOf("検索結果"), sections.map { it.title })
        assertEquals(bird, sections.single().options.first())
        assertEquals(false, other in sections.single().options)
    }

    @Test
    fun singleFiltersShowOnlyTheirSection() {
        val category = STANDARD_EMOJI_CATEGORIES[1]
        assertEquals(listOf(category.label), emojiPickerSections("", EmojiPickerFilter.Category(category),
            emptyList(), emptyList(), emptyList(), emptyList()).map { it.title })
        assertEquals(listOf("お気に入り"), emojiPickerSections("", EmojiPickerFilter.Favorites,
            emptyList(), emptyList(), emptyList(), emptyList()).map { it.title })
    }

    @Test
    fun communitySectionComesFirstAndHasItsOwnFilter() {
        val wave = ReactionOption.Custom("wave", "https://buzz.example/media/w.png")
        val favorite = ReactionOption.Custom("fav", "https://example.com/fav.png")
        val titles = emojiPickerSections("", EmojiPickerFilter.All, listOf(favorite), emptyList(), emptyList(), emptyList(),
            community = EmojiPickerSection("このコミュニティ", listOf(wave)))
            .map { it.title }
        assertEquals(listOf("このコミュニティ", "お気に入り") + STANDARD_EMOJI_CATEGORIES.map { it.label }, titles)
        assertEquals(listOf("このコミュニティ"), emojiPickerSections("", EmojiPickerFilter.Community, listOf(favorite), emptyList(),
            emptyList(), emptyList(), community = EmojiPickerSection("このコミュニティ", listOf(wave))).map { it.title })
    }

    @Test
    fun searchFindsCommunityEmojisFirst() {
        val wave = ReactionOption.Custom("wave_party", "https://buzz.example/media/w.png")
        val mine = ReactionOption.Custom("party", "https://example.com/p.png")
        val sections = emojiPickerSections("party", EmojiPickerFilter.All, emptyList(), emptyList(), emptyList(), listOf(mine),
            community = EmojiPickerSection("このコミュニティ", listOf(wave)))
        assertEquals(listOf(wave, mine), sections.single().options.take(2))
    }

    @Test
    fun unsendableOptionsAreHiddenEverywhere() {
        val japanese = ReactionOption.Custom("ぱーてぃ", "https://example.com/p.png")
        val ok = ReactionOption.Custom("party", "https://example.com/ok.png")
        val sendable: (ReactionOption) -> Boolean = { it !is ReactionOption.Custom || it.shortcode.all { c -> c.code < 128 } }
        val all = emojiPickerSections("", EmojiPickerFilter.All, listOf(japanese, ok), listOf(japanese), listOf(japanese), emptyList(),
            isSendable = sendable)
        assertEquals(listOf("お気に入り"), all.map { it.title }.filterNot { t -> STANDARD_EMOJI_CATEGORIES.any { it.label == t } })
        assertEquals(listOf<ReactionOption>(ok), all.first().options)
        val search = emojiPickerSections("ぱー", EmojiPickerFilter.All, emptyList(), emptyList(), emptyList(), listOf(japanese),
            isSendable = sendable)
        assertEquals(emptyList(), search.single().options)
    }
}
