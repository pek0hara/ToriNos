package com.nostr.torinos.ui.setup

import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.crypto.normalizePrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrivateKeyFileTest {
    private val hex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
    private val nsec = hexToNsec(hex)

    @Test
    fun plainNsecWithTrailingNewline() {
        assertEquals(nsec, extractPrivateKeyFromFile("$nsec\n"))
    }

    @Test
    fun nsecInsideJson() {
        val json = """{"pubkey":"${"a".repeat(64)}","nsec":"$nsec"}"""
        assertEquals(nsec, extractPrivateKeyFromFile(json))
        assertEquals(hex, normalizePrivateKey(extractPrivateKeyFromFile(json)!!))
    }

    @Test
    fun bomAndCrLf() {
        assertEquals(nsec, extractPrivateKeyFromFile("﻿$nsec\r\n"))
    }

    @Test
    fun hexOnly() {
        assertEquals(hex, extractPrivateKeyFromFile("  $hex  "))
    }

    @Test
    fun longerHexIsNotTakenAsKey() {
        assertNull(extractPrivateKeyFromFile("a".repeat(128)))
    }

    @Test
    fun nothingFound() {
        assertNull(extractPrivateKeyFromFile("hello"))
    }

    @Test
    fun encryptedKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            extractPrivateKeyFromFile("ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p")
        }
    }
}
