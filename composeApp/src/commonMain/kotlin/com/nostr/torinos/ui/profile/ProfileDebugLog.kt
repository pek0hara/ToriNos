package com.nostr.torinos.ui.profile

/**
 * 他人のプロフィール初回オープン時の遅延調査用の一時ログ。
 * 原因の切り分けが済んだら呼び出し箇所ごと削除すること。
 */
internal expect fun profileDebugLog(message: String)
