package com.nostr.torinos.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserKeyStoreTest {

    private class MemoryKeyValueStore : KeyValueStore {
        val values = linkedMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    // BIP340 テストベクタの秘密鍵と公開鍵。
    private val keyA = "0000000000000000000000000000000000000000000000000000000000000003"
    private val pubA = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    private val keyB = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef"
    private val pubB = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"

    private val memory = MemoryKeyValueStore()
    private val store = BrowserKeyStore(memory)

    @Test
    fun emptyStoreHasNoKey() {
        assertNull(store.loadPrivateKey())
        assertTrue(store.listAccounts().isEmpty())
    }

    @Test
    fun savedNsecIsLoadedAsNormalizedHex() {
        store.savePrivateKey(hexToNsec(keyB))

        assertEquals(keyB, store.loadPrivateKey())
        assertEquals(listOf(StoredAccount(pubB, hexToNpub(pubB))), store.listAccounts())
    }

    @Test
    fun hexWithoutLeadingZerosIsPadded() {
        store.savePrivateKey("3")

        assertEquals(keyA, store.loadPrivateKey())
    }

    @Test
    fun invalidKeyIsRejectedWithoutWritingAnything() {
        assertFailsWith<Throwable> { store.savePrivateKey("nsec1invalid") }
        assertFailsWith<Throwable> { store.savePrivateKey("0".repeat(64)) }

        assertTrue(memory.values.isEmpty())
    }

    @Test
    fun latestSavedAccountBecomesActiveAndIsListedFirst() {
        store.savePrivateKey(keyA)
        store.savePrivateKey(keyB)

        assertEquals(keyB, store.loadPrivateKey())
        assertEquals(listOf(pubB, pubA), store.listAccounts().map { it.pubkeyHex })
    }

    @Test
    fun switchAccountChangesActiveKey() {
        store.savePrivateKey(keyA)
        store.savePrivateKey(keyB)

        store.switchAccount(pubA)

        assertEquals(keyA, store.loadPrivateKey())
        assertFailsWith<IllegalStateException> { store.switchAccount("ff".repeat(32)) }
    }

    @Test
    fun logoutKeepsKeyAndFallsBackToAnotherLoggedInAccount() {
        store.savePrivateKey(keyA)
        store.savePrivateKey(keyB)

        store.logout()

        assertEquals(keyA, store.loadPrivateKey())
        val accounts = store.listAccounts().associateBy { it.pubkeyHex }
        assertTrue(accounts.getValue(pubB).isLoggedOut)
        assertFalse(accounts.getValue(pubA).isLoggedOut)
    }

    @Test
    fun loggingOutEveryAccountLeavesNoActiveKeyButKeepsThemListed() {
        store.savePrivateKey(keyA)
        store.logout()

        assertNull(store.loadPrivateKey())
        assertEquals(listOf(StoredAccount(pubA, hexToNpub(pubA), isLoggedOut = true)), store.listAccounts())

        store.switchAccount(pubA)
        assertEquals(keyA, store.loadPrivateKey())
    }

    @Test
    fun savingALoggedOutAccountAgainLogsItIn() {
        store.savePrivateKey(keyA)
        store.logout()

        store.savePrivateKey(keyA)

        assertEquals(keyA, store.loadPrivateKey())
        assertFalse(store.listAccounts().single().isLoggedOut)
    }

    @Test
    fun deleteAccountRemovesOnlyALoggedOutAccountsKey() {
        store.savePrivateKey(keyA)
        store.savePrivateKey(keyB)
        assertFailsWith<IllegalStateException> { store.deleteAccount(pubB) }

        store.logout()
        store.deleteAccount(pubB)

        assertEquals(keyA, store.loadPrivateKey())
        assertEquals(listOf(pubA), store.listAccounts().map { it.pubkeyHex })
        assertFalse(memory.values.values.any { it == keyB })
    }

    @Test
    fun deleteKeyRemovesTheActiveAccountAndActivatesTheNextLoggedInOne() {
        store.savePrivateKey(keyA)
        store.savePrivateKey(keyB)

        store.deleteKey()

        assertEquals(keyA, store.loadPrivateKey())
        assertEquals(listOf(pubA), store.listAccounts().map { it.pubkeyHex })
    }

    @Test
    fun deletingTheLastAccountClearsEveryStoredValue() {
        store.savePrivateKey(keyA)
        store.logout()
        store.deleteAccount(pubA)

        assertTrue(memory.values.isEmpty())
    }

    @Test
    fun accountsWithMissingOrMismatchedKeysAreDroppedFromTheList() {
        store.savePrivateKey(keyA)
        store.savePrivateKey(keyB)
        memory.values["torinos_web_private_key_$pubB"] = keyA

        assertEquals(listOf(pubA), store.listAccounts().map { it.pubkeyHex })
        assertEquals(keyA, store.loadPrivateKey())
    }
}
