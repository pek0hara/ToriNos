package com.nostr.torinos.ui.profile

import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.emoji.customEmojiTagsForContent

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.Nip05Repository
import com.nostr.torinos.network.Nip05Status
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 既存のプロフィールの読み込み。読み込む前に保存すると、空欄で今のプロフィールを上書きしてしまう。 */
enum class ProfileLoadState {
    Loading,
    Loaded,
    /** 見つからなかった（初めてのアカウント、またはリレーから取れなかった）。入力した内容だけで保存する。 */
    NotFound,
}

data class EditProfileState(
    val name: String = "",
    val displayName: String = "",
    val about: String = "",
    val picture: String = "",
    val banner: String = "",
    val nip05: String = "",
    /** Lightning アドレス（`lud16`）。Zap の受け取り先。 */
    val lud16: String = "",
    /** 編集欄はないが、保存で消さないように持ち回す（`lud06`）。 */
    val lud06: String = "",
    val isSaving: Boolean = false,
    val isUploadingImage: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val savedProfile: NostrProfile? = null,
    val loadState: ProfileLoadState = ProfileLoadState.Loading,
) {
    companion object {
        fun initial(loadExistingProfile: Boolean) = EditProfileState(
            loadState = if (loadExistingProfile) ProfileLoadState.Loading else ProfileLoadState.NotFound,
        )
    }
}

/** 設定画面で1つずつ変える項目（画像の URL・NIP-05 は「プロフィールの詳細」、Lightning アドレスは「Zap」）。編集シートには出さない。 */
enum class ProfileDetailField(
    val title: String,
    /** 編集シートの見出し。左右のボタンに挟まれるので短くする。 */
    val shortTitle: String,
    val placeholder: String,
    val description: String?,
) {
    Picture("アイコン画像の URL", "アイコン", "https://", "写真から選ぶときは、プロフィール編集でアイコンをタップします"),
    Banner("バナー画像の URL", "バナー", "https://", "写真から選ぶときは、プロフィール編集でバナーをタップします"),
    Nip05("NIP-05 アドレス", "NIP-05", "alice@example.com", "取得済みのアドレスを入力します"),
    Lud16("Lightning アドレス", "Lightning", "name@example.com", "Zap を受け取るアドレスです。プロフィールに載ります"),
}

internal fun EditProfileState.profileDetail(field: ProfileDetailField): String = when (field) {
    ProfileDetailField.Picture -> picture
    ProfileDetailField.Banner -> banner
    ProfileDetailField.Nip05 -> nip05
    ProfileDetailField.Lud16 -> lud16
}

internal fun EditProfileState.withProfileDetail(field: ProfileDetailField, value: String): EditProfileState = when (field) {
    ProfileDetailField.Picture -> copy(picture = value, error = null)
    ProfileDetailField.Banner -> copy(banner = value, error = null)
    ProfileDetailField.Nip05 -> copy(nip05 = value, error = null)
    ProfileDetailField.Lud16 -> copy(lud16 = value, error = null)
}

/** 保存できない理由。保存できるなら null。 */
internal fun profileSaveBlocker(state: EditProfileState): String? {
    if (state.loadState == ProfileLoadState.Loading) return "プロフィールを読み込んでいます"
    val lud16 = state.lud16.trim()
    if (lud16.isNotEmpty() && com.nostr.torinos.zap.lud16ToUrl(lud16) == null) {
        return "Lightning アドレスは name@example.com の形で入力してください"
    }
    return null
}

