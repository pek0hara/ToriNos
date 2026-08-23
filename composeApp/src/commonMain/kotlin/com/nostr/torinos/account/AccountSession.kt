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
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NgWordStore
import com.nostr.torinos.network.PrivateMuteListStore
import com.nostr.torinos.network.RelayListSynchronizer
import com.nostr.torinos.network.AccountRelayStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccountSessionState {
    data object Loading : AccountSessionState

    data class Active(val session: AccountSession) : AccountSessionState

    data class Anonymous(val session: AnonymousSession) : AccountSessionState {
        val sessionId: String get() = session.sessionId
    }

    data class Switching(
        val fromPubkey: String?,
        val toPubkey: String?,
    ) : AccountSessionState
}

data class AnonymousSession(val sessionId: String)

class AccountSession internal constructor(
    val sessionId: String,
    val pubkey: String,
    val signer: AccountSigner,
    internal val resources: AccountSessionResources,
) {
    val relayStore = AccountRelayStore(this)
    val followRepository = FollowRepository(this, resources.scope)
    private val privateMuteListStore = PrivateMuteListStore(this, relayStore, resources.scope)
    val muteStore = MuteStore(privateMuteListStore)
    val ngWordStore = NgWordStore(privateMuteListStore)
    val relayListSynchronizer = RelayListSynchronizer(this, followRepository, relayStore, resources.scope)
    private var repositoriesStarted = false

    init {
        resources.onClose(followRepository::close)
        resources.onClose(privateMuteListStore::close)
        resources.onClose(relayListSynchronizer::close)
    }

    internal fun ensureActive() {
        resources.lease.ensureActive()
    }

    internal fun startRepositories() {
        ensureActive()
        if (repositoriesStarted) return
        repositoriesStarted = true
        followRepository.start()
        privateMuteListStore.start()
    }

    internal fun onClose(action: () -> Unit) {
        resources.onClose(action)
    }
}

/** セッション終了後の署名・暗号化を、グローバル状態に依存せず拒否する。 */
internal class AccountSessionLease {
    private val _active = MutableStateFlow(true)
    val isActive: Boolean get() = _active.value

    fun ensureActive() {
        check(isActive) { "アカウントが切り替わったため操作を中止しました" }
    }

    fun invalidate() {
        _active.value = false
    }
}

