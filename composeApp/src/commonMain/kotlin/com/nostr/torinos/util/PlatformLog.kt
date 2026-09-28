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

/** trueにするとジャーナルの取得計画・取得結果・状態の変化をログ出力する。動作確認用。 */
private const val ENABLE_JOURNAL_TRACE_LOGS = true

internal inline fun journalTraceLog(message: () -> String) {
    if (ENABLE_JOURNAL_TRACE_LOGS) {
        platformLog("[Journal] ${message()}")
    }
}
