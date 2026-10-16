package com.nostr.torinos.util

/** Androidではadb setprop log.tag.ToriNosRefactor DEBUGで有効化する。本文・鍵・タグ値は渡さない。 */
internal expect fun isRefactorTraceEnabled(): Boolean
internal expect fun writeRefactorTrace(message: String)

internal inline fun refactorTraceLog(component: String, message: () -> String) {
    if (isRefactorTraceEnabled()) writeRefactorTrace("[$component] ${message()}")
}

private val refactorTraceLock = SynchronousLock()
private var refactorTraceSequence = 0L
internal fun nextRefactorTraceId(): Long = if (isRefactorTraceEnabled()) {
    refactorTraceLock.withLock { ++refactorTraceSequence }
} else 0L
