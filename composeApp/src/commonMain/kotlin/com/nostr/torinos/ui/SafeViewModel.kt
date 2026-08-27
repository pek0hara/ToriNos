package com.nostr.torinos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job

/**
 * iOS でコルーチンの未捕捉例外によるクラッシュを防ぐ基底クラス。
 * viewModelScope.launch の代わりに launch を使う。
 * CoroutineExceptionHandler に加え、直接 try/catch でも包む（Kotlin/Native 対策）。
 */
abstract class SafeViewModel : ViewModel() {
    private val safeCoroutineLauncher by lazy {
        SafeCoroutineLauncher(viewModelScope, "SafeViewModel")
    }

    fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = safeCoroutineLauncher.launch(start, block)
}
