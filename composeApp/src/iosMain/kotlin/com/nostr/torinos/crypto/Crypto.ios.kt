package com.nostr.torinos.crypto

import fr.acinq.secp256k1.Secp256k1
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { pinned ->
        digest.usePinned { out ->
            CC_SHA256(pinned.addressOf(0), data.size.toUInt(), out.addressOf(0))
        }
    }
    return digest.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun generatePrivateKey(): ByteArray {
    val key = ByteArray(32)
    key.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, 32.toULong(), pinned.addressOf(0))
    }
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
