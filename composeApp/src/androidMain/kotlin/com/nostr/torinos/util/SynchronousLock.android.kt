package com.nostr.torinos.util

import java.util.concurrent.locks.ReentrantLock

internal actual class SynchronousLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
