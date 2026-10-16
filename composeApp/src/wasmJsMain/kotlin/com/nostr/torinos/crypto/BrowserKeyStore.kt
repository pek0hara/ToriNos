package com.nostr.torinos.crypto

import com.nostr.torinos.account.AccountSecretCodec

/** 文字列を保存する同期キーバリューストア。本番は `localStorage`、テストはメモリ上の実装を使う。 */
internal interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

private const val ACCOUNTS_KEY = "torinos_web_accounts"
private const val ACTIVE_ACCOUNT_KEY = "torinos_web_active_account"
private const val LOGGED_OUT_ACCOUNTS_KEY = "torinos_web_logged_out_accounts"
private const val PRIVATE_KEY_PREFIX = "torinos_web_private_key_"

/**
 * ブラウザに秘密鍵と保存済みアカウントを保存する。
 *
 * アカウント一覧・アクティブ・ログアウト済みの扱いは iOS の `KeyStorage` と同じ規則にする。
 * - 保存したアカウントがアクティブになり、ログアウト済みから外れる。
 * - ログアウトは鍵を残し、残りのログイン中アカウントがあればそれをアクティブにする。
 * - 削除できるのはログアウト済みのアカウントだけ。
 */
internal class BrowserKeyStore(private val store: KeyValueStore) {

    fun savePrivateKey(hexKey: String) {
        val normalized = normalizePrivateKey(hexKey)
        saveAccountSecret(derivePublicKey(normalized.fromHex()).toHex(), normalized)
    }

    /** 秘密鍵、または秘密鍵以外の署名方式の保存値を、[pubkeyHex] のアカウントとして保存する。 */
    fun saveAccountSecret(pubkeyHex: String, secret: String) {
        val normalized = checkNotNull(AccountSecretCodec.normalizeStored(pubkeyHex, secret)) {
            "保存する値がアカウントと一致しません"
        }
        store.set(privateKeyKey(pubkeyHex), normalized)
        writeAccounts(readAccounts().filterNot { it == pubkeyHex } + pubkeyHex)
        store.set(ACTIVE_ACCOUNT_KEY, pubkeyHex)
        writeLoggedOut(readLoggedOut() - pubkeyHex)
    }

    fun loadPrivateKey(): String? = privateKeyOrNull(loadActiveAccountSecret())

    fun loadActiveAccountSecret(): StoredAccountSecret? {
        val loggedOut = readLoggedOut()
        val activePubkey = store.get(ACTIVE_ACCOUNT_KEY)
            ?.takeIf { it !in loggedOut }
            ?: readAccounts().firstOrNull { it !in loggedOut }
            ?: return null
        val secret = loadValidSecret(activePubkey) ?: return null
        store.set(ACTIVE_ACCOUNT_KEY, activePubkey)
        return StoredAccountSecret(activePubkey, secret)
    }

    fun listAccounts(): List<StoredAccount> {
        val accounts = readAccounts()
        val validAccounts = accounts.filter { loadValidSecret(it) != null }
        var loggedOut = readLoggedOut()
        if (validAccounts != accounts) {
            // 鍵が消えた・壊れたアカウントを一覧から外す。保存値は別アカウントの鍵の可能性もあるため消さない。
            writeAccounts(validAccounts)
            loggedOut = loggedOut.intersect(validAccounts.toSet())
            writeLoggedOut(loggedOut)
            if (store.get(ACTIVE_ACCOUNT_KEY) !in validAccounts) {
                writeActive(validAccounts.firstOrNull { it !in loggedOut })
            }
        }
        val activePubkey = store.get(ACTIVE_ACCOUNT_KEY)
        return validAccounts
            .map {
                StoredAccount(
                    pubkeyHex = it,
                    npub = hexToNpub(it),
                    isLoggedOut = it in loggedOut,
                    method = loadValidSecret(it)?.let { secret -> storedSecretMethod(it, secret) }
                        ?: com.nostr.torinos.account.SignerMethod.PrivateKey,
                )
            }
            .sortedBy { if (it.pubkeyHex == activePubkey) 0 else 1 }
    }

    fun switchAccount(pubkeyHex: String) {
        check(pubkeyHex in readAccounts()) { "アカウントが保存されていません" }
        store.set(ACTIVE_ACCOUNT_KEY, pubkeyHex)
        writeLoggedOut(readLoggedOut() - pubkeyHex)
    }

    fun logout() {
        val loggedOut = readLoggedOut() + listOfNotNull(store.get(ACTIVE_ACCOUNT_KEY))
        writeLoggedOut(loggedOut)
        writeActive(readAccounts().firstOrNull { it !in loggedOut })
    }

    fun deleteAccount(pubkeyHex: String) {
        val accounts = readAccounts()
        check(pubkeyHex in accounts) { "削除するアカウントが保存されていません" }
        val loggedOut = readLoggedOut()
        check(pubkeyHex in loggedOut) { "ログアウトしていないアカウントは削除できません" }
        removeAccount(pubkeyHex, accounts, loggedOut)
    }

    fun deleteKey() {
        val activePubkey = store.get(ACTIVE_ACCOUNT_KEY) ?: return
        removeAccount(activePubkey, readAccounts(), readLoggedOut())
    }

    private fun removeAccount(pubkeyHex: String, accounts: List<String>, loggedOut: Set<String>) {
        store.remove(privateKeyKey(pubkeyHex))
        val remaining = accounts.filterNot { it == pubkeyHex }
        val remainingLoggedOut = loggedOut - pubkeyHex
        writeAccounts(remaining)
        writeLoggedOut(remainingLoggedOut)
        if (store.get(ACTIVE_ACCOUNT_KEY) == pubkeyHex) {
            writeActive(remaining.firstOrNull { it !in remainingLoggedOut })
        }
    }

    /** 保存値が鍵の一致（秘密鍵）または形式（それ以外）の確認を通れば、正規化した値を返す。 */
    private fun loadValidSecret(pubkeyHex: String): String? =
        store.get(privateKeyKey(pubkeyHex))?.let { AccountSecretCodec.normalizeStored(pubkeyHex, it) }

    private fun readAccounts(): List<String> = readList(ACCOUNTS_KEY).distinct()

    private fun readLoggedOut(): Set<String> = readList(LOGGED_OUT_ACCOUNTS_KEY).toSet()

    private fun readList(key: String): List<String> =
        store.get(key)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun writeAccounts(pubkeys: List<String>) = writeList(ACCOUNTS_KEY, pubkeys.distinct())

    private fun writeLoggedOut(pubkeys: Set<String>) = writeList(LOGGED_OUT_ACCOUNTS_KEY, pubkeys.toList())

    private fun writeList(key: String, values: List<String>) {
        if (values.isEmpty()) store.remove(key) else store.set(key, values.joinToString(","))
    }

    private fun writeActive(pubkeyHex: String?) {
        if (pubkeyHex == null) store.remove(ACTIVE_ACCOUNT_KEY) else store.set(ACTIVE_ACCOUNT_KEY, pubkeyHex)
    }

    private fun privateKeyKey(pubkeyHex: String): String = PRIVATE_KEY_PREFIX + pubkeyHex
}