/** AccountSession が所有する非同期処理の終了境界。 */
internal class AccountSessionResources(
    val lease: AccountSessionLease = AccountSessionLease(),
    private val sessionJob: Job = SupervisorJob(),
) {
    private val closeActions = mutableListOf<() -> Unit>()
    val scope: CoroutineScope = CoroutineScope(sessionJob + Dispatchers.Default)
    val isClosed: Boolean get() = !lease.isActive

    suspend fun close() {
        if (isClosed) return
        lease.invalidate()
        closeActions.toList().also { closeActions.clear() }.forEach { action ->
            runCatching(action)
        }
        sessionJob.cancelAndJoin()
    }

    fun onClose(action: () -> Unit) {
        if (isClosed) action() else closeActions += action
    }
}

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
    private val lease: AccountSessionLease,
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
        lease.ensureActive()
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
    private val signerFactory: (AccountCredentials, AccountSessionLease) -> AccountSigner = { credentials, lease ->
        PrivateKeyAccountSigner(credentials.privateKeyHex, lease)
    },
) {
    private val transitionMutex = Mutex()
    private val _state = MutableStateFlow<AccountSessionState>(AccountSessionState.Loading)
    val state: StateFlow<AccountSessionState> = _state.asStateFlow()
    private val _transitionError = MutableStateFlow<String?>(null)
    val transitionError: StateFlow<String?> = _transitionError.asStateFlow()

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
            _transitionError.value = "アカウントを復元できませんでした"
        }
    }

    suspend fun switchAccount(pubkey: String): Result<AccountSession> = transitionMutex.withLock {
        _transitionError.value = null
        val previous = _state.value
        val current = (previous as? AccountSessionState.Active)?.session
        if (current?.pubkey == pubkey) return@withLock Result.success(current)

        _state.value = AccountSessionState.Switching(
            fromPubkey = current?.pubkey,
            toPubkey = pubkey,
        )
        current?.resources?.close()
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
            _state.value = restorePreviousSession(current)
            _transitionError.value = "アカウントを切り替えられませんでした"
        }
        result
    }

    /** 鍵追加直後など、KeyStorage の現在値から新しいセッションを開始する。 */
    suspend fun activateCurrentAccount(expectedPubkey: String? = null): Result<AccountSession> =
        transitionMutex.withLock {
            _transitionError.value = null
            val previous = _state.value
            _state.value = AccountSessionState.Switching(
                fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
                toPubkey = expectedPubkey,
            )
            val previousSession = (previous as? AccountSessionState.Active)?.session
            previousSession?.resources?.close()
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
                _state.value = restorePreviousSession(previousSession)
                _transitionError.value = "アカウントを開始できませんでした"
            }
            result
        }

    suspend fun logout(): Result<AccountSessionState> = transitionMutex.withLock {
        _transitionError.value = null
        val previous = _state.value
        _state.value = AccountSessionState.Switching(
            fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
            toPubkey = null,
        )
        val previousSession = (previous as? AccountSessionState.Active)?.session
        previousSession?.resources?.close()
        runCatching {
            storage.logout()
            activeOrAnonymous(storage.loadActiveCredentials()).also { _state.value = it }
        }.onFailure {
            _state.value = restorePreviousSession(previousSession)
            _transitionError.value = "ログアウトできませんでした"
        }
    }

    suspend fun deleteCurrentAccount(): Result<AccountSessionState> = transitionMutex.withLock {
        _transitionError.value = null
        val previous = _state.value
        _state.value = AccountSessionState.Switching(
            fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
            toPubkey = null,
        )
        val previousSession = (previous as? AccountSessionState.Active)?.session
        previousSession?.resources?.close()
        runCatching {
            storage.deleteActiveAccount()
            activeOrAnonymous(storage.loadActiveCredentials()).also { _state.value = it }
        }.onFailure {
            _state.value = restorePreviousSession(previousSession)
            _transitionError.value = "アカウントを削除できませんでした"
        }
    }

    suspend fun listAccounts(): List<StoredAccount> = storage.listAccounts()

    fun consumeTransitionError() {
        _transitionError.value = null
    }

    suspend fun exportActiveNsec(): String? {
        val credentials = storage.loadActiveCredentials() ?: return null
        if (credentials.pubkey != currentPubkey) return null
        return hexToNsec(credentials.privateKeyHex)
    }

    private suspend fun activeOrAnonymous(credentials: AccountCredentials?): AccountSessionState =
        credentials?.let { AccountSessionState.Active(newActiveSession(it)) }
            ?: newAnonymousSession()

    private suspend fun newActiveSession(credentials: AccountCredentials): AccountSession {
        val resources = AccountSessionResources()
        return try {
            val signer = signerFactory(credentials, resources.lease)
            check(signer.pubkey == credentials.pubkey) { "秘密鍵と公開鍵が一致しません" }
            AccountSession(
                sessionId = nextSessionId(credentials.pubkey),
                pubkey = credentials.pubkey,
                signer = signer,
                resources = resources,
            )
        } catch (error: Throwable) {
            resources.close()
            throw error
        }
    }

    private fun newAnonymousSession(): AccountSessionState.Anonymous =
        AccountSessionState.Anonymous(
            session = AnonymousSession(sessionId = nextSessionId("anonymous")),
        )

    private fun nextSessionId(owner: String): String {
        nextSessionNumber++
        return "$owner-$nextSessionNumber"
    }

    private suspend fun restorePreviousSession(previous: AccountSession?): AccountSessionState {
        if (previous == null) {
            runCatching { storage.logout() }
            return newAnonymousSession()
        }
        return runCatching {
            val currentCredentials = runCatching { storage.loadActiveCredentials() }.getOrNull()
            val credentials = if (currentCredentials?.pubkey == previous.pubkey) {
                currentCredentials
            } else {
                storage.switchAccount(previous.pubkey)
                checkNotNull(storage.loadActiveCredentials())
            }
            check(credentials.pubkey == previous.pubkey)
            AccountSessionState.Active(newActiveSession(credentials))
        }.getOrElse {
            runCatching { storage.logout() }
            newAnonymousSession()
        }
    }
}

object AccountSessions {
    val manager = AccountSessionManager(KeyStorageAccountStorage)
}
