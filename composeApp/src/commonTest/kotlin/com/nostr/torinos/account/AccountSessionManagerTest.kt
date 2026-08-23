package com.nostr.torinos.account

import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class AccountSessionManagerTest {
    @Test
    fun initializeWithoutAccountCreatesAnonymousSession() = runBlocking {
        val manager = newManager(FakeAccountStorage())

        val result = manager.initialize().getOrThrow()

        assertTrue(result is AccountSessionState.Anonymous)
        assertEquals(null, manager.currentPubkey)
    }

    @Test
    fun switchAccountCreatesNewSession() = runBlocking {
        val storage = FakeAccountStorage(activePubkey = PUBKEY_A)
        val manager = newManager(storage)
        val first = manager.initialize().getOrThrow() as AccountSessionState.Active

        val second = manager.switchAccount(PUBKEY_B).getOrThrow()

        assertEquals(PUBKEY_B, second.pubkey)
        assertNotEquals(first.session.sessionId, second.sessionId)
        assertEquals(PUBKEY_B, manager.currentPubkey)
        assertTrue(first.session.followRepository !== second.followRepository)
        assertTrue(first.session.muteStore !== second.muteStore)
        assertTrue(first.session.relayStore !== second.relayStore)
        assertTrue(first.session.relayListSynchronizer !== second.relayListSynchronizer)
    }

    @Test
    fun switchingToCurrentAccountIsNoOp() = runBlocking {
        val manager = newManager(FakeAccountStorage(activePubkey = PUBKEY_A))
        val first = manager.initialize().getOrThrow() as AccountSessionState.Active

        val second = manager.switchAccount(PUBKEY_A).getOrThrow()

        assertEquals(first.session.sessionId, second.sessionId)
    }

    @Test
    fun switchingAccountInvalidatesPreviousSigner() = runBlocking {
        val manager = newManager(FakeAccountStorage(activePubkey = PUBKEY_A))
        val first = manager.initialize().getOrThrow() as AccountSessionState.Active

        manager.switchAccount(PUBKEY_B).getOrThrow()

        assertFailsWith<IllegalStateException> {
            first.session.signer.encryptToSelf("old session")
        }
        Unit
    }

    @Test
    fun failedSwitchCreatesFreshPreviousAccountSession() = runBlocking {
        val storage = FakeAccountStorage(activePubkey = PUBKEY_A, failSwitch = true)
        val manager = newManager(storage)
        val first = manager.initialize().getOrThrow() as AccountSessionState.Active

        val result = manager.switchAccount(PUBKEY_B)

        assertTrue(result.isFailure)
        val restored = manager.state.value as AccountSessionState.Active
        assertNotEquals(first.session.sessionId, restored.session.sessionId)
        assertEquals(PUBKEY_A, restored.session.pubkey)
        assertTrue(first.session.resources.isClosed)
        assertEquals("アカウントを切り替えられませんでした", manager.transitionError.value)
    }

    @Test
    fun failedInitializationAfterStorageSwitchRollsStorageBack() = runBlocking {
        val storage = FakeAccountStorage(activePubkey = PUBKEY_A, failLoadForPubkey = PUBKEY_B)
        val manager = newManager(storage)
        manager.initialize().getOrThrow()

        val result = manager.switchAccount(PUBKEY_B)

        assertTrue(result.isFailure)
        assertEquals(PUBKEY_A, storage.activePubkeyForTest)
        assertEquals(PUBKEY_A, manager.currentPubkey)
    }

    @Test
    fun logoutCreatesAnonymousSession() = runBlocking {
        val manager = newManager(FakeAccountStorage(activePubkey = PUBKEY_A))
        val active = manager.initialize().getOrThrow() as AccountSessionState.Active

        manager.logout().getOrThrow()

        assertTrue(manager.state.value is AccountSessionState.Anonymous)
        assertTrue(active.session.resources.isClosed)
    }

    @Test
    fun deletingCurrentAccountActivatesRemainingAccount() = runBlocking {
        val manager = newManager(FakeAccountStorage(activePubkey = PUBKEY_A))
        manager.initialize().getOrThrow()

        val result = manager.deleteCurrentAccount().getOrThrow()

        assertTrue(result is AccountSessionState.Active)
        assertEquals(PUBKEY_B, result.session.pubkey)
    }

    private class FakeAccountStorage(
        activePubkey: String? = null,
        private val failSwitch: Boolean = false,
        private val failLoadForPubkey: String? = null,
    ) : AccountStorage {
        private val accounts = linkedMapOf(
            PUBKEY_A to PRIVATE_KEY_A,
            PUBKEY_B to PRIVATE_KEY_B,
        )
        private var activePubkey = activePubkey
        private var loggedOut = activePubkey == null
        val activePubkeyForTest: String?
            get() = activePubkey

        override suspend fun loadActiveCredentials(): AccountCredentials? {
            if (loggedOut) return null
            val pubkey = activePubkey ?: return null
            if (pubkey == failLoadForPubkey) error("load failed")
            return AccountCredentials(pubkey, accounts.getValue(pubkey))
        }

        override suspend fun listAccounts(): List<StoredAccount> =
            accounts.keys.map { StoredAccount(pubkeyHex = it, npub = it) }

        override suspend fun switchAccount(pubkey: String) {
            if (failSwitch) error("switch failed")
            check(pubkey in accounts)
            activePubkey = pubkey
            loggedOut = false
        }

        override suspend fun logout() {
            loggedOut = true
        }

        override suspend fun deleteActiveAccount() {
            activePubkey?.let(accounts::remove)
            activePubkey = accounts.keys.firstOrNull()
            loggedOut = activePubkey == null
        }
    }

    private fun newManager(storage: AccountStorage): AccountSessionManager =
        AccountSessionManager(storage) { credentials, lease -> FakeSigner(credentials.pubkey, lease) }

    private class FakeSigner(
        override val pubkey: String,
        private val lease: AccountSessionLease,
    ) : AccountSigner {
        override fun encryptToSelf(plaintext: String): String = plaintext.also { lease.ensureActive() }

        override fun decrypt(content: String, peerPubkey: String): String = content.also { lease.ensureActive() }

        override fun sign(
            content: String,
            kind: Int,
            tags: List<List<String>>,
            createdAt: Long?,
        ): NostrEvent = error("not used")
    }

    private companion object {
        const val PUBKEY_A = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val PUBKEY_B = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"
        const val PRIVATE_KEY_A = "0000000000000000000000000000000000000000000000000000000000000001"
        const val PRIVATE_KEY_B = "0000000000000000000000000000000000000000000000000000000000000002"
    }
}
