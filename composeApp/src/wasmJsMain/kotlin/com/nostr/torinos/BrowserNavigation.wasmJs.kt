package com.nostr.torinos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavHostController
import androidx.navigation.bindToBrowserNavigation
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalBrowserHistoryApi::class)
@Composable
internal actual fun BindBrowserNavigation(navController: NavHostController) {
    // NavHost がグラフを設定した後、画面履歴をブラウザの戻る／進むに同期する。
    LaunchedEffect(navController) {
        // initial-hash.js は読み込み中だけ URL の # を外し、load で戻す。
        // 起動時の画面は URL の # から決まるため、戻った後で同期を始める。
        awaitWindowLoad()
        navController.bindToBrowserNavigation()
    }
}

private suspend fun awaitWindowLoad() {
    if (isDocumentLoaded()) return
    suspendCancellableCoroutine { continuation ->
        onWindowLoad { if (continuation.isActive) continuation.resume(Unit) }
    }
}

private fun isDocumentLoaded(): Boolean = js("document.readyState === 'complete'")

private fun onWindowLoad(callback: () -> Unit) {
    js("window.addEventListener('load', () => callback(), { once: true })")
}
