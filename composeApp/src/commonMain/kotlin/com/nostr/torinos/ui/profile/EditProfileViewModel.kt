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
    /** キャッシュもなく、取得を待っている。入力も保存もできない。 */
    Loading,
    /**
     * キャッシュの内容で入力できる状態にして、裏で最新を取り直している。
     * 取り直しが届いたら、触っていない欄だけ最新に差し替える。保存は取り直しの完了を待ってから送る。
     */
    Refreshing,
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

/** kind 0 のうち編集画面で持ち回す項目。取り直しの差し替えで、開いた時点の値と比べるのに使う。 */
internal data class ProfileFields(
    val name: String = "",
    val displayName: String = "",
    val about: String = "",
    val picture: String = "",
    val banner: String = "",
    val nip05: String = "",
    val lud16: String = "",
    val lud06: String = "",
) {
    companion object {
        fun from(profile: NostrProfile) = ProfileFields(
            name = profile.name.orEmpty(),
            displayName = profile.displayName.orEmpty(),
            about = profile.about.orEmpty(),
            picture = profile.picture.orEmpty(),
            banner = profile.banner.orEmpty(),
            nip05 = profile.nip05.orEmpty(),
            lud16 = profile.lud16.orEmpty(),
            lud06 = profile.lud06.orEmpty(),
        )
    }
}

internal fun EditProfileState.withFields(fields: ProfileFields): EditProfileState = copy(
    name = fields.name,
    displayName = fields.displayName,
    about = fields.about,
    picture = fields.picture,
    banner = fields.banner,
    nip05 = fields.nip05,
    lud16 = fields.lud16,
    lud06 = fields.lud06,
)

/**
 * キャッシュ [base] で開いたあとに届いた最新 [fresh] を当てる。開いてから変えていない欄だけ最新にし、
 * 変えた欄（入力・画像のアップロード）はそのまま残す。他のクライアントで変えた値を古い値で上書きしないため。
 */
internal fun EditProfileState.mergeFresh(base: ProfileFields, fresh: ProfileFields): EditProfileState {
    fun pick(current: String, base: String, fresh: String) = if (current == base) fresh else current
    return copy(
        name = pick(name, base.name, fresh.name),
        displayName = pick(displayName, base.displayName, fresh.displayName),
        about = pick(about, base.about, fresh.about),
        picture = pick(picture, base.picture, fresh.picture),
        banner = pick(banner, base.banner, fresh.banner),
        nip05 = pick(nip05, base.nip05, fresh.nip05),
        lud16 = pick(lud16, base.lud16, fresh.lud16),
        lud06 = fresh.lud06,
        loadState = ProfileLoadState.Loaded,
    )
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

    /**
     * 自分の今のプロフィールを読み込み直す。キャッシュがあればすぐその内容で入力できるようにし、
     * 最新の取り直しは裏で待つ（全リレーの応答を待つので、遅いリレーがあると数秒かかる）。
     */
    fun reload() {
        // 読み込み中なら、その結果が最新なので重ねて取りに行かない（開いた直後の init と画面側の呼び出しが重なるため）。
        if (loadJob?.isActive == true) return
        val pubkey = accountSession?.pubkey
        // 外から表示中のプロフィールを渡せると古い内容で上書きする入口になるため、キャッシュからだけ埋める。
        val base = pubkey?.let(ProfileRepository::getCached)?.let(ProfileFields::from)
        _state.value = if (base != null) {
            _state.value.withFields(base).copy(loadState = ProfileLoadState.Refreshing, error = null)
        } else {
            _state.value.copy(loadState = ProfileLoadState.Loading, error = null)
        }
        loadJob = launch {
            // 保存すると kind 0 を丸ごと置き換えるので、キャッシュがあっても取り直しの完了を待つ
            // （他のクライアントで変えた Lightning アドレスなどを古い値で上書きしないように）。
            val profile = pubkey?.let { ProfileRepository.awaitFresh(it, maxAgeMillis = 0L, timeoutMillis = 10_000L) }
            val current = _state.value
            _state.value = when {
                profile == null -> current.copy(loadState = ProfileLoadState.NotFound)
                base == null -> current.withFields(ProfileFields.from(profile)).copy(loadState = ProfileLoadState.Loaded)
                else -> current.mergeFresh(base = base, fresh = ProfileFields.from(profile))
            }
        }
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
            // キャッシュで開いて取り直し中なら、最新を当て終わってから送る。
            loadJob?.join()
            profileSaveBlocker(_state.value)?.let { reason ->
                _state.value = _state.value.copy(isSaving = false, error = reason)
                return@launch
            }
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
