package com.nostr.torinos

import com.nostr.torinos.util.refactorTraceLog
import com.nostr.torinos.ui.SafeCoroutineLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** 遅い返信解決の結果を、別の入力・終了済みセッションへ適用しない。 */
internal class ReplyComposerResolver(scope: CoroutineScope, private val canApply: () -> Boolean = { true }) {
    private val launcher = SafeCoroutineLauncher(scope, "ReplyComposerResolver")
    private val tracker = ReplyResolutionTracker()
    private var job: Job? = null

    fun cancel() {
        tracker.invalidate()
        job?.cancel()
        job = null
    }

    fun <T> resolve(load: suspend () -> T, isCurrentInput: () -> Boolean = { true }, apply: suspend (T) -> Unit) {
        cancel()
        val request = tracker.begin()
        refactorTraceLog("Reply") { "start request=$request" }
        job = launcher.launch {
            val result = load()
            val currentRequest = tracker.isCurrent(request)
            val allowed = canApply()
            val currentInput = isCurrentInput()
            refactorTraceLog("Reply") { "resolved request=$request current=$currentRequest allowed=$allowed inputCurrent=$currentInput action=${if (currentRequest && allowed && currentInput) "apply" else "discard"}" }
            if (currentRequest && allowed && currentInput) apply(result)
        }
    }
}
