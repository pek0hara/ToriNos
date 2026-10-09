@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package com.nostr.torinos.ui.article

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun ArticleEditorBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val callback = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val guard = if (enabled) installArticleHistoryGuard { callback.value() } else null
        onDispose { if (guard != null) removeArticleHistoryGuard(guard) }
    }
}
private fun installArticleHistoryGuard(onBack: () -> Unit): JsAny = js("""
(() => {
    let restoring = false;
    const originalUrl = location.href;
    const handler = event => {
        event.stopImmediatePropagation();
        if (restoring) {
            restoring = false;
            queueMicrotask(() => onBack());
        } else {
            restoring = true;
            history.forward();
        }
    };
    window.addEventListener('popstate', handler, true);
    window.__torinosArticleHistoryGuard = handler;
    return handler;
})()
""")
private fun removeArticleHistoryGuard(guard: JsAny): Unit = js("""
{
    window.removeEventListener('popstate', guard, true);
    if (window.__torinosArticleHistoryGuard === guard) delete window.__torinosArticleHistoryGuard;
}
""")
internal actual fun releaseArticleEditorNavigationGuard(): Unit = js("""
{
    const guard = window.__torinosArticleHistoryGuard;
    if (guard) { window.removeEventListener('popstate', guard, true); delete window.__torinosArticleHistoryGuard; }
}
""")
