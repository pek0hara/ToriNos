package com.nostr.torinos.ui.components

import com.nostr.torinos.account.SignerAttention
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignerStatusBannerLogicTest {
    @Test
    fun attentionIsShownUntilDismissed() {
        assertTrue(isSignerAttentionShown(SignerAttention.Locked, requestCount = 0, dismissal = null))
        assertFalse(isSignerAttentionShown(SignerAttention.Locked, 0, SignerBannerDismissal(SignerAttention.Locked, 0)))
    }

    @Test
    fun nothingIsShownWithoutAttention() {
        assertFalse(isSignerAttentionShown(null, 0, null))
    }

    @Test
    fun dismissedAttentionReturnsWhenAnOperationNeedsIt() {
        // 「後で」で閉じた後でも、投稿などで解錠が要ったら出し直す。
        val dismissal = SignerBannerDismissal(SignerAttention.Locked, requestCount = 2)
        assertFalse(isSignerAttentionShown(SignerAttention.Locked, 2, dismissal))
        assertTrue(isSignerAttentionShown(SignerAttention.Locked, 3, dismissal))
    }

    @Test
    fun dismissalDoesNotHideADifferentAttention() {
        val dismissal = SignerBannerDismissal(SignerAttention.Disconnected("timeout"), requestCount = 0)
        assertTrue(isSignerAttentionShown(SignerAttention.Disconnected("closed"), 0, dismissal))
        assertTrue(isSignerAttentionShown(SignerAttention.Locked, 0, dismissal))
    }
}
