package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals

class PublishAuthRetryTest {
    @Test
    fun authRequiredRejectionIsRetriedOnlyWhereAuthIsAllowed() {
        assertEquals(true, shouldRetryPublishAfterAuth("auth-required: you must auth", awaitAcceptance = true, canAuthenticate = true))
        // 認証を許していないリレーでは、公開鍵を知らせないため認証しない。
        assertEquals(false, shouldRetryPublishAfterAuth("auth-required: you must auth", awaitAcceptance = true, canAuthenticate = false))
        // 受理を待たない送信では、拒否を知りようがない。
        assertEquals(false, shouldRetryPublishAfterAuth("auth-required: you must auth", awaitAcceptance = false, canAuthenticate = true))
        assertEquals(false, shouldRetryPublishAfterAuth("blocked: spam", awaitAcceptance = true, canAuthenticate = true))
        assertEquals(false, shouldRetryPublishAfterAuth("restricted: not a member", awaitAcceptance = true, canAuthenticate = true))
    }
}
