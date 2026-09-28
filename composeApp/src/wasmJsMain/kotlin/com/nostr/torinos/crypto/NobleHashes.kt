@file:JsModule("@noble/hashes/sha2.js")

package com.nostr.torinos.crypto

import org.khronos.webgl.Uint8Array

/** `@noble/hashes` の SHA-256。 */
@JsName("sha256")
internal external fun nobleSha256(data: Uint8Array): Uint8Array