class EditProfileViewModel(
    private val accountSession: AccountSession? = null,
    loadExistingProfile: Boolean = true,
) : SafeViewModel() {
    companion object {
        private var instanceCounter = 0
    }
    private val _state = MutableStateFlow(EditProfileState.initial(loadExistingProfile))
    val state: StateFlow<EditProfileState> = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        if (loadExistingProfile) reload()
    }

    /** 自分の今のプロフィールを読み込み直す。読み込み終わるまでは保存できない。 */
    fun reload() {
        // 読み込み中なら、その結果が最新なので重ねて取りに行かない（開いた直後の init と画面側の呼び出しが重なるため）。
        if (loadJob?.isActive == true) return
        _state.value = _state.value.copy(loadState = ProfileLoadState.Loading, error = null)
        loadJob = launch {
            val pubkey = accountSession?.pubkey
            val profile = pubkey?.let {
                ProfileRepository.awaitProfiles(
                    setOf(it),
                    ProfileFetchPolicy.CacheFirst(15 * 60 * 1_000L),
                    timeoutMillis = 10_000L,
                )[it]
            }
            if (profile == null) {
                _state.value = _state.value.copy(loadState = ProfileLoadState.NotFound)
            } else {
                initFrom(profile)
            }
        }
    }

    // 外から表示中のプロフィールを渡せると、古い内容で上書きする入口になるため、読み込み結果からだけ使う。
    private fun initFrom(profile: NostrProfile) {
        _state.value = _state.value.copy(
            name = profile.name ?: "",
            displayName = profile.displayName ?: "",
            about = profile.about ?: "",
            picture = profile.picture ?: "",
            banner = profile.banner ?: "",
            nip05 = profile.nip05 ?: "",
            lud16 = profile.lud16 ?: "",
            lud06 = profile.lud06 ?: "",
            error = null,
            loadState = ProfileLoadState.Loaded,
        )
    }

    fun onNameChange(v: String) { _state.value = _state.value.copy(name = v, error = null) }
    fun onDisplayNameChange(v: String) { _state.value = _state.value.copy(displayName = v, error = null) }
    fun onAboutChange(v: String) { _state.value = _state.value.copy(about = v, error = null) }
    fun onPictureChange(v: String) { _state.value = _state.value.copy(picture = v, error = null) }
    fun onBannerChange(v: String) { _state.value = _state.value.copy(banner = v, error = null) }
    fun onNip05Change(v: String) { _state.value = _state.value.copy(nip05 = v, error = null) }
    fun onLud16Change(v: String) { _state.value = _state.value.copy(lud16 = v, error = null) }
    fun onProfileDetailChange(field: ProfileDetailField, value: String) {
        _state.value = _state.value.withProfileDetail(field, value)
    }

    suspend fun verifyNip05(address: String): Nip05Status {
        val pubkey = accountSession?.pubkey ?: return Nip05Status.Unchecked
        return Nip05Repository.verify(address, pubkey, forceRefresh = true)
    }

    fun uploadProfileImage(bytes: ByteArray, mimeType: String) {
        uploadImage(bytes, mimeType, ImageUploadTarget.Picture)
    }

    fun uploadBannerImage(bytes: ByteArray, mimeType: String) {
        uploadImage(bytes, mimeType, ImageUploadTarget.Banner)
    }

    private fun uploadImage(bytes: ByteArray, mimeType: String, target: ImageUploadTarget) {
        _state.value = _state.value.copy(isUploadingImage = true, error = null)
        launch {
            ImageUploader.upload(bytes, mimeType, accountSession?.signer)
                .onSuccess { url ->
                    _state.value = when (target) {
                        ImageUploadTarget.Picture -> _state.value.copy(picture = url, isUploadingImage = false)
                        ImageUploadTarget.Banner -> _state.value.copy(banner = url, isUploadingImage = false)
                    }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isUploadingImage = false,
                        error = "画像のアップロードに失敗しました: ${e.message}",
                    )
                }
        }
    }

    fun save() {
        profileSaveBlocker(_state.value)?.let { reason ->
            _state.value = _state.value.copy(error = reason)
            return
        }
        _state.value = _state.value.copy(isSaving = true, error = null)
        launch {
            val signer = accountSession?.signer ?: run {
                _state.value = _state.value.copy(isSaving = false, error = "秘密鍵が設定されていません")
                return@launch
            }

            val s = _state.value
            val contentJson = buildJsonObject {
                if (s.name.isNotBlank()) put("name", s.name.trim())
                if (s.displayName.isNotBlank()) put("display_name", s.displayName.trim())
                if (s.about.isNotBlank()) put("about", s.about.trim())
                if (s.picture.isNotBlank()) put("picture", s.picture.trim())
                if (s.banner.isNotBlank()) put("banner", s.banner.trim())
                if (s.nip05.isNotBlank()) put("nip05", s.nip05.trim())
                if (s.lud16.isNotBlank()) put("lud16", s.lud16.trim())
                if (s.lud06.isNotBlank()) put("lud06", s.lud06.trim())
            }

            val emojiTags = customEmojiTagsForContent(
                listOf(s.name, s.displayName).joinToString(" "),
                accountSession.customEmojis.preferences.value.available,
                accountSession.customEmojis.preferences.value::setAddressOf,
            )
            runCatching {
                val event = signer.sign(
                    Json.encodeToString(contentJson),
                    kind = 0,
                    tags = emojiTags,
                )
                NostrRepository.publish(event)
                event
            }.onSuccess {
                val customEmojis = emojiTags.customEmojiMap()
                val savedProfile = NostrProfile(
                    name = s.name.trim().ifBlank { null },
                    displayName = s.displayName.trim().ifBlank { null },
                    about = s.about.trim().ifBlank { null },
                    picture = s.picture.trim().ifBlank { null },
                    banner = s.banner.trim().ifBlank { null },
                    nip05 = s.nip05.trim().ifBlank { null },
                    lud16 = s.lud16.trim().ifBlank { null },
                    lud06 = s.lud06.trim().ifBlank { null },
                    customEmojis = customEmojis,
                )
                ProfileRepository.applyOptimistic(it.pubkey, savedProfile)
                _state.value = _state.value.copy(isSaving = false, saved = true, savedProfile = savedProfile)
            }.onFailure { e ->
                _state.value = _state.value.copy(isSaving = false, error = e.message ?: "保存に失敗しました")
            }
        }
    }

    fun clearSaved() {
        _state.value = _state.value.copy(saved = false)
    }
}

private enum class ImageUploadTarget {
    Picture,
    Banner,
}
