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

/** trueにすると各プロセスメモリキャッシュのhit/miss/evictionをログ出力する。動作確認用。 */
private const val ENABLE_CACHE_TRACE_LOGS = false

internal inline fun cacheTraceLog(message: () -> String) {
    if (ENABLE_CACHE_TRACE_LOGS) {
        platformLog(message())
    }
}
