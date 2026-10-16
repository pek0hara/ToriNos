package com.nostr.torinos.journal

import com.nostr.torinos.network.RelayRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 取り終えなかったリレーだけを、間を空けて取り直す。フィードと同じく失敗はリレーごとに数え、
 * 待ち時間と諦める回数は[policy]に従う。同じリレーで待っている間に失敗した取得は、同じ取り直しにまとめる。
 * 状態の変更はすべて同じスレッド（コントローラーのスコープ）で行う前提。
 */
internal class JournalRelayRetries(
    private val launch: (suspend () -> Unit) -> Job,
    private val policy: RelayRetryPolicy = JOURNAL_RETRY_POLICY,
    private val log: (() -> String) -> Unit = {},
) {
    /** 取り直す処理。そのリレーで取り終えたら true。 */
    fun interface Work {
        suspend fun run(relayUrl: String): Boolean
    }

    private val failures = mutableMapOf<String, Int>()
    private val pending = mutableMapOf<String, MutableList<Work>>()
    private val timers = mutableMapOf<String, Job>()

    /** [relayUrl]で取り終えなかった[work]を取り直す。 */
    fun schedule(relayUrl: String, work: Work) {
        pending.getOrPut(relayUrl) { mutableListOf() } += work
        if (relayUrl in timers) return
        onFailed(relayUrl)
    }

    /** 待っている取り直しをすべてやめ、失敗の回数も戻す。 */
    fun cancelAll() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        pending.clear()
        failures.clear()
    }

    private fun onFailed(relayUrl: String) {
        val count = (failures[relayUrl] ?: 0) + 1
        failures[relayUrl] = count
        val delayMillis = policy.delayAfter(count)
        if (delayMillis == null) {
            val dropped = pending.remove(relayUrl)?.size ?: 0
            log { "relay gave up relay=$relayUrl failures=$count dropped=$dropped" }
            return
        }
        log { "retry relay=$relayUrl attempt=$count in=${delayMillis}ms works=${pending[relayUrl]?.size ?: 0}" }
        timers[relayUrl] = launch {
            delay(delayMillis)
            val works = pending.remove(relayUrl).orEmpty()
            val failed = works.filterNot { work -> runWork(work, relayUrl) }
            timers.remove(relayUrl)
            // 実行中に失敗した取得も、続けて取り直す。
            val remaining = failed + pending.remove(relayUrl).orEmpty()
            if (failed.isEmpty()) failures.remove(relayUrl)
            if (remaining.isEmpty()) {
                log { "retry done relay=$relayUrl" }
                return@launch
            }
            pending[relayUrl] = remaining.toMutableList()
            onFailed(relayUrl)
        }
    }
}

/** 例外も失敗として数える。止めたまま残ると、そのリレーの取り直しを受け付けなくなるため。 */
private suspend fun runWork(work: JournalRelayRetries.Work, relayUrl: String): Boolean =
    try {
        work.run(relayUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

/** フィードの履歴取得と同じ待ち時間と回数（3回続けて失敗したら諦める）。 */
internal val JOURNAL_RETRY_POLICY = RelayRetryPolicy.history(maxFailures = 3)
