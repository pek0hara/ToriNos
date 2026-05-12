package com.nostr.torinos.ui.post

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ImageAttachment(
    val id: Int,
    val previewBytes: ByteArray?,
    val uploadedUrl: String?,
    val isUploading: Boolean,
)

data class PostState(
    val text: String = "",
    val isPosting: Boolean = false,
    val images: List<ImageAttachment> = emptyList(),
    val error: String? = null,
    val posted: Boolean = false,
) {
    val isUploadingAny: Boolean get() = images.any { it.isUploading }
    val canPost: Boolean get() =
        (text.isNotBlank() || images.any { it.uploadedUrl != null }) &&
            !isPosting && !isUploadingAny
}

private const val MAX_IMAGES = 4

class PostViewModel : SafeViewModel() {
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state.asStateFlow()
    private var nextImageId = 0

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(text = text, error = null, posted = false)
    }

    fun uploadAndAppendImage(bytes: ByteArray, mimeType: String) {
        if (_state.value.images.size >= MAX_IMAGES) return
        val id = nextImageId++
        _state.update { s ->
            s.copy(
                images = s.images + ImageAttachment(id = id, previewBytes = bytes, uploadedUrl = null, isUploading = true),
                error = null,
            )
        }
        launch {
            ImageUploader.upload(bytes, mimeType)
                .onSuccess { url ->
                    _state.update { s ->
                        s.copy(images = s.images.map { if (it.id == id) it.copy(uploadedUrl = url, isUploading = false) else it })
                    }
                }
                .onFailure { e ->
                    _state.update { s ->
                        s.copy(
                            images = s.images.map { if (it.id == id) it.copy(isUploading = false) else it },
                            error = "画像のアップロードに失敗しました: ${e.message}",
                        )
                    }
                }
        }
    }

    fun removeImage(id: Int) {
        _state.update { s -> s.copy(images = s.images.filter { it.id != id }) }
    }

    fun post(
        replyToId: String? = null,
        replyToPubkey: String? = null,
        noteContext: NoteContext = NoteContext.Timeline,
    ) {
        val current = _state.value
        val uploadedUrls = current.images.mapNotNull { it.uploadedUrl }
        val text = buildPostContent(current.text, uploadedUrls)
        if (text.isBlank()) return

        _state.value = _state.value.copy(isPosting = true, error = null)
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.copy(isPosting = false, error = "秘密鍵が設定されていません")
                return@launch
            }

            val tags = buildList {
                addAll(noteContext.replyTags(replyToId, replyToPubkey))
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
                add(listOf("client", "ToriNos"))
            }

            runCatching {
                val event = signEvent(privateKeyHex, text, kind = noteContext.eventKind, tags = tags)
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

    private fun buildPostContent(text: String, imageUrls: List<String>): String {
        val body = text.trim()
        val urls = imageUrls.filter { it.isNotBlank() }
        if (urls.isEmpty()) return body
        val urlBlock = urls.joinToString("\n")
        return if (body.isBlank()) urlBlock else "$body\n$urlBlock"
    }
}
