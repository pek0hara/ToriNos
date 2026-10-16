package com.nostr.torinos.account

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.util.logException
import com.nostr.torinos.util.signerTraceLog
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.emoji.CustomEmojiRepository
import com.nostr.torinos.emoji.EmojiPreferenceSync
import com.nostr.torinos.emoji.NostrEmojiPreferenceTransport
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NgWordStore
import com.nostr.torinos.network.PrivateMuteListStore
import com.nostr.torinos.network.RelayListSynchronizer
import com.nostr.torinos.network.AccountRelayStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
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
    private val _inputFrozen = MutableStateFlow(false)
    internal val inputFrozen = _inputFrozen.asStateFlow()
    internal val matometrSelection = com.nostr.torinos.article.MatometrSelectionCoordinator {
        !_inputFrozen.value && resources.lease.isActive
    }
    private val inputRegistryLock = SynchronousLock()
    private val transitionPreparations = mutableMapOf<String, suspend () -> Unit>()
    internal fun registerInput(id: String, flush: suspend () -> Unit): () -> Unit {
        inputRegistryLock.withLock { transitionPreparations[id] = flush }
        return { inputRegistryLock.withLock { transitionPreparations.remove(id) } }
    }
    internal suspend fun flushInputs() {
        inputRegistryLock.withLock { transitionPreparations.values.toList() }.forEach { it() }
    }
    internal fun cancelTransitionPreparation() { _inputFrozen.value = false }
    internal suspend fun prepareTransition() {
        ensureActive()
        _inputFrozen.value = true
        try { withTimeout(20_000L) { flushInputs() } }
        catch (e: Throwable) { _inputFrozen.value = false; throw e }
    }
    val relayStore = AccountRelayStore(this)
    internal val channelPublications by lazy {
        com.nostr.torinos.ui.channel.ChannelPublicationService(
            scope = resources.scope, signer = signer, canStart = { resources.lease.isActive },
            writableRelays = { relayStore.writableRelayUrlsSnapshot().toSet() },
        )
    }
    internal val articleDraftStore by lazy {
        com.nostr.torinos.article.ArticleDraftStore(transport = com.nostr.torinos.article.ArticleDraftRepository(
            signer = signer,
            readRelays = { (relayStore.enabledRelayUrlsSnapshot() + relayStore.writableRelayUrlsSnapshot()).toSet() },
            writableRelays = relayStore::writableRelayUrlsSnapshot,
            ensureActive = ::ensureActive,
        ))
    }
    internal val badgeRepository = com.nostr.torinos.badge.BadgeRepository(
        scope = resources.scope,
        readRelays = { relayStore.enabledRelayUrlsSnapshot().toSet() },
    )
    internal val badges = com.nostr.torinos.badge.AccountBadgeStore(
        pubkey = pubkey, sessionId = sessionId, signer = signer, scope = resources.scope,
        repository = badgeRepository,
        relays = { (relayStore.enabledRelayUrlsSnapshot() + relayStore.writableRelayUrlsSnapshot()).toSet() },
        writableRelays = { relayStore.writableRelayUrlsSnapshot().toSet() },
        ensureActive = ::ensureActive,
    )
    internal val badgeIssuer = com.nostr.torinos.badge.AccountBadgeIssuer(
        pubkey = pubkey, signer = signer, scope = resources.scope,
        repository = badgeRepository,
        relays = { (relayStore.enabledRelayUrlsSnapshot() + relayStore.writableRelayUrlsSnapshot()).toSet() },
        writableRelays = { relayStore.writableRelayUrlsSnapshot().toSet() },
        ensureActive = ::ensureActive,
    )
    val followRepository = FollowRepository(this, resources.scope)
    private val privateMuteListStore = PrivateMuteListStore(
        signer = signer,
        sessionId = sessionId,
        scope = resources.scope,
        writableRelayUrls = relayStore::writableRelayUrlsSnapshot,
        ensureActive = ::ensureActive,
    )
    val muteStore = MuteStore(privateMuteListStore)
    val ngWordStore = NgWordStore(privateMuteListStore)
    val relayListSynchronizer = RelayListSynchronizer(this, followRepository, relayStore, resources.scope)
    internal val emojiAdoption = com.nostr.torinos.emoji.EmojiAdoptionRepository(pubkey, sessionId, resources.scope)
    val customEmojis = CustomEmojiRepository(pubkey, resources.scope)
    internal val emojiPreferenceSync = EmojiPreferenceSync(
        pubkey = pubkey,
        signer = signer,
        repository = customEmojis,
        scope = resources.scope,
        transport = NostrEmojiPreferenceTransport(
            sessionId = sessionId,
            relaysLoaded = { relayStore.isLoaded.first { it } },
            writableRelayUrls = relayStore::writableRelayUrlsSnapshot,
        ),
        ensureActive = ::ensureActive,
    )
    private var repositoriesStarted = false

    init {
        // 解錠・再接続で署名器が使えるようになったら、起動時に読めなかった暗号化データを読み直す。
        (signer as? InteractiveSigner)?.let { interactive ->
            resources.scope.launch {
                var previous = interactive.attention.value
                interactive.attention.collect { current ->
                    signerTraceLog { "attention ${previous?.let { it::class.simpleName }} -> ${current?.let { it::class.simpleName }}" }
                    if (previous != null && current == null) privateMuteListStore.refresh()
                    previous = current
                }
            }
        }
        resources.onClose(followRepository::close)
        resources.onBeforeClose(privateMuteListStore::closeAndFlush)
        resources.onClose { com.nostr.torinos.engagement.EngagementStore.shared.releaseLocalSession(sessionId) }
        resources.onClose(relayListSynchronizer::close)
        resources.onClose(emojiPreferenceSync::close)
        resources.onClose(customEmojis::close)
        resources.onClose(emojiAdoption::close)
        resources.onClose(badges::close)
        resources.onClose(badgeIssuer::close)
    }

    internal fun ensureActive() {
        resources.lease.ensureActive()
    }

    internal suspend fun prepareLocalState() {
        privateMuteListStore.initialize()
    }

    internal fun restorePendingChanges(previous: AccountSession) {
        check(previous.pubkey == pubkey)
        privateMuteListStore.restorePendingChanges(previous.privateMuteListStore.pendingSnapshot())
    }

    internal fun startRepositories() {
        ensureActive()
        if (repositoriesStarted) return
        repositoriesStarted = true
        followRepository.start()
        privateMuteListStore.start()
        customEmojis.start()
        emojiPreferenceSync.start()
        badges.start()
        badgeIssuer.start()
    }

    /** アプリが前面に戻ったとき。 */
    internal fun onAppForeground() {
        if (!resources.lease.isActive) return
        emojiPreferenceSync.onAppForeground()
        badges.onForeground()
        badgeIssuer.onForeground()
    }

    internal fun onClose(action: () -> Unit) {
        resources.onClose(action)
    }
}

