package com.nostr.torinos.util

// Wasm はシングルスレッドで動くため、排他は不要。
internal actual class SynchronousLock actual constructor() {
    actual fun lock() = Unit

    actual fun unlock() = Unit
}
