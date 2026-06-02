package com.nostr.torinos.ui.profile

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class EditProfileState(
    val name: String = "",
    val displayName: String = "",
    val about: String = "",
    val picture: String = "",
    val banner: String = "",
    val nip05: String = "",
    val isSaving: Boolean = false,
    val isUploadingImage: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val savedProfile: NostrProfile? = null,
)

class EditProfileViewModel : SafeViewModel() {
    companion object {
        private var instanceCounter = 0
    }
    private val _state = MutableStateFlow(EditProfileState())
    val state: StateFlow<EditProfileState> = _state.asStateFlow()

    // インスタンスごとにユニークな subId を生成（同時に複数開いた場合の競合を防ぐ）
    private val subId = "ep-${instanceCounter++}"

    init {
        launch {
            val pubkey = loadPublicKey() ?: return@launch

            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(0), authors = listOf(pubkey), limit = 1),
            )

            // リレーが応答しない場合に無限待機しないようタイムアウトを設定
            val event = withTimeoutOrNull(10_000) {
                NostrRepository.events(subId).firstOrNull { it.kind == 0 }
            }
            NostrRepository.close(subId)

            val profile = event?.toProfile() ?: return@launch
            _state.value = _state.value.copy(
                name = profile.name ?: "",
                displayName = profile.displayName ?: "",
                about = profile.about ?: "",
                picture = profile.picture ?: "",
                banner = profile.banner ?: "",
                nip05 = profile.nip05 ?: "",
            )
        }
    }

    fun initFrom(profile: NostrProfile) {
        _state.value = _state.value.copy(
            name = profile.name ?: "",
            displayName = profile.displayName ?: "",
            about = profile.about ?: "",
            picture = profile.picture ?: "",
            banner = profile.banner ?: "",
            nip05 = profile.nip05 ?: "",
            error = null,
        )
    }

    fun onNameChange(v: String) { _state.value = _state.value.copy(name = v, error = null) }
    fun onDisplayNameChange(v: String) { _state.value = _state.value.copy(displayName = v, error = null) }
    fun onAboutChange(v: String) { _state.value = _state.value.copy(about = v, error = null) }
    fun onPictureChange(v: String) { _state.value = _state.value.copy(picture = v, error = null) }
    fun onBannerChange(v: String) { _state.value = _state.value.copy(banner = v, error = null) }
    fun onNip05Change(v: String) { _state.value = _state.value.copy(nip05 = v, error = null) }

    fun uploadProfileImage(bytes: ByteArray, mimeType: String) {
        uploadImage(bytes, mimeType, ImageUploadTarget.Picture)
    }

    fun uploadBannerImage(bytes: ByteArray, mimeType: String) {
        uploadImage(bytes, mimeType, ImageUploadTarget.Banner)
    }

    private fun uploadImage(bytes: ByteArray, mimeType: String, target: ImageUploadTarget) {
        _state.value = _state.value.copy(isUploadingImage = true, error = null)
        launch {
            ImageUploader.upload(bytes, mimeType)
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
        _state.value = _state.value.copy(isSaving = true, error = null)
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
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
            }

            runCatching {
                val event = signEvent(
                    privateKeyHex,
                    Json.encodeToString(contentJson),
                    kind = 0,
                    tags = customEmojiTagsForContent(
                        listOf(s.name, s.displayName).joinToString(" "),
                        CustomEmojiStore.emojis.value,
                    ),
                )
                NostrRepository.publish(event)
            }.onSuccess {
                val customEmojis = customEmojiTagsForContent(
                    listOf(s.name, s.displayName).joinToString(" "),
                    CustomEmojiStore.emojis.value,
                ).customEmojiMap()
                val savedProfile = NostrProfile(
                    name = s.name.trim().ifBlank { null },
                    displayName = s.displayName.trim().ifBlank { null },
                    about = s.about.trim().ifBlank { null },
                    picture = s.picture.trim().ifBlank { null },
                    banner = s.banner.trim().ifBlank { null },
                    nip05 = s.nip05.trim().ifBlank { null },
                    customEmojis = customEmojis,
                )
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
