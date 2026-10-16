package com.nostr.torinos.account

import com.nostr.torinos.crypto.signEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AccountSignerTest {
    // BIP340 テストベクタの秘密鍵と公開鍵。
    private val key = "0000000000000000000000000000000000000000000000000000000000000003"
    private val pubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    private val otherPubkey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"

    @Test
    fun externalCredentialsRoundTripThroughTheStoredString() {
        val credentials = listOf(
            SignerCredential.Nip07,
            SignerCredential.Nip46("11".repeat(32), otherPubkey, listOf("wss://relay.example")),
            SignerCredential.Passkey("cred", "salt", "iv", "cipher"),
        )
        credentials.forEach { credential ->
            val stored = AccountSecretCodec.encode(credential)
            assertEquals(AccountSecret.External(credential), AccountSecretCodec.decode(pubkey, stored))
            assertEquals(stored, AccountSecretCodec.normalizeStored(pubkey, stored))
            assertEquals(credential.method, AccountSecretCodec.methodOf(pubkey, stored))
        }
    }

    @Test
    fun privateKeyMustBelongToTheAccount() {
        assertEquals(AccountSecret.PrivateKey(key), AccountSecretCodec.decode(pubkey, key.uppercase()))
        assertNull(AccountSecretCodec.decode(otherPubkey, key))
        assertNull(AccountSecretCodec.decode(pubkey, "torinos-signer:v1:{\"type\":\"unknown\"}"))
    }

    @Test
    fun signedResponseMustMatchTheRequest() {
        val request = UnsignedEvent(pubkey, createdAt = 100, kind = 1, tags = listOf(listOf("t", "a")), content = "hi")
        val signed = signEvent(privateKeyHex = key, content = "hi", kind = 1, tags = listOf(listOf("t", "a")), createdAt = 100)
        assertEquals(signed, checkSignedResponse(request, signed))

        listOf(
            request.copy(content = "other"),
            request.copy(kind = 7),
            request.copy(tags = emptyList()),
            request.copy(createdAt = 101),
            request.copy(pubkey = otherPubkey),
        ).forEach { changed ->
            assertFailsWith<SignerException.InvalidResponse> { checkSignedResponse(changed, signed) }
        }
        assertFailsWith<SignerException.InvalidResponse> {
            checkSignedResponse(request, signed.copy(sig = "00".repeat(64)))
        }
    }

    @Test
    fun privateKeySignerEncryptsForPeersAndStopsAfterTheSessionEnds() = runTest {
        val lease = AccountSessionLease()
        val signer = PrivateKeyAccountSigner(key, lease)
        assertEquals(pubkey, signer.pubkey)
        assertEquals("secret", signer.decrypt(signer.encryptToSelf("secret"), pubkey))

        lease.invalidate()
        assertFailsWith<AccountSessionEndedException> { signer.sign("after") }
    }

    @Test
    fun decodingKeepsSignerFailuresButHidesBrokenContent() = runTest {
        assertNull(decodeUnlessSignerFails<String> { error("壊れた内容") })
        assertIs<SignerException.Timeout>(
            assertFailsWith<SignerException.Timeout> { decodeUnlessSignerFails<String> { throw SignerException.Timeout() } },
        )
        assertFailsWith<AccountSessionEndedException> {
            decodeUnlessSignerFails<String> { throw AccountSessionEndedException() }
        }
    }
}

class ArticleSaveIntervalTest {
    @Test
    fun externalSignersSaveLessOften() {
        assertEquals(10_000L to 60_000L, com.nostr.torinos.ui.article.articleSaveIntervals(SignerMethod.PrivateKey, 10_000L, 60_000L))
        assertEquals(10_000L to 60_000L, com.nostr.torinos.ui.article.articleSaveIntervals(SignerMethod.Passkey, 10_000L, 60_000L))
        assertEquals(60_000L to 300_000L, com.nostr.torinos.ui.article.articleSaveIntervals(SignerMethod.Nip46, 10_000L, 60_000L))
        assertEquals(60_000L to 300_000L, com.nostr.torinos.ui.article.articleSaveIntervals(SignerMethod.Nip07, 10_000L, 60_000L))
    }
}
