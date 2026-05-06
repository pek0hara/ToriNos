package com.nostr.torinos.crypto

import kotlin.experimental.xor
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.floor
import kotlin.math.log2

private val NIP44_SALT = "nip44-v2".encodeToByteArray()
private val SECP256K1_ORDER = byteArrayOf(
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xfe.toByte(),
    0xba.toByte(), 0xae.toByte(), 0xdc.toByte(), 0xe6.toByte(),
    0xaf.toByte(), 0x48.toByte(), 0xa0.toByte(), 0x3b.toByte(),
    0xbf.toByte(), 0xd2.toByte(), 0x5e.toByte(), 0x8c.toByte(),
    0xd0.toByte(), 0x36.toByte(), 0x41.toByte(), 0x41.toByte(),
)

object Nip44 {
    fun encrypt(plaintext: String, privateKeyHex: String, publicKeyHex: String): String {
        val conversationKey = conversationKey(privateKeyHex, publicKeyHex)
        return encryptWithConversationKey(plaintext, conversationKey, generatePrivateKey())
    }

    fun decrypt(payload: String, privateKeyHex: String, publicKeyHex: String): String {
        val conversationKey = conversationKey(privateKeyHex, publicKeyHex)
        return decryptWithConversationKey(payload, conversationKey)
    }

    fun conversationKey(privateKeyHex: String, publicKeyHex: String): ByteArray {
        val privateKey = normalizeBip340PrivateKey(privateKeyHex.fromHex())
        val publicKey = compressedEvenPublicKey(publicKeyHex.fromHex())
        val sharedPoint = secp256k1PublicKeyTweakMul(publicKey, privateKey)
        val sharedX = sharedPoint.copyOfRange(1, 33)
        return hmacSha256(NIP44_SALT, sharedX)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encryptWithConversationKey(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): String {
        require(conversationKey.size == 32) { "invalid conversation key length" }
        require(nonce.size == 32) { "invalid nonce length" }
        val keys = messageKeys(conversationKey, nonce)
        val padded = pad(plaintext)
        val ciphertext = chacha20(keys.chachaKey, keys.chachaNonce, padded)
        val mac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        return Base64.encode(byteArrayOf(2) + nonce + ciphertext + mac)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decryptWithConversationKey(payload: String, conversationKey: ByteArray): String {
        require(conversationKey.size == 32) { "invalid conversation key length" }
        if (payload.isEmpty() || payload.first() == '#') error("unsupported nip44 version")
        require(payload.length in 132..87472) { "invalid payload size" }
        val decoded = Base64.decode(payload)
        require(decoded.size in 99..65603) { "invalid data size" }
        require(decoded[0].toInt() == 2) { "unsupported nip44 version" }
        val nonce = decoded.copyOfRange(1, 33)
        val ciphertext = decoded.copyOfRange(33, decoded.size - 32)
        val mac = decoded.copyOfRange(decoded.size - 32, decoded.size)
        val keys = messageKeys(conversationKey, nonce)
        val calculatedMac = hmacSha256(keys.hmacKey, nonce + ciphertext)
        require(constantTimeEquals(mac, calculatedMac)) { "invalid MAC" }
        return unpad(chacha20(keys.chachaKey, keys.chachaNonce, ciphertext))
    }
}

private data class MessageKeys(
    val chachaKey: ByteArray,
    val chachaNonce: ByteArray,
    val hmacKey: ByteArray,
)

private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
    val expanded = hkdfExpand(conversationKey, nonce, 76)
    return MessageKeys(
        chachaKey = expanded.copyOfRange(0, 32),
        chachaNonce = expanded.copyOfRange(32, 44),
        hmacKey = expanded.copyOfRange(44, 76),
    )
}

private fun compressedEvenPublicKey(xOnlyPublicKey: ByteArray): ByteArray {
    require(xOnlyPublicKey.size == 32) { "invalid x-only public key length" }
    return byteArrayOf(2) + xOnlyPublicKey
}

private fun normalizeBip340PrivateKey(privateKey: ByteArray): ByteArray {
    require(privateKey.size == 32) { "invalid private key length" }
    val compressed = secp256k1CompressedPublicKey(privateKey)
    return if (compressed[0] == 3.toByte()) subtract(SECP256K1_ORDER, privateKey) else privateKey
}

private fun subtract(a: ByteArray, b: ByteArray): ByteArray {
    require(a.size == b.size)
    val out = ByteArray(a.size)
    var borrow = 0
    for (i in a.indices.reversed()) {
        var value = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff) - borrow
        if (value < 0) {
            value += 256
            borrow = 1
        } else {
            borrow = 0
        }
        out[i] = value.toByte()
    }
    return out
}

