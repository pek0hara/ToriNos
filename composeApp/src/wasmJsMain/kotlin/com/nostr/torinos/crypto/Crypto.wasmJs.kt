package com.nostr.torinos.crypto

actual fun sha256(data: ByteArray): ByteArray = throw UnsupportedOperationException("Not supported on web")
actual fun generatePrivateKey(): ByteArray = throw UnsupportedOperationException("Not supported on web")
actual fun derivePublicKey(privateKey: ByteArray): ByteArray = throw UnsupportedOperationException("Not supported on web")
actual fun schnorrSign(data: ByteArray, privateKey: ByteArray): ByteArray = throw UnsupportedOperationException("Not supported on web")
