package com.nostr.torinos.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WebSignersTest {
    @Test
    fun cancelledPasskeyCreationIsRejected() {
        assertIs<SignerException.Rejected>(passkeyFailure(WebSignerCallFailed("NotAllowedError"), PasskeyStep.Create))
        assertIs<SignerException.Rejected>(passkeyFailure(WebSignerCallFailed("AbortError"), PasskeyStep.Unlock))
    }

    @Test
    fun notAllowedUnlockMentionsAMissingPasskey() {
        // NotAllowedError は取り消しと、このブラウザで使えるパスキーが無いときの両方で返る。
        val error = passkeyFailure(WebSignerCallFailed("NotAllowedError"), PasskeyStep.Unlock)
        assertIs<SignerException.Unavailable>(error)
        assertTrue(error.message!!.contains("見つかりません"), error.message)
    }

    @Test
    fun wrongPasskeyIsExplained() {
        val error = passkeyFailure(WebSignerCallFailed("OperationError"), PasskeyStep.Unlock)
        assertIs<SignerException.Unavailable>(error)
        assertTrue(error.message!!.contains("保存したときのパスキー"), error.message)
    }

    @Test
    fun lockedPasskeySignerCountsOperationsThatNeedUnlocking() = runTest {
        val signer = PasskeyAccountSigner(
            pubkey = PUBKEY,
            credential = SignerCredential.Passkey(credentialId = "id", prfSalt = "salt", iv = "iv", ciphertext = "data"),
            lease = AccountSessionLease(),
        )
        assertEquals(SignerAttention.Locked, signer.attention.value)
        assertEquals(0L, signer.attentionRequests.value)

        repeat(2) { assertIs<SignerException.Locked>(runCatching { signer.sign("hello") }.exceptionOrNull()) }
        runCatching { signer.nip44Encrypt("hello", PUBKEY) }

        assertEquals(3L, signer.attentionRequests.value)
    }

    private companion object {
        const val PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }
}
