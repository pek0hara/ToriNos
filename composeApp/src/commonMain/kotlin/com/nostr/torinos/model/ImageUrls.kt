package com.nostr.torinos.model

/** ノート本文から画像URLを抽出する */
private val imageUrlRegex = Regex(
    """https?://\S+\.(?:jpg|jpeg|png|gif|webp|bmp|svg)(\?\S*)?""",
    RegexOption.IGNORE_CASE,
)

fun extractImageUrls(content: String): List<String> =
    imageUrlRegex.findAll(content).map { it.value }.toList()

fun isImageUrl(url: String): Boolean = imageUrlRegex.matches(url)

fun stripImageUrls(content: String): String =
    imageUrlRegex.replace(content, "").trim()
