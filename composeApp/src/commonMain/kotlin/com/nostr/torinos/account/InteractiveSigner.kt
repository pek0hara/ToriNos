package com.nostr.torinos.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 署名器が利用者の操作を待っている状態。画面上部の帯で知らせる。 */
sealed interface SignerAttention {
    /** パスキーで解錠するまで署名・暗号化できない。 */
    data object Locked : SignerAttention

    /** 署名器（NIP-46 の auth_url）での承認を待っている。 */
    data class ApprovalRequired(val url: String) : SignerAttention

    /** 署名器に接続できない。読むことはできる。 */
    data class Disconnected(val reason: String) : SignerAttention
}

/** 利用者の操作が必要になることがある署名器。 */
interface InteractiveSigner {
    val attention: StateFlow<SignerAttention?>

    /**
     * [attention] の操作が要るのに、それが済んでいないため断った操作の回数。
     * 利用者が帯を閉じた後でも、投稿などで解錠が要ったら帯を出し直すのに使う。
     */
    val attentionRequests: StateFlow<Long> get() = NoAttentionRequests

    /** [SignerAttention.Locked] を解く。生体認証のダイアログを出すので、ボタンの操作から呼ぶ。 */
    suspend fun unlock() {}

    /** [SignerAttention.Disconnected] から接続し直す。 */
    suspend fun reconnect() {}
}

private val NoAttentionRequests: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
