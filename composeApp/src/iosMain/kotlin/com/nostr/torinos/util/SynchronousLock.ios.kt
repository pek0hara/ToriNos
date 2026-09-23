package com.nostr.torinos.util

import platform.Foundation.NSLock

internal actual class SynchronousLock actual constructor() {
    private val delegate = NSLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
