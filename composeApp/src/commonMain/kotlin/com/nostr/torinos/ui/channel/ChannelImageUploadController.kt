package com.nostr.torinos.ui.channel

import com.nostr.torinos.ui.SafeCoroutineLauncher
import kotlinx.coroutines.*

/** 閉じた/差し替えた画像の遅い完了を、現在の入力へ適用しない。 */
internal class ChannelImageUploadController(scope: CoroutineScope) {
    private val launcher = SafeCoroutineLauncher(scope, "ChannelImageUploadController")
    private var revision = 0L
    private var job: Job? = null

    fun <T> upload(load: suspend () -> Result<T>, apply: (Result<T>) -> Unit) {
        cancel()
        val request = revision
        job = launcher.launch {
            val result = try { withContext(Dispatchers.Default) { load() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { Result.failure(error) }
            if (request == revision) apply(result)
        }
    }

    fun cancel() { revision++; job?.cancel(); job = null }
}
