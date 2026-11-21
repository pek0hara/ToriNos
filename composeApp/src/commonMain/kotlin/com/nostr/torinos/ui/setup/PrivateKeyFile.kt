package com.nostr.torinos.ui.setup

/**
 * 秘密鍵ファイル(Buzz 公式アプリが書き出す .nsec など)の中身から秘密鍵の文字列を取り出す。
 * 書式に依存しないよう、本文中の最初の nsec1... か 64 桁の hex を探す。
 * 見つからなければ null。パスワードで暗号化された ncryptsec は扱えないので例外で知らせる。
 */
internal fun extractPrivateKeyFromFile(content: String): String? {
    NSEC_PATTERN.find(content)?.let { return it.value }
    if (NCRYPTSEC_PATTERN.containsMatchIn(content)) {
        throw IllegalArgumentException("パスワードで暗号化された鍵(ncryptsec)には対応していません")
    }
    return HEX_PATTERN.find(content)?.value
}

private val NSEC_PATTERN = Regex("""nsec1[02-9ac-hj-np-z]{58}""", RegexOption.IGNORE_CASE)
private val NCRYPTSEC_PATTERN = Regex("""ncryptsec1[02-9ac-hj-np-z]+""", RegexOption.IGNORE_CASE)
private val HEX_PATTERN = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])""")
