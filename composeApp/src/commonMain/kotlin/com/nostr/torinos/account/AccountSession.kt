package com.nostr.torinos.account

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.crypto.normalizePrivateKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccountSessionState {
    data object Loading : AccountSessionState

    data class Active(val session: AccountSession) : AccountSessionState

    data class Anonymous(val sessionId: String) : AccountSessionState

    data class Switching(
        val fromPubkey: String?,
        val toPubkey: String?,
    ) : AccountSessionState
}

data class AccountSession(
    val sessionId: String,
    val pubkey: String,
    val signer: AccountSigner,
)

/** 秘密鍵を画面や ViewModel に公開せず、セッションに固定した鍵で署名する。 */
interface AccountSigner {
    val pubkey: String

    fun encryptToSelf(plaintext: String): String

    fun decrypt(content: String, peerPubkey: String): String

    fun sign(
        content: String,
        kind: Int = 1,
        tags: List<List<String>> = emptyList(),
        createdAt: Long? = null,
    ): NostrEvent
}

private class PrivateKeyAccountSigner(
    private val privateKeyHex: String,
) : AccountSigner {
    override val pubkey: String = derivePublicKey(privateKeyHex.fromHex()).toHex()

    override fun encryptToSelf(plaintext: String): String {
        ensureActive()
        return Nip44.encrypt(plaintext, privateKeyHex, pubkey)
    }

    override fun decrypt(content: String, peerPubkey: String): String {
        ensureActive()
        return Nip44.decrypt(content, privateKeyHex, peerPubkey)
    }

    override fun sign(
        content: String,
        kind: Int,
        tags: List<List<String>>,
        createdAt: Long?,
    ): NostrEvent {
        ensureActive()
        return if (createdAt == null) {
            signEvent(
                privateKeyHex = privateKeyHex,
                content = content,
                kind = kind,
                tags = tags,
            )
        } else {
            signEvent(
                privateKeyHex = privateKeyHex,
                content = content,
                kind = kind,
                tags = tags,
                createdAt = createdAt,
            )
        }
    }

    private fun ensureActive() {
        check(AccountSessions.manager.currentSession?.signer === this) {
            "アカウントが切り替わったため操作を中止しました"
        }
    }
}

internal data class AccountCredentials(
    val pubkey: String,
    val privateKeyHex: String,
)

internal interface AccountStorage {
    suspend fun loadActiveCredentials(): AccountCredentials?
    suspend fun listAccounts(): List<StoredAccount>
    suspend fun switchAccount(pubkey: String)
    suspend fun logout()
    suspend fun deleteActiveAccount()
}

internal object KeyStorageAccountStorage : AccountStorage {
    override suspend fun loadActiveCredentials(): AccountCredentials? {
        val privateKey = KeyStorage.loadPrivateKey()
            ?.let(::normalizePrivateKey)
            ?: return null
        val pubkey = derivePublicKey(privateKey.fromHex()).toHex()
        return AccountCredentials(pubkey = pubkey, privateKeyHex = privateKey)
    }

    override suspend fun listAccounts(): List<StoredAccount> = KeyStorage.listAccounts()

    override suspend fun switchAccount(pubkey: String) {
        KeyStorage.switchAccount(pubkey)
    }

    override suspend fun logout() {
        KeyStorage.logout()
    }

    override suspend fun deleteActiveAccount() {
        KeyStorage.deleteKey()
    }
}

