@file:JsModule("@noble/curves/secp256k1.js")

package com.nostr.torinos.crypto

import org.khronos.webgl.Uint8Array

/** `@noble/curves` の BIP340 Schnorr 署名。 */
@JsName("schnorr")
internal external object NobleSchnorr : JsAny {
    fun sign(message: Uint8Array, secretKey: Uint8Array, auxRand: Uint8Array): Uint8Array
    fun verify(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array): Boolean
}

/** `@noble/curves` の secp256k1 曲線。 */
@JsName("secp256k1")
internal external object NobleSecp256k1 : JsAny {
    fun getPublicKey(secretKey: Uint8Array, isCompressed: Boolean): Uint8Array
    val Point: JsAny
}
