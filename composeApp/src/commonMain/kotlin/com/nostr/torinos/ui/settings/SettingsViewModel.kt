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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    fun deleteAccount(onCleared: (String?) -> Unit) {
        if (_state.value.isAccountActionProcessing) return

        launch {
            _state.update {
                it.copy(
                    isAccountActionProcessing = true,
                    accountActionError = null,
                )
            }
            try {
                val signer = accountSessionManager.currentSession?.signer
                    ?: error("秘密鍵が保存されていません")
                val deletedProfile = signer.sign(
                    content = Json.encodeToString(
                        buildJsonObject {
                            put("name", "nobody")
                            put("about", "account deleted")
                        },
                    ),
                    kind = 0,
                )
                // Damus と同様、削除済みプロフィールを配信してからローカル鍵を破棄する。
                // publish は少なくとも1つの有効リレーへの送信成功を保証する。
                NostrRepository.publish(deletedProfile)
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
                logException("SettingsViewModel", e, "Failed to publish deleted profile and clear account key")
                _state.update {
                    it.copy(
                        isAccountActionProcessing = false,
                        accountActionError = e.message
                            ?.let { "アカウントを削除できませんでした: $it" }
                            ?: "アカウントを削除できませんでした",
                    )
                }
            }
        }
    }

    fun clearAccountActionError() {
        _state.update { it.copy(accountActionError = null) }
    }
}
