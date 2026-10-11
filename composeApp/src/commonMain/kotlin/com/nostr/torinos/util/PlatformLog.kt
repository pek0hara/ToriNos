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

/** trueにすると購読ごと・リレーごとの通信の流れ（REQ/件数/EOSE/CLOSED/完了）をログ出力する。動作確認用。 */
internal const val isFetchTraceEnabled = false

internal inline fun fetchTraceLog(message: () -> String) {
    if (isFetchTraceEnabled) {
        platformLog("[Fetch] ${message()}")
    }
}

/** trueにするとフィードとポスト詳細のリアクション等の取得計画・結果・合算をログ出力する。動作確認用。 */
internal const val isEngagementTraceEnabled = false

internal inline fun engagementTraceLog(message: () -> String) {
    if (isEngagementTraceEnabled) {
        platformLog("[Engagement] ${message()}")
    }
}

/**
 * trueにするとフィードの読み込みのきっかけ（初回・更新・復帰・もっと読む・追いつき・再試行）と、
 * ページごとの受信件数・新規件数・重複件数、引用・リポスト元・プロフィールの追加取得をログ出力する。
 * 無駄な通信の調査用。購読IDを出すので、[Fetch] と並べると REQ と対応づけられる。
 */
internal const val isFeedTraceEnabled = false

internal inline fun feedTraceLog(message: () -> String) {
    if (isFeedTraceEnabled) {
        platformLog("[Feed] ${message()}")
    }
}

/**
 * trueにすると起動からフィード表示までの節目（アカウント・フォロー一覧・リレー接続・REQ・初受信・
 * 一覧への反映・画面への表示）を、起動からの経過ミリ秒つきでログ出力する。表示までの時間の調査用。
 */
internal const val isStartupTraceEnabled = false
