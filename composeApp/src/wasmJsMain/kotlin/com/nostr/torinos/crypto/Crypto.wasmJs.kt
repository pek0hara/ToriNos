package com.nostr.torinos.crypto

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

// Android / iOS は libsecp256k1 に aux_rand として null を渡し、ゼロとして扱われる。
// 同じ入力から同じ署名になるよう、Web もゼロを渡す。
private val ZERO_AUX_RAND = ByteArray(32)

actual fun sha256(data: ByteArray): ByteArray =
    nobleSha256(data.toUint8Array()).toByteArray()

actual fun generatePrivateKey(): ByteArray = randomBytes(32).toByteArray()

actual fun derivePublicKey(privateKey: ByteArray): ByteArray {
    val compressed = secp256k1CompressedPublicKey(privateKey)
    return compressed.copyOfRange(1, 33)
}

actual fun schnorrSign(data: ByteArray, privateKey: ByteArray): ByteArray =
    NobleSchnorr.sign(data.toUint8Array(), privateKey.toUint8Array(), ZERO_AUX_RAND.toUint8Array()).toByteArray()

actual fun schnorrVerify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
    NobleSchnorr.verify(signature.toUint8Array(), data.toUint8Array(), publicKey.toUint8Array())

internal actual fun secp256k1CompressedPublicKey(privateKey: ByteArray): ByteArray =
    NobleSecp256k1.getPublicKey(privateKey.toUint8Array(), true).toByteArray()

internal actual fun secp256k1PublicKeyTweakMul(publicKey: ByteArray, tweak: ByteArray): ByteArray =
    pointMultiply(NobleSecp256k1.Point, publicKey.toUint8Array(), tweak.toUint8Array()).toByteArray()

// スカラーは BigInt で渡す必要があるため、変換を含めて JS 側で計算する。
// libsecp256k1 と同じく、tweak が 0 や曲線の位数以上なら例外になる。
private fun pointMultiply(point: JsAny, publicKey: Uint8Array, tweak: Uint8Array): Uint8Array =
    js(
        """
        point.fromBytes(publicKey)
            .multiply(BigInt('0x' + Array.from(tweak, b => b.toString(16).padStart(2, '0')).join('')))
            .toBytes(true)
        """,
    )

private fun randomBytes(size: Int): Uint8Array =
    js("crypto.getRandomValues(new Uint8Array(size))")

private fun ByteArray.toUint8Array(): Uint8Array {
    val array = Int8Array(size)
    for (i in indices) array[i] = this[i]
    return Uint8Array(array.buffer, array.byteOffset, array.length)
}

private fun Uint8Array.toByteArray(): ByteArray {
    val bytes = Int8Array(buffer, byteOffset, length)
    return ByteArray(length) { bytes[it] }
}
