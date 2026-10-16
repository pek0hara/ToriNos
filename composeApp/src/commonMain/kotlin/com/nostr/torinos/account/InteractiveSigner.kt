package com.nostr.torinos.account

import kotlinx.coroutines.flow.StateFlow

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

    /** [SignerAttention.Locked] を解く。生体認証のダイアログを出すので、ボタンの操作から呼ぶ。 */
    suspend fun unlock() {}

    /** [SignerAttention.Disconnected] から接続し直す。 */
    suspend fun reconnect() {}
}
