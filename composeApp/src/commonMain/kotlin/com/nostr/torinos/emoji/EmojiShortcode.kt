package com.nostr.torinos.emoji

/** 本文中の `:shortcode:`。NIP-30 の shortcode は英数字・ハイフン・アンダースコア。 */
internal val CustomEmojiCodeRegex = Regex(""":([A-Za-z0-9_-]+):""")

/** タグや入力の shortcode から前後の空白とコロンを取り除く。 */
internal fun normalizeShortcode(value: String): String = value.trim().trim(':')
