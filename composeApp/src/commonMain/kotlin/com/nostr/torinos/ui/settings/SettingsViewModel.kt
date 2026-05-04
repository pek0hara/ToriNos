package com.nostr.torinos.ui.settings

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.StoredAccount
import com.nostr.torinos.crypto.hexToNsec
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
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
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _secretKeyEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val secretKeyEvent: SharedFlow<String> = _secretKeyEvent.asSharedFlow()

    private val _secretKeyClipboardEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val secretKeyClipboardEvent: SharedFlow<String> = _secretKeyClipboardEvent.asSharedFlow()

    private val profileSubId = "settings-account-profiles"

    init {
        launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                event.toProfile()?.let { profile ->
                    _state.update { it.copy(profiles = it.profiles + (event.pubkey to profile)) }
                }
            }
        }
        refreshAccounts()
    }

    fun refreshAccounts() {
        launch {
            val accounts = runCatching { KeyStorage.listAccounts() }.getOrElse { e ->
                logException("SettingsViewModel", e, "Failed to load accounts")
                emptyList()
            }
            _state.update { it.copy(accounts = accounts) }
            if (accounts.isNotEmpty()) {
                NostrRepository.subscribe(
                    profileSubId,
                    NostrFilter(kinds = listOf(0), authors = accounts.map { it.pubkeyHex }),
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        NostrRepository.close(profileSubId)
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
                KeyStorage.switchAccount(pubkeyHex)
                val accounts = KeyStorage.listAccounts()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        isAccountActionProcessing = false,
                        accountActionError = null,
                    )
                }
                onSwitched(pubkeyHex)
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
                val privateKey = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が保存されていません")
                hexToNsec(privateKey)
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
                val privateKey = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が保存されていません")
                hexToNsec(privateKey)
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
                KeyStorage.deleteKey()
                val accounts = KeyStorage.listAccounts()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        isSecretKeyVisible = false,
                        keyError = null,
                        isAccountActionProcessing = false,
                        accountActionError = null,
                    )
                }
                onCleared(accounts.firstOrNull()?.pubkeyHex)
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
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が保存されていません")
                val vanishRequest = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 62,
                    tags = targets.map { listOf("relay", it) },
                )
                NostrRepository.publishToRelays(vanishRequest, targets)
                KeyStorage.deleteKey()
                val accounts = KeyStorage.listAccounts()
                _state.update {
                    it.copy(
                        accounts = accounts,
                        isSecretKeyVisible = false,
                        keyError = null,
                        isAccountActionProcessing = false,
                        accountActionError = null,
                    )
                }
                onCleared(accounts.firstOrNull()?.pubkeyHex)
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
