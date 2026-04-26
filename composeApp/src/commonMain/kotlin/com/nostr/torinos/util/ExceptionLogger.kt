package com.nostr.torinos.util

import kotlinx.coroutines.CoroutineExceptionHandler

fun logException(tag: String, throwable: Throwable, context: String? = null) {
    val prefix = if (context.isNullOrBlank()) "[$tag]" else "[$tag] $context"
    try {
        appLog("$prefix ${throwable::class.simpleName}: ${throwable.message}")
        throwable.cause?.let { cause ->
            appLog("$prefix Caused by ${cause::class.simpleName}: ${cause.message}")
        }
        try {
            appLog(throwable.stackTraceToString())
        } catch (_: Throwable) {
            appLog("$prefix (stacktrace unavailable)")
        }
    } catch (_: Throwable) {
        // logException itself must never throw
    }
}

fun loggingExceptionHandler(tag: String, context: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        logException(tag, e, context)
    }
