package com.nostr.torinos.engagement

import com.nostr.torinos.network.RelayPublishResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 送ったリポストの全リレーの結果。[authorPubkey] はリポストしたアカウント。 */
data class RepostNotice(val authorPubkey: String, val result: RelayPublishResult)

/**
 * リポストの送信完了の知らせ。リポストはどの画面からでも1タップで送れて誤操作しやすいので、
 * 投稿と同じ完了表示をアプリ全体のスナックバーで出す。
 */
object RepostNotices {
    private val notices = MutableSharedFlow<RepostNotice>(extraBufferCapacity = 16)
    val results: SharedFlow<RepostNotice> = notices.asSharedFlow()

    fun report(notice: RepostNotice) {
        notices.tryEmit(notice)
    }
}
