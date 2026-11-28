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
    /** チャット（NIP-29）。最初に使うときに作り、セッションの終了で NIP-42 の署名器を外す。 */
    internal val chat by lazy {
        com.nostr.torinos.chat.ChatAccount(resources.scope, pubkey, signer, extraAuthRelays = { dm.dmRelays.value }).also { account ->
            resources.onClose(account::close)
        }
    }
    /** グループチャットの入力欄の下書き。画面を離れてもアプリが止められても残るよう、端末に保存する。 */
    internal val chatDrafts by lazy { com.nostr.torinos.chat.ChatDraftStore(pubkey, resources.scope) }
    /** DM の入力欄の下書き。開封した DM と同じく、端末には書かずセッションの間だけ持つ。 */
    internal val dmDrafts by lazy {
        com.nostr.torinos.chat.ChatDraftStore(pubkey, resources.scope, com.nostr.torinos.chat.MemoryChatDraftStorage())
    }
    /** NIP-17 の DM の受信箱（docs/dm-feature-design.md §8.4.4）。開封した内容はメモリーにだけ持つ。 */
    internal val dm: com.nostr.torinos.dm.DmInbox by lazy {
        com.nostr.torinos.dm.DmInbox(
            scope = resources.scope,
            signer = signer,
            readRelays = { relayStore.enabledRelayUrlsSnapshot().toSet() },
            follows = followRepository.followedPubkeys,
            muted = muteStore.mutedPubkeys,
            fetcher = com.nostr.torinos.dm.DmEventFetcher { relays, filters, onEvent ->
                // NIP-42 の署名器を登録してから読む（DM リレーの多くは認証を求める）。
                chat.awaitReady()
                com.nostr.torinos.network.fetchFiniteEvents(
                    com.nostr.torinos.network.SubscriptionSpec(
                        id = "dm-" + kotlin.random.Random.nextLong().toULong().toString(16),
                        filters = filters,
                        target = com.nostr.torinos.network.RelayTarget.Explicit(relays),
                        behavior = com.nostr.torinos.network.SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                        replayCachedEvents = false,
                    ),
                    onEvent = { _, event -> onEvent(event) },
                ).events
            },
            settings = com.nostr.torinos.dm.LocalDmSettings,
            // 相手の DM リレーは利用者のリレー設定に無いことが多いので、一時接続で送る。read 専用の設定には縛られない。
            publisher = com.nostr.torinos.dm.DmPublisher { event, relays ->
                com.nostr.torinos.network.NostrRepository.publishToRelaysWithResult(
                    event, relays, awaitAcceptance = true, respectRelayWritePolicy = false,
                ).succeededRelays
            },
            // 相手の kind 10050 は、相手の NIP-65 の書き込みリレーにも探しに行く（端末に控えがあるときだけ）。
            peerHintRelays = { peer ->
                com.nostr.torinos.network.RelayListEventCache.getOrLoad(peer)?.tags.orEmpty()
                    .let { tags -> com.nostr.torinos.network.relayEntriesFromTags(tags) }
                    .filter { it.write }
                    .map { it.url }
                    .toSet()
            },
        ).also { inbox ->
            // DM リレーが分かったら、チャレンジを受けたまま待っている接続でいま認証する。
            resources.scope.launch {
                inbox.dmRelays.collect { urls -> urls.forEach(com.nostr.torinos.network.NostrRepository::onRelayAuthPolicyChanged) }
            }
        }
    }
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
        eventStore = com.nostr.torinos.badge.AppBadgeEventStore,
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
    /** Zap の送信。セッションのスコープで動き、アカウントの切り替えで支払い待ちも捨てる。 */
    val zaps: com.nostr.torinos.zap.ZapSender by lazy { com.nostr.torinos.zap.createZapSender(this, resources.scope) }
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
        // 自分の鍵なら、起動時から DM を受信してフッターに未読を出す。外部 signer はチャットタブを開いたとき（§8.4.4）。
        if (signer.method == SignerMethod.PrivateKey) {
            resources.scope.launch {
                relayStore.isLoaded.first { it }
                dm.start()
            }
        }
    }

    /** アプリが前面に戻ったとき。 */
    internal fun onAppForeground() {
        if (!resources.lease.isActive) return
        NostrRepository.onAppForeground()
        (signer as? InteractiveSigner)?.onAppForeground()
        emojiPreferenceSync.onAppForeground()
        zaps.onAppForeground()
        badges.onForeground()
        badgeIssuer.onForeground()
        dm.refreshIfStarted()
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

    /**
     * ほかの所（Web の別タブなど）が保存先のアカウントを書き換えたとき、このセッションを保存先に合わせる。
     *
     * 保存先は読むだけで書き換えない（書き換えた側の決定に従う）。アクティブなアカウントが同じなら何もしない。
     * 入力の保存に失敗しても、ログアウト・切り替え済みのアカウントで署名を続けさせないため、セッションは終える。
     */
    suspend fun syncWithStorage(): Result<AccountSessionState> =
        transitionScope.async { syncWithStorageInternal() }.await()

    private suspend fun syncWithStorageInternal(): Result<AccountSessionState> = transitionMutex.withLock {
        val previous = _state.value
        if (previous !is AccountSessionState.Active && previous !is AccountSessionState.Anonymous) {
            return@withLock Result.success(previous)
        }
        val stored = runCatching { storage.loadActiveCredentials() }
        val storedPubkey = stored.getOrNull()?.pubkey
        val currentSession = (previous as? AccountSessionState.Active)?.session
        if (stored.isSuccess && storedPubkey == currentSession?.pubkey) return@withLock Result.success(previous)

        signerTraceLog { "sync with storage from=${currentSession?.pubkey?.take(8)} to=${storedPubkey?.take(8)}" }
        if (currentSession != null) {
            runCatching { currentSession.prepareTransition() }.onFailure {
                logException("AccountSessionManager", it, "Failed to save inputs before following another tab")
            }
        }
        _state.value = AccountSessionState.Switching(fromPubkey = currentSession?.pubkey, toPubkey = storedPubkey)
        runCatching {
            currentSession?.resources?.close()
            if (currentSession != null) {
                // 別のタブで切り替えただけなら、戻ったときに解錠し直さなくてよいよう鍵を残す。ログアウト・削除なら消す。
                val stillLoggedIn = runCatching {
                    storage.listAccounts().any { it.pubkeyHex == currentSession.pubkey && !it.isLoggedOut }
                }.getOrDefault(false)
                if (!stillLoggedIn) PlatformSigners.forgetUnlockedKey(currentSession.pubkey)
            }
            activeOrAnonymous(stored.getOrThrow()).also { _state.value = it }
        }.onFailure {
            logException("AccountSessionManager", it, "Failed to follow account changed in another tab")
            _state.value = fallbackAnonymousSession()
            _transitionError.value = "別の画面で変わったアカウントを読み込めませんでした"
        }
    }

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
