package com.nostr.torinos.ui.feed

import com.nostr.torinos.util.feedTraceLog
import com.nostr.torinos.util.isFeedTraceEnabled

/**
 * フィード1つ分の読み込みの内訳を数え、まとめて1行で出す。無駄な通信の調査用。
 *
 * 受信1件ごとにログを出すと読めないので、件数だけ数えて [FeedController] が間隔をおいて [dump] する。
 * 値は累計。前回の出力から変化がなければ何も出さない。
 */
internal class FeedTrace(
    private val label: String,
    /** 数が変わったときに呼ぶ。出力の予約に使う。 */
    private val onChanged: () -> Unit = {},
) {
    private val counters = linkedMapOf<String, Int>()
    private var dirty = false

    fun count(name: String, delta: Int = 1) {
        if (!isFeedTraceEnabled || delta == 0) return
        counters[name] = (counters[name] ?: 0) + delta
        dirty = true
        onChanged()
    }

    fun log(message: () -> String) = feedTraceLog { "$label ${message()}" }

    /** 前回から変化があれば累計を出す。出したら true。 */
    fun dump(): Boolean {
        if (!isFeedTraceEnabled || !dirty) return false
        dirty = false
        feedTraceLog { "$label summary ${counters.entries.joinToString(" ") { (key, value) -> "$key=$value" }}" }
        return true
    }
}
