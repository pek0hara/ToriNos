package com.nostr.torinos.ui.post

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PostState(
    val text: String = "",
    val isPosting: Boolean = false,
    val isUploadingImage: Boolean = false,
    val error: String? = null,
    val posted: Boolean = false,
)

class PostViewModel : SafeViewModel() {
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state.asStateFlow()

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(text = text, error = null, posted = false)
    }

    fun uploadAndAppendImage(bytes: ByteArray, mimeType: String) {
        _state.value = _state.value.copy(isUploadingImage = true, error = null)
        launch {
            ImageUploader.upload(bytes, mimeType)
                .onSuccess { url ->
                    val current = _state.value.text
                    val separator = if (current.isNotEmpty() && !current.endsWith("\n")) "\n" else ""
                    _state.value = _state.value.copy(
                        text = current + separator + url,
                        isUploadingImage = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isUploadingImage = false,
                        error = "画像のアップロードに失敗しました: ${e.message}",
                    )
                }
        }
    }

    fun post(replyToId: String? = null, replyToPubkey: String? = null) {
        val text = _state.value.text.trim()
        if (text.isBlank()) return

        _state.value = _state.value.copy(isPosting = true, error = null)
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.copy(isPosting = false, error = "秘密鍵が設定されていません")
                return@launch
            }

            val tags = buildList {
                if (replyToId != null) add(listOf("e", replyToId))
                if (replyToPubkey != null) add(listOf("p", replyToPubkey))
            }

            runCatching {
                val event = signEvent(privateKeyHex, text, tags = tags)
                NostrRepository.publish(event)
            }.onSuccess {
                _state.value = PostState(posted = true)
            }.onFailure { e ->
                _state.value = _state.value.copy(isPosting = false, error = e.message ?: "投稿に失敗しました")
            }
        }
    }

    fun clearPosted() {
        _state.value = _state.value.copy(posted = false)
    }
}
