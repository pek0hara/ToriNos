package com.nostr.torinos.ui.settings

import com.nostr.torinos.account.AccountSessionState
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val accounts: List<StoredAccount> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val isSecretKeyVisible: Boolean = false,
    val keyError: String? = null,
    val isAccountActionProcessing: Boolean = false,
    val accountActionError: String? = null,
)

class SettingsViewModel : SafeViewModel() {
    private val accountSessionManager = AccountSessions.manager
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _secretKeyEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val secretKeyEvent: SharedFlow<String> = _secretKeyEvent.asSharedFlow()

    private val _secretKeyClipboardEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val secretKeyClipboardEvent: SharedFlow<String> = _secretKeyClipboardEvent.asSharedFlow()

    init {
        launch {
            ProfileRepository.observeAll().collect { cachedProfiles ->
                val accountPubkeys = _state.value.accounts.mapTo(hashSetOf()) { it.pubkeyHex }
                val profiles = cachedProfiles.filterKeys { it in accountPubkeys }
                if (profiles != _state.value.profiles) _state.update { it.copy(profiles = profiles) }
            }
        }
        refreshAccounts()
    }

    fun refreshAccounts() {
        launch {
            val accounts = runCatching { accountSessionManager.listAccounts() }.getOrElse { e ->
                logException("SettingsViewModel", e, "Failed to load accounts")
                emptyList()
            }
            _state.update { it.copy(accounts = accounts) }
            if (accounts.isNotEmpty()) {
                val pubkeys = accounts.mapTo(linkedSetOf()) { it.pubkeyHex }
                _state.update { it.copy(profiles = ProfileRepository.getCached(pubkeys)) }
                ProfileRepository.ensureProfiles(
                    pubkeys,
                    ProfileFetchPolicy.CacheFirst(15 * 60 * 1_000L),
                )
            }
        }
    }

    fun switchAccount(pubkeyHex: String, onSwitched: (String?) -> Unit) {
        if (_state.value.isAccountActionProcessing) return

        launch {
            _state.update {
                it.copy(
                    isSecretKeyVisible = false,
                    keyError = null,
                    isAccountActionProcessing = true,
                    accountActionError = null,
                )
            }
            try {
                val session = accountSessionManager.switchAccount(pubkeyHex).getOrThrow()
                val accounts = accountSessionManager.listAccounts()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        isAccountActionProcessing = false,
                        accountActionError = null,
                    )
                }
                onSwitched(session.pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("SettingsViewModel", e, "Failed to switch account")
                _state.update {
                    it.copy(
                        isAccountActionProcessing = false,
                        accountActionError = e.message
                            ?.let { message -> "アカウントを切り替えられませんでした: $message" }
                            ?: "アカウントを切り替えられませんでした",
                    )
                }
            }
        }
    }

    fun showSecretKey() {
        launch {
            val nsec = runCatching {
                accountSessionManager.exportActiveNsec()
                    ?: error("秘密鍵が保存されていません")
            }.getOrElse { e ->
                logException("SettingsViewModel", e, "Failed to load private key for display")
                _state.update {
                    it.copy(
                        isSecretKeyVisible = false,
                        keyError = e.message ?: "秘密鍵を読み込めませんでした",
                    )
                }
                return@launch
            }

            _state.update { it.copy(isSecretKeyVisible = true, keyError = null) }
            _secretKeyEvent.emit(nsec)
        }
    }

    fun hideSecretKey() {
        _state.update { it.copy(isSecretKeyVisible = false, keyError = null) }
    }

    fun copySecretKey() {
        launch {
            val nsec = runCatching {
                accountSessionManager.exportActiveNsec()
                    ?: error("秘密鍵が保存されていません")
            }.getOrElse { e ->
                logException("SettingsViewModel", e, "Failed to load private key for clipboard")
                _state.update { it.copy(keyError = e.message ?: "秘密鍵を読み込めませんでした") }
                return@launch
            }
            _state.update { it.copy(keyError = null) }
            _secretKeyClipboardEvent.emit(nsec)
        }
    }

    fun clearAccount(onCleared: (String?) -> Unit) {
        if (_state.value.isAccountActionProcessing) return

        launch {
            _state.update {
                it.copy(
                    isAccountActionProcessing = true,
                    accountActionError = null,
                )
            }
            try {
                val nextState = accountSessionManager.logout().getOrThrow()
                val accounts = accountSessionManager.listAccounts()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        isSecretKeyVisible = false,
                        keyError = null,
                        isAccountActionProcessing = false,
                        accountActionError = null,
                    )
                }
                onCleared((nextState as? AccountSessionState.Active)?.session?.pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("SettingsViewModel", e, "Failed to clear account key")
                _state.update {
                    it.copy(
                        isAccountActionProcessing = false,
                        accountActionError = e.message
                    ?.let { "アカウント情報を削除できませんでした: $it" }
                    ?: "アカウント情報を削除できませんでした",
                    )
                }
            }
        }
    }

    fun requestVanishAndClearAccount(relayUrls: Collection<String>, onCleared: (String?) -> Unit) {
        if (_state.value.isAccountActionProcessing) return

        launch {
            val targets = relayUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (targets.isEmpty()) {
                _state.update { it.copy(accountActionError = "送信先リレーを選択してください") }
                return@launch
            }

            _state.update {
                it.copy(
                    isAccountActionProcessing = true,
                    accountActionError = null,
                )
            }
            try {
                val signer = accountSessionManager.currentSession?.signer
                    ?: error("秘密鍵が保存されていません")
                val vanishRequest = signer.sign(
                    content = "",
                    kind = 62,
                    tags = targets.map { listOf("relay", it) },
                )
                NostrRepository.publishToRelays(vanishRequest, targets)
                val nextState = accountSessionManager.deleteCurrentAccount().getOrThrow()
                val accounts = accountSessionManager.listAccounts()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        isSecretKeyVisible = false,
                        keyError = null,
                        isAccountActionProcessing = false,
                        accountActionError = null,
                    )
                }
                onCleared((nextState as? AccountSessionState.Active)?.session?.pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("SettingsViewModel", e, "Failed to request vanish and clear account key")
                _state.update {
                    it.copy(
                        isAccountActionProcessing = false,
                        accountActionError = e.message
                            ?.let { "アカウント削除要求を送信できませんでした: $it" }
                            ?: "アカウント削除要求を送信できませんでした",
                    )
                }
            }
        }
    }

    fun clearAccountActionError() {
        _state.update { it.copy(accountActionError = null) }
    }
}
