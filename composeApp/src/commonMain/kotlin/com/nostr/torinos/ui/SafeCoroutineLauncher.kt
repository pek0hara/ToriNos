package com.nostr.torinos.ui

import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 親 [CoroutineScope] のライフサイクルを維持しながら、未捕捉例外をアプリ終了まで到達させないランチャー。
 * キャンセルは通常どおり伝播し、それ以外の例外はログへ記録する。
 */
internal class SafeCoroutineLauncher(
    private val scope: CoroutineScope,
    private val tag: String,
    private val reportError: (Throwable) -> Unit = { error ->
        logException(tag, error, "Caught in safe coroutine launcher")
    },
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        reportSafely(error)
    }

    fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch(exceptionHandler, start = start) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportSafely(error)
        }
    }

    private fun reportSafely(error: Throwable) {
        try {
            reportError(error)
        } catch (_: Throwable) {
            // エラー報告の失敗を新たな未捕捉例外にしない。
        }
    }
}
