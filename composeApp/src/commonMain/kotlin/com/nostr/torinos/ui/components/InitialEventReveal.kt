package com.nostr.torinos.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 初回イベントを「組み立て → 表示」の2段階で出すための状態。
 *
 * 組み立て済みで表示されない状態(透明なのに操作だけ効く)に取り残さないため、
 * 表示済みかどうかは [isContentVisible] だけで判定し、準備処理が中断・キャンセルされても
 * 必ず表示へ進める。
 */
internal class InitialEventReveal(staged: Boolean) {
    var isContentComposed by mutableStateOf(!staged)
        private set
    var isContentVisible by mutableStateOf(!staged)
        private set

    /** 何度呼ばれても、どこで中断されても、最終的に表示状態へ収束する。 */
    suspend fun reveal(beforeShow: suspend () -> Unit = {}) {
        if (isContentVisible) return
        isContentComposed = true
        try {
            beforeShow()
        } finally {
            isContentVisible = true
        }
    }

    /** 準備処理なしで即座に表示する(空状態の確定など)。 */
    fun showNow() {
        isContentComposed = true
        isContentVisible = true
    }
}
