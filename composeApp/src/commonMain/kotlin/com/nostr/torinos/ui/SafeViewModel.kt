package com.nostr.torinos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.torinos.util.logException
import com.nostr.torinos.util.loggingExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val handler = loggingExceptionHandler("ViewModel", "Uncaught coroutine exception")

/**
 * iOS でコルーチンの未捕捉例外によるクラッシュを防ぐ基底クラス。
 * viewModelScope.launch の代わりに launch を使う。
 * CoroutineExceptionHandler に加え、直接 try/catch でも包む（Kotlin/Native 対策）。
 */
abstract class SafeViewModel : ViewModel() {
    fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = viewModelScope.launch(handler, start = start) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("SafeViewModel", e, "Caught in launch wrapper")
        }
    }
}
