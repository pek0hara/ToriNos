package com.nostr.torinos.util

expect fun platformLog(message: String)

fun appLog(message: String) {
    platformLog(message)
}

private const val ENABLE_NETWORK_TRACE_LOGS = false

internal inline fun networkTraceLog(message: () -> String) {
    if (ENABLE_NETWORK_TRACE_LOGS) {
        platformLog(message())
    }
}
