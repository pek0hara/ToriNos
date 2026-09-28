package com.nostr.torinos.emoji

/** kind 30030 絵文字セットのアドレス（`30030:<pubkey>:<d>`）。 */
data class EmojiSetAddress(
    val author: String,
    val identifier: String,
) {
    /** `a` タグや NIP-30 の4要素目に書く値。 */
    val value: String get() = "$KIND_EMOJI_SET:$author:$identifier"

    override fun toString(): String = value

    companion object {
        const val KIND_EMOJI_SET = 30030

        fun of(author: String, identifier: String): EmojiSetAddress? {
            val normalizedAuthor = author.trim().lowercase()
            if (!normalizedAuthor.isPubkeyHex() || identifier.isBlank()) return null
            return EmojiSetAddress(normalizedAuthor, identifier)
        }

        /** `30030:<pubkey>:<d>` を読む。kind が違う、pubkey が64桁の16進でない、d が空なら null。 */
        fun parse(value: String): EmojiSetAddress? {
            val parts = value.trim().split(':', limit = 3)
            if (parts.size != 3 || parts[0] != KIND_EMOJI_SET.toString()) return null
            return of(parts[1], parts[2])
        }
    }
}

private fun String.isPubkeyHex(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
