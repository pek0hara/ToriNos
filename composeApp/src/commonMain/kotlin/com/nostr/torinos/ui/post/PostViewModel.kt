package com.nostr.torinos.ui.post

import androidx.lifecycle.ViewModel
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.extractNostrEventReferences
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
    val imagePreviewBytes: ByteArray? = null,
    val uploadedImageUrl: String? = null,
    val error: String? = null,
    val posted: Boolean = false,
)

class PostViewModel : SafeViewModel() {
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state.asStateFlow()
    private var imageUploadSerial = 0

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(text = text, error = null, posted = false)
    }

    fun uploadAndAppendImage(bytes: ByteArray, mimeType: String) {
        val uploadSerial = ++imageUploadSerial
        _state.value = _state.value.copy(
            isUploadingImage = true,
            imagePreviewBytes = bytes,
            uploadedImageUrl = null,
            error = null,
        )
        launch {
            ImageUploader.upload(bytes, mimeType)
                .also {
                    if (uploadSerial != imageUploadSerial) return@launch
                }
                .onSuccess { url ->
                    _state.value = _state.value.copy(
                        isUploadingImage = false,
                        uploadedImageUrl = url,
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

    fun clearAttachedImage() {
        imageUploadSerial++
        val current = _state.value
        val text = current.uploadedImageUrl?.let { url ->
            current.text
                .replace(url, "")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        } ?: current.text
        _state.value = current.copy(
            text = text,
            isUploadingImage = false,
            imagePreviewBytes = null,
            uploadedImageUrl = null,
            error = null,
        )
    }

    fun post(replyToId: String? = null, replyToPubkey: String? = null) {
        val current = _state.value
        val text = buildPostContent(current.text, current.uploadedImageUrl)
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
                extractNostrEventReferences(text).forEach { reference ->
                    add(
                        buildList {
                            add("q")
                            add(reference.eventId)
                            reference.relayUrls.firstOrNull()?.let { add(it) }
                        },
                    )
                    reference.authorPubkey?.let { add(listOf("p", it)) }
                }
            }

            runCatching {
                val event = signEvent(privateKeyHex, text, tags = tags)
                NostrRepository.publish(event)
            }.onSuccess {
                _state.value = PostState(posted = true)
            }.onFailure { e ->
                _state.value = _state.value.copy(isPosting = false, error = e.message ?: "ポストに失敗しました")
            }
        }
    }

    fun clearPosted() {
        _state.value = _state.value.copy(posted = false)
    }

    private fun buildPostContent(text: String, imageUrl: String?): String {
        val body = text.trim()
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return body
        return if (body.isBlank()) url else "$body\n$url"
    }
}
