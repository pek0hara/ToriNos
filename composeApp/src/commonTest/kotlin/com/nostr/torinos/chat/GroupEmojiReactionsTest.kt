package com.nostr.torinos.chat

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupEmojiReactionsTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun emojiSet(id: String, pubkey: String, createdAt: Long, vararg emojis: Pair<String, String>) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = 30030,
        tags = listOf(listOf("d", BUZZ_EMOJI_SET_D_TAG)) + emojis.map { (code, url) -> listOf("emoji", code, url) },
        content = "",
        sig = "s",
    )

    // --- コミュニティの絵文字の和集合 ---

    @Test
    fun unionTakesNewestSetPerShortcodeAndSortsByShortcode() {
        val palette = unionBuzzEmojiSets(
            listOf(
                emojiSet("1", alice, 100, "wave" to "https://x/old.png", "zebra" to "https://x/z.png"),
                emojiSet("2", bob, 200, "wave" to "https://x/new.png", "apple" to "https://x/a.png"),
            ),
        )
        assertEquals(
            listOf(
                CustomEmoji("apple", "https://x/a.png"),
                CustomEmoji("wave", "https://x/new.png"),
                CustomEmoji("zebra", "https://x/z.png"),
            ),
            palette.emojis,
        )
        assertFalse(palette.truncated)
    }

    @Test
    fun unionTieBreaksToSmallestUrlRegardlessOfOrder() {
        val a = emojiSet("1", alice, 100, "wave" to "https://x/b.png")
        val b = emojiSet("2", bob, 100, "wave" to "https://x/a.png")
        assertEquals("https://x/a.png", unionBuzzEmojiSets(listOf(a, b)).emojis.single().imageUrl)
        assertEquals("https://x/a.png", unionBuzzEmojiSets(listOf(b, a)).emojis.single().imageUrl)
    }

    @Test
    fun unionNormalizesShortcodesAndDropsInvalidEntries() {
        val palette = unionBuzzEmojiSets(
            listOf(
                emojiSet(
                    "1", alice, 100,
                    "  :WAVE:  " to "https://x/wave.png",
                    "日本語" to "https://x/jp.png",
                    "empty" to "",
                    "a".repeat(65) to "https://x/long.png",
                    "wave" to "https://x/second.png",
                ),
            ),
        )
        assertEquals(listOf(CustomEmoji("wave", "https://x/wave.png")), palette.emojis)
    }

    @Test
    fun unionIgnoresOtherKindsAndOtherSets() {
        val other = emojiSet("1", alice, 100, "wave" to "https://x/w.png").copy(tags = listOf(listOf("d", "mine"), listOf("emoji", "wave", "https://x/w.png")))
        val note = emojiSet("2", alice, 100, "cat" to "https://x/c.png").copy(kind = 1)
        assertTrue(unionBuzzEmojiSets(listOf(other, note)).emojis.isEmpty())
    }

    @Test
    fun unionIsMarkedTruncatedWhenTheLimitIsReached() {
        val events = (1..3).map { emojiSet("$it", alice, it.toLong(), "e$it" to "https://x/$it.png") }
        assertTrue(unionBuzzEmojiSets(events, limit = 3).truncated)
        assertFalse(unionBuzzEmojiSets(events, limit = 4).truncated)
    }

    @Test
    fun paletteFilterAsksForTheCommunitySets() {
        val filter = buzzEmojiPaletteFilter()
        assertEquals(listOf(30030), filter.kinds)
        assertEquals(listOf(BUZZ_EMOJI_SET_D_TAG), filter.dTags)
        assertEquals(BUZZ_EMOJI_SET_LIMIT, filter.limit)
    }

    // --- 送れるかの判定 ---

    @Test
    fun buzzAcceptsUnicodeUpToSixtyFourCodePoints() {
        assertTrue(isGroupReactionSendable(ReactionOption.Unicode("👍"), GroupFlavor.Buzz))
        assertTrue(isGroupReactionSendable(ReactionOption.Unicode("😀".repeat(64)), GroupFlavor.Buzz))
        assertFalse(isGroupReactionSendable(ReactionOption.Unicode("😀".repeat(65)), GroupFlavor.Buzz))
        // 家族の絵文字は1文字に見えて7コードポイント。
        assertTrue(isGroupReactionSendable(ReactionOption.Unicode("👨‍👩‍👧‍👦"), GroupFlavor.Buzz))
        assertFalse(isGroupReactionSendable(ReactionOption.Unicode("  "), GroupFlavor.Buzz))
    }

    @Test
    fun buzzAcceptsOnlyShortcodesItCanNormalize() {
        assertTrue(isGroupReactionSendable(ReactionOption.Custom("party_parrot-2", "https://x/p.png"), GroupFlavor.Buzz))
        // リレーが小文字にして受け付ける。
        assertTrue(isGroupReactionSendable(ReactionOption.Custom("Party", "https://x/p.png"), GroupFlavor.Buzz))
        assertFalse(isGroupReactionSendable(ReactionOption.Custom("ぱーてぃ", "https://x/p.png"), GroupFlavor.Buzz))
        assertFalse(isGroupReactionSendable(ReactionOption.Custom("a".repeat(65), "https://x/p.png"), GroupFlavor.Buzz))
        assertFalse(isGroupReactionSendable(ReactionOption.Custom("party", " "), GroupFlavor.Buzz))
    }

    @Test
    fun standardGroupsDoNotRestrictLikeTheFeed() {
        assertTrue(isGroupReactionSendable(ReactionOption.Custom("ぱーてぃ", "https://x/p.png"), GroupFlavor.Standard))
        assertTrue(isGroupReactionSendable(ReactionOption.Unicode("😀".repeat(65)), GroupFlavor.Standard))
        assertFalse(isGroupReactionSendable(ReactionOption.Unicode(" "), GroupFlavor.Standard))
    }

    // --- 並びの組み立て ---

    @Test
    fun quickOptionsPutCommunityRecentFirstThenAccountRecentWithoutDuplicates() {
        val wave = ReactionOption.Custom("wave", "https://buzz/media/w.png")
        val thumbs = ReactionOption.Unicode("👍")
        val heart = ReactionOption.Unicode("❤️")
        val japanese = ReactionOption.Custom("ぱーてぃ", "https://x/p.png")
        val options = groupQuickReactionOptions(
            communityRecent = listOf(wave, thumbs),
            accountRecent = listOf(heart, thumbs, japanese),
            isSendable = { isGroupReactionSendable(it, GroupFlavor.Buzz) },
        )
        assertEquals(listOf(wave, thumbs, heart), options)
    }

    @Test
    fun quickOptionsAreCapped() {
        val many = (1..30).map { ReactionOption.Unicode("e$it") }
        assertEquals(16, groupQuickReactionOptions(many, emptyList(), { true }).size)
        assertEquals(6, groupQuickReactionOptions(many, emptyList(), { true }, limit = 6).size)
    }

    // --- 「最近使った」の記録先 ---

    @Test
    fun onlyUnicodeAndOwnEmojisGoToTheAccountRecent() {
        val own = listOf(CustomEmoji("cat", "https://cdn/cat.png"))
        assertTrue(recordsInAccountRecent(ReactionOption.Unicode("👍"), own))
        assertTrue(recordsInAccountRecent(ReactionOption.Custom("cat", "https://cdn/cat.png"), own))
        assertFalse(recordsInAccountRecent(ReactionOption.Custom("wave", "https://buzz/media/w.png"), own))
        // 同じ shortcode でも画像が違えばコミュニティの絵文字。
        assertFalse(recordsInAccountRecent(ReactionOption.Custom("cat", "https://buzz/media/cat.png"), own))
    }

    @Test
    fun communityRecentMovesToFrontAndIsCapped() {
        val a = ReactionOption.Unicode("a")
        val b = ReactionOption.Unicode("b")
        assertEquals(listOf(b, a), recordGroupRecent(listOf(a), b))
        assertEquals(listOf(a, b), recordGroupRecent(listOf(b, a), a))
        val full = (1..24).map { ReactionOption.Unicode("e$it") }
        val next = recordGroupRecent(full, a)
        assertEquals(24, next.size)
        assertEquals(a, next.first())
        assertFalse(ReactionOption.Unicode("e24") in next)
    }

    @Test
    fun communityRecentRoundTripsAndIgnoresBrokenValues() {
        val list = listOf(
            ReactionOption.Unicode("👍"),
            ReactionOption.Custom("wave", "https://buzz/media/w.png", EmojiSetAddress(alice, "set")),
        )
        // セットのアドレスは保存しない（送るときに自分のセットから引き直す）。
        assertEquals(
            listOf(ReactionOption.Unicode("👍"), ReactionOption.Custom("wave", "https://buzz/media/w.png")),
            decodeGroupRecent(encodeGroupRecent(list)),
        )
        assertEquals(emptyList(), decodeGroupRecent(null))
        assertEquals(emptyList(), decodeGroupRecent("{broken"))
    }

    @Test
    fun communityRecentKeyIsPerAccountAndCommunity() {
        assertTrue(groupRecentStorageKey(alice, "wss://a") != groupRecentStorageKey(bob, "wss://a"))
        assertTrue(groupRecentStorageKey(alice, "wss://a") != groupRecentStorageKey(alice, "wss://b"))
    }

    // --- 集計の単位とチップ ---

    @Test
    fun reactionKeyIgnoresShortcodeCaseButNotTheImage() {
        assertEquals(groupReactionKey(":Wave:", "https://x/w.png"), groupReactionKey(":wave:", "https://x/w.png"))
        assertTrue(groupReactionKey(":wave:", "https://x/w.png") != groupReactionKey(":wave:", "https://x/other.png"))
        assertTrue(groupReactionKey("A", null) != groupReactionKey("a", null))
    }

    @Test
    fun summaryBecomesTheOptionToSend() {
        assertEquals(ReactionOption.Unicode("+"), GroupReactionSummary("+", 1, false, null).toReactionOption(emptyMap()))
        assertEquals(
            ReactionOption.Custom("wave", "https://x/w.png"),
            GroupReactionSummary(":wave:", 1, false, null, emojiUrl = "https://x/w.png").toReactionOption(emptyMap()),
        )
        // タグ無しの `:shortcode:` はコミュニティの一覧から画像を引く。
        assertEquals(
            ReactionOption.Custom("wave", "https://x/p.png"),
            GroupReactionSummary(":Wave:", 1, false, null).toReactionOption(mapOf("wave" to "https://x/p.png")),
        )
        assertEquals(ReactionOption.Unicode(":nope:"), GroupReactionSummary(":nope:", 1, false, null).toReactionOption(emptyMap()))
    }

    @Test
    fun ownSummaryIsFoundForTheOption() {
        val summaries = listOf(
            GroupReactionSummary(":wave:", 2, true, "r1", emojiUrl = "https://x/w.png"),
            GroupReactionSummary("👍", 1, true, "r2"),
            GroupReactionSummary(":cat:", 1, false, null, emojiUrl = "https://x/c.png"),
        )
        assertEquals("r1", ownReactionIdFor(summaries, ReactionOption.Custom("Wave", "https://x/w.png")))
        assertNull(ownReactionIdFor(summaries, ReactionOption.Custom("wave", "https://x/other.png")))
        assertEquals("r2", ownReactionIdFor(summaries, ReactionOption.Unicode("👍")))
        assertNull(ownReactionIdFor(summaries, ReactionOption.Custom("cat", "https://x/c.png")))
    }

    @Test
    fun tagLessShortcodeResolvesThroughThePalette() {
        assertEquals("https://x/p.png", reactionImageUrl(GroupReactionSummary(":Wave:", 1, false, null), mapOf("wave" to "https://x/p.png")))
        assertEquals("https://x/t.png", reactionImageUrl(GroupReactionSummary(":wave:", 1, false, null, "https://x/t.png"), mapOf("wave" to "https://x/p.png")))
        assertNull(reactionImageUrl(GroupReactionSummary("👍", 1, false, null), mapOf("wave" to "https://x/p.png")))
        assertTrue(hasTagLessShortcodeReaction(listOf(GroupReactionSummary(":wave:", 1, false, null))))
        assertFalse(hasTagLessShortcodeReaction(listOf(GroupReactionSummary(":wave:", 1, false, null, "https://x/t.png"))))
    }
}
