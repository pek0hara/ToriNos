package com.nostr.torinos.util

import kotlin.time.TimeSource

/**
 * 起動からフィード表示までの節目を、起動からの経過時間つきで1行ずつ出す。
 *
 * 同じ節目は最初の1回だけ出す（2回目以降のタブ切り替えや再表示は数えない）。
 * 起点はこのオブジェクトに最初に触れた時点。Android では Application.onCreate で触れ、
 * プロセス起動からそこまでの時間は [processOffsetMillis] で別に渡す。
 */
object StartupTrace {
    private val origin = TimeSource.Monotonic.markNow()
    private val lock = SynchronousLock()
    private val marked = mutableSetOf<String>()

    /** プロセス起動から起点までの時間。分かるプラットフォームだけが設定する。 */
    var processOffsetMillis: Long = 0L

    fun elapsedMillis(): Long = processOffsetMillis + origin.elapsedNow().inWholeMilliseconds

    /** [name] の節目を初回だけ出す。出したら true。 */
    fun mark(name: String, detail: () -> String = { "" }): Boolean {
        if (!isStartupTraceEnabled) return false
        if (!lock.withLock { marked.add(name) }) return false
        val text = detail()
        platformLog("[Startup] +${elapsedMillis()}ms $name${if (text.isEmpty()) "" else " $text"}")
        return true
    }

    fun isMarked(name: String): Boolean = lock.withLock { name in marked }
}