/** セッション終了後の署名・暗号化。従来どおり IllegalStateException として扱える。 */
class AccountSessionEndedException : IllegalStateException("アカウントが切り替わったため操作を中止しました")

/** セッション終了後の署名・暗号化を、グローバル状態に依存せず拒否する。 */
internal class AccountSessionLease {
    private val _active = MutableStateFlow(true)
    val isActive: Boolean get() = _active.value

    fun ensureActive() {
        if (!isActive) throw AccountSessionEndedException()
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
    private val beforeCloseActions = mutableListOf<suspend () -> Unit>()
    val scope: CoroutineScope = CoroutineScope(sessionJob + Dispatchers.Default)
    val isClosed: Boolean get() = !lease.isActive

    suspend fun close() {
        if (isClosed) return
        lease.invalidate()
        try {
            // ローカル変更を保存してから、保存を所有するセッションのジョブをキャンセルする。
            beforeCloseActions.toList().also { beforeCloseActions.clear() }.forEach { it() }
        } finally {
            closeActions.toList().also { closeActions.clear() }.forEach { action -> runCatching(action) }
            sessionJob.cancel()
            withTimeoutOrNull(SESSION_CLOSE_TIMEOUT_MS) { sessionJob.join() }
        }
    }

    fun onBeforeClose(action: suspend () -> Unit) {
        check(!isClosed)
        beforeCloseActions += action
    }

    fun onClose(action: () -> Unit) {
        if (isClosed) action() else closeActions += action
    }
}

private const val SESSION_CLOSE_TIMEOUT_MS = 2_000L

internal data class AccountCredentials(
    val pubkey: String,
    val secret: AccountSecret,
) {
    constructor(pubkey: String, privateKeyHex: String) : this(pubkey, AccountSecret.PrivateKey(privateKeyHex))

    override fun toString(): String = "AccountCredentials(pubkey=$pubkey, method=${secret.method})"
}

internal interface AccountStorage {
    suspend fun loadActiveCredentials(): AccountCredentials?
    suspend fun listAccounts(): List<StoredAccount>
    suspend fun switchAccount(pubkey: String)
    suspend fun logout()
    suspend fun deleteActiveAccount()
}

internal object KeyStorageAccountStorage : AccountStorage {
    override suspend fun loadActiveCredentials(): AccountCredentials? {
        val stored = KeyStorage.loadActiveAccountSecret() ?: return null
        val secret = checkNotNull(AccountSecretCodec.decode(stored.pubkeyHex, stored.secret)) {
            "保存されたアカウントの情報を読み込めませんでした"
        }
        return AccountCredentials(pubkey = stored.pubkeyHex, secret = secret)
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
    private val signerFactory: (AccountCredentials, AccountSessionLease) -> AccountSigner = ::createAccountSigner,
    private val prepareAccount: suspend (AccountSession?) -> Unit = { session ->
        RelayStore.activateAccount(session?.pubkey)
        withTimeout(10_000L) {
            NostrRepository.awaitRelayRouting(RelayStore.enabledRelayUrlsSnapshot().toSet())
        }
        session?.prepareLocalState()
    },
) {
    // AccountSessionHost の破棄で呼び出し元 ViewModel がキャンセルされても、
    // Switching に入った遷移は必ず終端状態まで完了させる。
    private val transitionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            _state.value = fallbackAnonymousSession()
            _transitionError.value = "アカウントを復元できませんでした"
        }
    }

