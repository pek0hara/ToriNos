package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkPreviewRepositoryTest {
    @Test
    fun safeUrl_allowsPublicHttpsHost() {
        assertTrue(isSafeLinkPreviewUrl("https://example.com/post"))
    }

    @Test
    fun safeUrl_rejectsPlainHttp() {
        assertFalse(isSafeLinkPreviewUrl("http://example.com/post"))
    }

    @Test
    fun safeUrl_rejectsLocalhost() {
        assertFalse(isSafeLinkPreviewUrl("https://localhost/status"))
        assertFalse(isSafeLinkPreviewUrl("https://app.localhost/status"))
    }

    @Test
    fun safeUrl_rejectsPrivateIpv4Literals() {
        assertFalse(isSafeLinkPreviewUrl("https://127.0.0.1/status"))
        assertFalse(isSafeLinkPreviewUrl("https://10.0.0.1/status"))
        assertFalse(isSafeLinkPreviewUrl("https://172.16.0.1/status"))
        assertFalse(isSafeLinkPreviewUrl("https://192.168.0.1/status"))
        assertFalse(isSafeLinkPreviewUrl("https://169.254.1.1/status"))
    }
}
