package com.nostr.torinos.crypto

/** SHA-256 ハッシュ */
expect fun sha256(data: ByteArray): ByteArray

/** 32バイトの秘密鍵をランダム生成 */
expect fun generatePrivateKey(): ByteArray

/** 秘密鍵から x-only 公開鍵（32バイト）を導出 */
expect fun derivePublicKey(privateKey: ByteArray): ByteArray

/** secp256k1 Schnorr 署名（64バイト） */
expect fun schnorrSign(data: ByteArray, privateKey: ByteArray): ByteArray

/** secp256k1 Schnorr 署名検証 */
expect fun schnorrVerify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean

/** secp256k1 公開鍵（33バイト圧縮形式）を導出 */
internal expect fun secp256k1CompressedPublicKey(privateKey: ByteArray): ByteArray

/** secp256k1 public key tweak multiplication。戻り値は 33 または 65 バイト公開鍵。 */
internal expect fun secp256k1PublicKeyTweakMul(publicKey: ByteArray, tweak: ByteArray): ByteArray

// ---- Hex ユーティリティ ----

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val i = b.toInt() and 0xFF
        append(HEX[i ushr 4])
        append(HEX[i and 0x0F])
    }
}

fun String.fromHex(): ByteArray {
    check(length % 2 == 0) { "Invalid hex string length: $length" }
    return ByteArray(length / 2) { i ->
        ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
}