    suspend fun switchAccount(pubkey: String): Result<AccountSession> =
        transitionScope.async { switchAccountInternal(pubkey) }.await()

    private suspend fun switchAccountInternal(pubkey: String): Result<AccountSession> = transitionMutex.withLock {
        _transitionError.value = null
        val previous = _state.value
        val current = (previous as? AccountSessionState.Active)?.session
        if (current?.pubkey == pubkey) return@withLock Result.success(current)

        if (current != null) {
            val prepared = runCatching { current.prepareTransition() }
            if (prepared.isFailure) {
                _transitionError.value = "保存できないため切り替えられません。再試行または編集を続けてください: ${prepared.exceptionOrNull()?.message}"
                return@withLock Result.failure(prepared.exceptionOrNull()!!)
            }
        }
        _state.value = AccountSessionState.Switching(
            fromPubkey = current?.pubkey,
            toPubkey = pubkey,
        )
        val result = runCatching {
            current?.resources?.close()
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
        transitionScope.async { activateCurrentAccountInternal(expectedPubkey) }.await()

    private suspend fun activateCurrentAccountInternal(expectedPubkey: String?): Result<AccountSession> =
        transitionMutex.withLock {
            _transitionError.value = null
            val previous = _state.value
            val preparingSession = (previous as? AccountSessionState.Active)?.session
            if (preparingSession != null) {
                val prepared = runCatching { preparingSession.prepareTransition() }
                if (prepared.isFailure) {
                    _transitionError.value = "保存できないため切り替えられません。再試行または編集を続けてください: ${prepared.exceptionOrNull()?.message}"
                    return@withLock Result.failure(prepared.exceptionOrNull()!!)
                }
            }
            _state.value = AccountSessionState.Switching(
                fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
                toPubkey = expectedPubkey,
            )
            val previousSession = (previous as? AccountSessionState.Active)?.session
            val result = runCatching {
                previousSession?.resources?.close()
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

    suspend fun logout(): Result<AccountSessionState> =
        transitionScope.async { logoutInternal() }.await()

    private suspend fun logoutInternal(): Result<AccountSessionState> = transitionMutex.withLock {
        _transitionError.value = null
        val previous = _state.value
        val preparingSession = (previous as? AccountSessionState.Active)?.session
        if (preparingSession != null) {
            val prepared = runCatching { preparingSession.prepareTransition() }
            if (prepared.isFailure) {
                _transitionError.value = "保存できないため切り替えられません。再試行または編集を続けてください: ${prepared.exceptionOrNull()?.message}"
                return@withLock Result.failure(prepared.exceptionOrNull()!!)
            }
        }
        _state.value = AccountSessionState.Switching(
            fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
            toPubkey = null,
        )
        val previousSession = (previous as? AccountSessionState.Active)?.session
        runCatching {
            previousSession?.resources?.close()
            storage.logout()
            previousSession?.let { PlatformSigners.forgetUnlockedKey(it.pubkey) }
            activeOrAnonymous(storage.loadActiveCredentials()).also { _state.value = it }
        }.onFailure {
            _state.value = restorePreviousSession(previousSession)
            _transitionError.value = "ログアウトできませんでした"
        }
    }

    suspend fun deleteCurrentAccount(beforeDelete: suspend (AccountSigner) -> Unit = {}): Result<AccountSessionState> =
        transitionScope.async { deleteCurrentAccountInternal(beforeDelete) }.await()

    private suspend fun deleteCurrentAccountInternal(beforeDelete: suspend (AccountSigner) -> Unit): Result<AccountSessionState> = transitionMutex.withLock {
        _transitionError.value = null
        val previous = _state.value
        val preparingSession = (previous as? AccountSessionState.Active)?.session
        if (preparingSession != null) {
            val prepared = runCatching { preparingSession.prepareTransition() }
            if (prepared.isFailure) {
                _transitionError.value = "保存できないため切り替えられません。再試行または編集を続けてください: ${prepared.exceptionOrNull()?.message}"
                return@withLock Result.failure(prepared.exceptionOrNull()!!)
            }
        }
        _state.value = AccountSessionState.Switching(
            fromPubkey = (previous as? AccountSessionState.Active)?.session?.pubkey,
            toPubkey = null,
        )
        val previousSession = (previous as? AccountSessionState.Active)?.session
        runCatching {
            previousSession?.let { beforeDelete(it.signer) }
            previousSession?.resources?.close()
            storage.deleteActiveAccount()
            previousSession?.let { PlatformSigners.forgetUnlockedKey(it.pubkey) }
            activeOrAnonymous(storage.loadActiveCredentials()).also { _state.value = it }
        }.onFailure {
            _state.value = if (previousSession?.resources?.lease?.isActive == true) {
                previousSession.cancelTransitionPreparation()
                AccountSessionState.Active(previousSession)
            } else restorePreviousSession(previousSession)
            _transitionError.value = "アカウントを削除できませんでした"
        }
    }

    suspend fun listAccounts(): List<StoredAccount> = storage.listAccounts()

    fun consumeTransitionError() {
        _transitionError.value = null
    }

    /**
     * 秘密鍵でログインしているアカウントの nsec。パスキーで保護した鍵は解錠後だけ返す。
     * 秘密鍵を持たない署名方式（拡張機能・リモート署名）では null。
     */
    suspend fun exportActiveNsec(): String? {
        val credentials = storage.loadActiveCredentials() ?: return null
        if (credentials.pubkey != currentPubkey) return null
        return when (val secret = credentials.secret) {
            is AccountSecret.PrivateKey -> hexToNsec(secret.hex)
            is AccountSecret.External -> when (secret.credential) {
                is SignerCredential.Passkey -> PlatformSigners.unlockedPrivateKey(credentials.pubkey)?.let(::hexToNsec)
                    ?: throw SignerException.Locked()
                else -> null
            }
        }
    }

    private suspend fun activeOrAnonymous(credentials: AccountCredentials?): AccountSessionState =
        credentials?.let { AccountSessionState.Active(newActiveSession(it)) }
            ?: newAnonymousSession()

    private suspend fun newActiveSession(credentials: AccountCredentials): AccountSession {
        val resources = AccountSessionResources()
        return try {
            val signer = signerFactory(credentials, resources.lease)
            (signer as? SessionBoundSigner)?.let { resources.onClose(it::close) }
            signerTraceLog { "session start pubkey=${credentials.pubkey.take(8)} method=${signer.method} interactive=${signer is InteractiveSigner}" }
            resources.onClose { signerTraceLog { "session closed pubkey=${credentials.pubkey.take(8)} method=${signer.method}" } }
            check(signer.pubkey == credentials.pubkey) { "署名器と公開鍵が一致しません" }
            AccountSession(
                sessionId = nextSessionId(credentials.pubkey),
                pubkey = credentials.pubkey,
                signer = signer,
                resources = resources,
            ).also { prepareAccount(it) }
        } catch (error: Throwable) {
            resources.close()
            throw error
        }
    }

    private suspend fun newAnonymousSession(): AccountSessionState.Anonymous {
        prepareAccount(null)
        return AccountSessionState.Anonymous(
            session = AnonymousSession(sessionId = nextSessionId("anonymous")),
        )
    }

    /** 匿名の準備まで失敗しても、Loading/Switchingで停止しない。 */
    private suspend fun fallbackAnonymousSession(): AccountSessionState.Anonymous =
        runCatching { newAnonymousSession() }.getOrElse { error ->
            logException("AccountSessionManager", error, "Failed to prepare anonymous fallback")
            AccountSessionState.Anonymous(AnonymousSession(nextSessionId("anonymous")))
        }

    private fun nextSessionId(owner: String): String {
        nextSessionNumber++
        return "$owner-$nextSessionNumber"
    }

    private suspend fun restorePreviousSession(previous: AccountSession?): AccountSessionState {
        if (previous == null) {
            runCatching { storage.logout() }
            return fallbackAnonymousSession()
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
            AccountSessionState.Active(newActiveSession(credentials).also { it.restorePendingChanges(previous) })
        }.getOrElse {
            runCatching { storage.logout() }
            fallbackAnonymousSession()
        }
    }
}

object AccountSessions {
    val manager = AccountSessionManager(KeyStorageAccountStorage)
}