private fun pad(plaintext: String): ByteArray {
    val bytes = plaintext.encodeToByteArray()
    require(bytes.size in 1..65535) { "invalid plaintext length" }
    val paddedLen = calcPaddedLen(bytes.size)
    return byteArrayOf(((bytes.size ushr 8) and 0xff).toByte(), (bytes.size and 0xff).toByte()) +
        bytes +
        ByteArray(paddedLen - bytes.size)
}

private fun unpad(padded: ByteArray): String {
    require(padded.size >= 34) { "invalid padding" }
    val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
    require(len in 1..65535) { "invalid padding" }
    require(padded.size == 2 + calcPaddedLen(len)) { "invalid padding" }
    return padded.copyOfRange(2, 2 + len).decodeToString()
}

private fun calcPaddedLen(unpaddedLen: Int): Int {
    require(unpaddedLen in 1..65535)
    if (unpaddedLen <= 32) return 32
    val nextPower = 1 shl (floor(log2((unpaddedLen - 1).toDouble())).toInt() + 1)
    val chunk = if (nextPower <= 256) 32 else nextPower / 8
    return chunk * (((unpaddedLen - 1) / chunk) + 1)
}

private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        val toCopy = minOf(previous.size, length - offset)
        previous.copyInto(out, offset, 0, toCopy)
        offset += toCopy
        counter++
    }
    return out
}

private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val blockSize = 64
    val actualKey = if (key.size > blockSize) sha256(key) else key
    val paddedKey = actualKey.copyOf(blockSize)
    val outer = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x5c).toByte() }
    val inner = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x36).toByte() }
    return sha256(outer + sha256(inner + message))
}

private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

private fun chacha20(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
    require(key.size == 32) { "invalid chacha key length" }
    require(nonce.size == 12) { "invalid chacha nonce length" }
    val out = ByteArray(data.size)
    var counter = 0
    var offset = 0
    while (offset < data.size) {
        val block = chacha20Block(key, nonce, counter)
        val toCopy = minOf(64, data.size - offset)
        for (i in 0 until toCopy) {
            out[offset + i] = data[offset + i] xor block[i]
        }
        offset += toCopy
        counter++
    }
    return out
}

private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
    val state = IntArray(16)
    state[0] = 0x61707865
    state[1] = 0x3320646e
    state[2] = 0x79622d32
    state[3] = 0x6b206574
    for (i in 0 until 8) state[4 + i] = key.readIntLe(i * 4)
    state[12] = counter
    state[13] = nonce.readIntLe(0)
    state[14] = nonce.readIntLe(4)
    state[15] = nonce.readIntLe(8)

    val working = state.copyOf()
    repeat(10) {
        quarterRound(working, 0, 4, 8, 12)
        quarterRound(working, 1, 5, 9, 13)
        quarterRound(working, 2, 6, 10, 14)
        quarterRound(working, 3, 7, 11, 15)
        quarterRound(working, 0, 5, 10, 15)
        quarterRound(working, 1, 6, 11, 12)
        quarterRound(working, 2, 7, 8, 13)
        quarterRound(working, 3, 4, 9, 14)
    }
    val out = ByteArray(64)
    for (i in 0 until 16) {
        out.writeIntLe(i * 4, working[i] + state[i])
    }
    return out
}

private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
    x[a] += x[b]
    x[d] = (x[d] xor x[a]).rotateLeft(16)
    x[c] += x[d]
    x[b] = (x[b] xor x[c]).rotateLeft(12)
    x[a] += x[b]
    x[d] = (x[d] xor x[a]).rotateLeft(8)
    x[c] += x[d]
    x[b] = (x[b] xor x[c]).rotateLeft(7)
}

private fun ByteArray.readIntLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)

private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}
