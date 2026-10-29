package com.nostr.torinos

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.post.PostCompletion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 投稿の送信結果のスナックバー。失敗したリレーを表示ごとに持ち、続けて投稿しても取り違えない。 */
internal class PublishResultSnackbarVisuals(result: RelayPublishResult) : SnackbarVisuals {
    val failedRelays: List<String> = result.failedRelays.keys.toList()
    override val message: String = if (result.failureCount == 0) {
        "すべてのリレーに送信成功しました。"
    } else {
        "送信しました。成功${result.successCount}リレー 失敗${result.failureCount}リレー"
    }
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration =
        if (result.failureCount > 0) SnackbarDuration.Long else SnackbarDuration.Short
}

/**
 * 投稿完了の知らせ。下書きを消せなかった等の警告はすぐ出す。
 * リレーごとの結果は全リレーがそろってから出す（応答しないリレーがあると時間切れまでかかる）。
 */
internal suspend fun showPostCompletionSnackbars(host: SnackbarHostState, completion: PostCompletion) = coroutineScope {
    val warning = completion.warning
    if (warning != null) {
        // 先に表示の順番を取る。結果がすでにそろっていても警告が先に出る。
        launch(start = CoroutineStart.UNDISPATCHED) {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message = warning, duration = SnackbarDuration.Long)
        }
    }
    val result = completion.finalResult.await()
    // 警告が出ているときは消さずに後ろへ並べる。
    if (warning == null) host.currentSnackbarData?.dismiss()
    host.showSnackbar(PublishResultSnackbarVisuals(result))
}
