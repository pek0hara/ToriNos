package com.nostr.torinos.emoji

/** 本文中の `:shortcode:`。NIP-30 の shortcode は英数字・ハイフン・アンダースコア。 */
internal val CustomEmojiCodeRegex = Regex(""":([A-Za-z0-9_-]+):""")

internal data class ShortcodeMatch(val start: Int, val endExclusive: Int, val shortcode: String)

/**
 * 画像の分からない `:shortcode:` の候補。前後が半角英数字のもの（`12:30:45` の `:30:` など）は除く。
 * 日本語は区切りなしで続けて書くことが多いため、前後が全角文字でも候補にする。
 */
internal fun findStandaloneShortcodes(text: String): List<ShortcodeMatch> =
    CustomEmojiCodeRegex.findAll(text)
        .filter { match ->
            val before = text.getOrNull(match.range.first - 1)
            val after = text.getOrNull(match.range.last + 1)
            !before.isAsciiLetterOrDigit() && !after.isAsciiLetterOrDigit()
        }
        .map { ShortcodeMatch(it.range.first, it.range.last + 1, it.groupValues[1]) }
        .toList()

/** タグや入力の shortcode から前後の空白とコロンを取り除く。 */
internal fun normalizeShortcode(value: String): String = value.trim().trim(':')

private fun Char?.isAsciiLetterOrDigit(): Boolean =
    this != null && (this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9')
