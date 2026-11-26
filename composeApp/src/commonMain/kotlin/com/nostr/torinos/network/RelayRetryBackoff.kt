package com.nostr.torinos.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * リレーへのつなぎ直しの待ち。失敗するたびに間隔を倍にし、[maxMillis] で止める。
 * バックグラウンド中は通信できずに待ちが延びるので、前面に戻ったら [wake] で待ちを切り上げ、間隔を最初に戻す。
 */
internal class RelayRetryBackoff(
    private val initialMillis: Long = 2_000L,
    private val maxMillis: Long = 30_000L,
) {
    // 試行中に来た知らせも残し、その試行が失敗した後の待ちを切り上げる。
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private var nextMillis = initialMillis

    /** 次に待つ時間（ログ用）。 */
    val nextDelayMillis: Long get() = nextMillis

    /** つながった（または試行を始める）。次に切れたら最初の間隔から待つ。それまでに来た [wake] は捨てる。 */
    fun reset() {
        nextMillis = initialMillis
        // 残すと、ずっと後に切れたときに待たずにつなぎ直してしまう。
        wakeSignal.tryReceive()
    }

    /** 次の試行まで待つ。[wake] が来たら切り上げ、間隔を最初に戻す。 */
    suspend fun await() {
        val millis = nextMillis
        nextMillis = (nextMillis * 2).coerceAtMost(maxMillis)
        val woken = withTimeoutOrNull(millis) { wakeSignal.receive() } != null
        if (woken) nextMillis = initialMillis
    }

    /** 待ちを切り上げる（前面に戻った・通信が戻ったとき）。 */
    fun wake() {
        wakeSignal.trySend(Unit)
    }
}
