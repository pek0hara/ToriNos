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
    val compressed = secp256k1CompressedPublicKey(privateKey)
    return compressed.copyOfRange(1, 33)
}

actual fun schnorrSign(data: ByteArray, privateKey: ByteArray): ByteArray =
    Secp256k1.signSchnorr(data, privateKey, null)

actual fun schnorrVerify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
    Secp256k1.verifySchnorr(signature, data, publicKey)

internal actual fun secp256k1CompressedPublicKey(privateKey: ByteArray): ByteArray =
    Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(privateKey))

internal actual fun secp256k1PublicKeyTweakMul(publicKey: ByteArray, tweak: ByteArray): ByteArray =
    Secp256k1.pubKeyTweakMul(publicKey, tweak)
