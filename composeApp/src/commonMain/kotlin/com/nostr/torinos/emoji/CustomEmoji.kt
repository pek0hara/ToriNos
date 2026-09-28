package com.nostr.torinos.emoji

import kotlinx.serialization.Serializable

/** NIP-30 のカスタム絵文字。同一性は (shortcode, imageUrl)。下書き（kind 31234）にもこの形で保存する。 */
@Serializable
data class CustomEmoji(
    val shortcode: String,
    val imageUrl: String,
)