class AccountSessionManager internal constructor(
    private val storage: AccountStorage,
    private val signerFactory: (AccountCredentials) -> AccountSigner = {
        PrivateKeyAccountSigner(it.privateKeyHex)
    },
) {
    private val transitionMutex = Mutex()
    private val _state = MutableStateFlow<AccountSessionState>(AccountSessionState.Loading)
    val state: StateFlow<AccountSessionState> = _state.asStateFlow()

    private var nextSessionNumber = 0L

    val currentSession: AccountSession?
        get() = (_state.value as? AccountSessionState.Active)?.session

    val currentPubkey: String?
        get() = currentSession?.pubkey

    val currentSessionId: String
        get() = when (val current = _state.value) {
            is AccountSessionState.Active -> current.session.sessionId
            is AccountSessionState.Anonymous -> current.sessionId
            is AccountSessionState.Switching -> "switching-${current.fromPubkey.orEmpty()}-${current.toPubkey.orEmpty()}"
            AccountSessionState.Loading -> "loading"
        }

    suspend fun initialize(): Result<AccountSessionState> = transitionMutex.withLock {
        if (_state.value !is AccountSessionState.Loading) return@withLock Result.success(_state.value)
        runCatching {
            val next = activeOrAnonymous(storage.loadActiveCredentials())
            _state.value = next
            next
        }.onFailure {
            _state.value = newAnonymousSession()
        }
    }

    suspend fun switchAccount(pubkey: String): Result<AccountSession> = transitionMutex.withLock {
        val previous = _state.value
        val current = (previous as? AccountSessionState.Active)?.session
        if (current?.pubkey == pubkey) return@withLock Result.success(current)

        _state.value = AccountSessionState.Switching(
            fromPubkey = current?.pubkey,
            toPubkey = pubkey,
        )
        val result = runCatching {
            storage.switchAccount(pubkey)
            val credentials = checkNotNull(storage.loadActiveCredentials()) {
                "切り替え先アカウントの鍵を読み込めませんでした"
            }
            check(credentials.pubkey == pubkey) {
                "切り替え先と読み込んだアカウントが一致しません"
            }
            newActiveSession(credentials).also { _state.value = AccountSessionState.Active(it) }
        }
        if (result.isFailure) {
            runCatching {
                if (current != null) storage.switchAccount(current.pubkey) else storage.logout()
            }
            _state.value = previous
        }
        result
    }

    /** 鍵追加直後など、KeyStorage の現在値から新しいセッションを開始する。 */
    suspend fun activateCurrentAccount(expectedPubkey: String? = null): Result<AccountSession> =
        transitionMutex.withLock {
            val previous = _state.value
            _state.value = AccountSessionState.Switching(
                fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
                toPubkey = expectedPubkey,
            )
            val result = runCatching {
                val credentials = checkNotNull(storage.loadActiveCredentials()) {
                    "アカウントの鍵を読み込めませんでした"
                }
                if (expectedPubkey != null) {
                    check(credentials.pubkey == expectedPubkey) {
                        "保存したアカウントと読み込んだアカウントが一致しません"
                    }
                }
                newActiveSession(credentials).also { _state.value = AccountSessionState.Active(it) }
            }
            if (result.isFailure) {
                runCatching {
                    val previousSession = (previous as? AccountSessionState.Active)?.session
                    if (previousSession != null) {
                        storage.switchAccount(previousSession.pubkey)
                    } else {
                        storage.logout()
                    }
                }
                _state.value = previous
            }
            result
        }

    suspend fun logout(): Result<AccountSessionState.Anonymous> = transitionMutex.withLock {
        val previous = _state.value
        _state.value = AccountSessionState.Switching(
            fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
            toPubkey = null,
        )
        runCatching {
            storage.logout()
            newAnonymousSession().also { _state.value = it }
        }.onFailure {
            _state.value = previous
        }
    }

    suspend fun deleteCurrentAccount(): Result<AccountSessionState> = transitionMutex.withLock {
        val previous = _state.value
        _state.value = AccountSessionState.Switching(
            fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
            toPubkey = null,
        )
        runCatching {
            storage.deleteActiveAccount()
            activeOrAnonymous(storage.loadActiveCredentials()).also { _state.value = it }
        }.onFailure {
            _state.value = previous
        }
    }

    suspend fun listAccounts(): List<StoredAccount> = storage.listAccounts()

    suspend fun exportActiveNsec(): String? {
        val credentials = storage.loadActiveCredentials() ?: return null
        if (credentials.pubkey != currentPubkey) return null
        return hexToNsec(credentials.privateKeyHex)
    }

    private fun activeOrAnonymous(credentials: AccountCredentials?): AccountSessionState =
        credentials?.let { AccountSessionState.Active(newActiveSession(it)) }
            ?: newAnonymousSession()

    private fun newActiveSession(credentials: AccountCredentials): AccountSession {
        val signer = signerFactory(credentials)
        check(signer.pubkey == credentials.pubkey) { "秘密鍵と公開鍵が一致しません" }
        return AccountSession(
            sessionId = nextSessionId(credentials.pubkey),
            pubkey = credentials.pubkey,
            signer = signer,
        )
    }

    private fun newAnonymousSession(): AccountSessionState.Anonymous =
        AccountSessionState.Anonymous(sessionId = nextSessionId("anonymous"))

    private fun nextSessionId(owner: String): String {
        nextSessionNumber++
        return "$owner-$nextSessionNumber"
    }
}

object AccountSessions {
    val manager = AccountSessionManager(KeyStorageAccountStorage)
}

fun accountScopedViewModelKey(base: String): String =
    "$base@${AccountSessions.manager.currentSessionId}"
