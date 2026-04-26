package com.nostr.torinos.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

actual fun generatePrivateKey(): ByteArray {
    val key = ByteArray(32)
    SecureRandom().nextBytes(key)
    return key
}

actual fun derivePublicKey(privateKey: ByteArray): ByteArray {
    // 33バイト圧縮公開鍵 → 先頭1バイト(prefix)を除いた x-only 32バイト
    val compressed = Secp256k1.pubkeyCreate(privateKey)
    return compressed.copyOfRange(1, 33)
}

actual fun schnorrSign(data: ByteArray, privateKey: ByteArray): ByteArray =
    Secp256k1.signSchnorr(data, privateKey, null)
