package com.nostr.torinos.util

/** 短いメモリ操作だけを直列化するプラットフォーム同期ロック。 */
internal expect class SynchronousLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> SynchronousLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
