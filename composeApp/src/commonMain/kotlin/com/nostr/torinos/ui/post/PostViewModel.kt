package com.nostr.torinos.ui.post

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.profile.customEmojiTagsForContent
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ImageAttachment(
    val id: Int,
    val previewBytes: ByteArray?,
    val uploadedUrl: String?,
    val isUploading: Boolean,
)

data class PostState(
    val text: String = "",
    val isPosting: Boolean = false,
    val isSavingMemo: Boolean = false,
    val isLoadingMemo: Boolean = false,
    val images: List<ImageAttachment> = emptyList(),
    val error: String? = null,
    val memoMessage: String? = null,
    val posted: Boolean = false,
    val postedEventId: String? = null,
) {
    val isUploadingAny: Boolean get() = images.any { it.isUploading }
    val canPost: Boolean get() =
        (text.isNotBlank() || images.any { it.uploadedUrl != null }) &&
            !isPosting && !isUploadingAny
    val canSaveMemo: Boolean get() =
        (text.isNotBlank() || images.any { it.uploadedUrl != null }) &&
            !isPosting && !isSavingMemo && !isUploadingAny
}

private const val MAX_IMAGES = 4
internal const val MEMO_EVENT_KIND = 31234
internal const val MEMO_FETCH_TIMEOUT_MS = 8_000L
private const val MEMO_IDENTIFIER_POST = "torinos-post-memo"
internal val memoJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}

@Serializable
internal data class PostMemoPayload(
    val text: String = "",
    val imageUrls: List<String> = emptyList(),
    val replyToId: String? = null,
    val replyToPubkey: String? = null,
    val noteKind: Int = 1,
    val channelId: String? = null,
    val updatedAt: Long,
)

data class PostMemoData(
    val text: String,
    val imageUrls: List<String>,
    val replyToId: String?,
    val replyToPubkey: String?,
    val noteKind: Int,
    val channelId: String?,
    val updatedAt: Long,
    val identifier: String? = null,
)

internal fun PostMemoPayload.toPostMemoData(identifier: String? = null): PostMemoData =
    PostMemoData(
        text = text,
        imageUrls = imageUrls,
        replyToId = replyToId,
        replyToPubkey = replyToPubkey,
        noteKind = noteKind,
        channelId = channelId,
        updatedAt = updatedAt,
        identifier = identifier,
    )

class PostViewModel : SafeViewModel() {
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state.asStateFlow()
    private var nextImageId = 0
    private var editingMemoIdentifier: String? = null
    private var editingMemoUpdatedAt: Long? = null

    fun onTextChange(text: String) {
        _state.value = _state.value.copy(
            text = text,
            error = null,
            memoMessage = null,
            posted = false,
            postedEventId = null,
        )
    }

    fun uploadAndAppendImage(
        bytes: ByteArray,
        mimeType: String,
        previewBytes: ByteArray = bytes,
    ) {
        if (_state.value.images.size >= MAX_IMAGES) return
        val id = nextImageId++
        _state.update { s ->
            s.copy(
                images = s.images + ImageAttachment(
                    id = id,
                    previewBytes = previewBytes,
                    uploadedUrl = null,
                    isUploading = true,
                ),
                error = null,
            )
        }
        launch {
            withContext(Dispatchers.Default) {
                ImageUploader.upload(bytes, mimeType)
            }
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
        _state.update { s -> s.copy(images = s.images.filter { it.id != id }, memoMessage = null) }
    }

    fun restoreMemo(memo: PostMemoData, message: String? = null) {
        val restoredImages = memo.imageUrls
            .filter { it.isNotBlank() }
            .take(MAX_IMAGES)
            .map { url ->
                ImageAttachment(
                    id = nextImageId++,
                    previewBytes = null,
                    uploadedUrl = url,
                    isUploading = false,
                )
        }
        editingMemoIdentifier = memo.identifier
        editingMemoUpdatedAt = memo.updatedAt
        _state.value = PostState(
            text = memo.text,
            images = restoredImages,
            memoMessage = message,
        )
    }

    fun currentMemoSnapshot(
        replyToId: String? = null,
        replyToPubkey: String? = null,
        noteContext: NoteContext = NoteContext.Timeline,
    ): PostMemoData? {
        val current = _state.value
        val uploadedUrls = current.images.mapNotNull { it.uploadedUrl }
        if (current.text.isBlank() && uploadedUrls.isEmpty()) return null
        return PostMemoData(
            text = current.text,
            imageUrls = uploadedUrls,
            replyToId = replyToId,
            replyToPubkey = replyToPubkey,
            noteKind = noteContext.eventKind,
            channelId = (noteContext as? NoteContext.Channel)?.channelId,
            updatedAt = Clock.System.now().epochSeconds,
            identifier = editingMemoIdentifier,
        )
    }

    fun saveMemo(
        replyToId: String? = null,
        replyToPubkey: String? = null,
        noteContext: NoteContext = NoteContext.Timeline,
        onSaved: (() -> Unit)? = null,
    ) {
        val current = _state.value
        val uploadedUrls = current.images.mapNotNull { it.uploadedUrl }
        if (current.text.isBlank() && uploadedUrls.isEmpty()) return

        _state.value = current.copy(isSavingMemo = true, error = null, memoMessage = null)
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.copy(isSavingMemo = false, error = "秘密鍵が設定されていません")
                return@launch
            }
            val publicKeyHex = runCatching { derivePublicKey(privateKeyHex.fromHex()).toHex() }
                .getOrElse {
                    _state.value = _state.value.copy(isSavingMemo = false, error = "公開鍵の取得に失敗しました")
                    return@launch
            }

            val updatedAt = nextMemoUpdatedAt(
                now = Clock.System.now().epochSeconds,
                previousUpdatedAt = editingMemoUpdatedAt,
            )
            val identifier = editingMemoIdentifier ?: memoEventIdentifier(replyToId, updatedAt)
            val memo = PostMemoPayload(
                text = current.text,
                imageUrls = uploadedUrls,
                replyToId = replyToId,
                replyToPubkey = replyToPubkey,
                noteKind = noteContext.eventKind,
                channelId = (noteContext as? NoteContext.Channel)?.channelId,
                updatedAt = updatedAt,
            )

            runCatching {
                val plaintext = memoJson.encodeToString(memo)
                val content = Nip44.encrypt(plaintext, privateKeyHex, publicKeyHex)
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = content,
                    kind = MEMO_EVENT_KIND,
                    tags = listOf(
                        listOf("d", identifier),
                        listOf("client", "ToriNos"),
                    ),
                    createdAt = updatedAt,
                )
                NostrRepository.publish(event)
            }.onSuccess {
                editingMemoIdentifier = identifier
                editingMemoUpdatedAt = updatedAt
                _state.value = _state.value.copy(
                    isSavingMemo = false,
                    memoMessage = "ポストメモを保存しました",
                )
                onSaved?.invoke()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isSavingMemo = false,
                    error = e.message ?: "ポストメモの保存に失敗しました",
                )
            }
        }
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
                addAll(customEmojiTagsForContent(text, CustomEmojiStore.emojis.value))
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
                event
            }.onSuccess { event ->
                _state.value = PostState(posted = true, postedEventId = event.id)
            }.onFailure { e ->
                _state.value = _state.value.copy(isPosting = false, error = e.message ?: "ポストに失敗しました")
            }
        }
    }

    fun clearPosted() {
        _state.value = _state.value.copy(posted = false, postedEventId = null)
    }

    private fun buildPostContent(text: String, imageUrls: List<String>): String {
        val body = text.trim()
        val urls = imageUrls.filter { it.isNotBlank() }
        if (urls.isEmpty()) return body
        val urlBlock = urls.joinToString("\n")
        return if (body.isBlank()) urlBlock else "$body\n$urlBlock"
    }

    private fun memoIdentifier(replyToId: String?): String =
        replyToId?.let { "torinos-reply-memo-$it" } ?: MEMO_IDENTIFIER_POST

    private fun memoEventIdentifier(replyToId: String?, updatedAt: Long): String =
        "${memoIdentifier(replyToId)}-$updatedAt"
}

internal fun nextMemoUpdatedAt(now: Long, previousUpdatedAt: Long?): Long =
    when {
        previousUpdatedAt == null || previousUpdatedAt < now -> now
        previousUpdatedAt == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> previousUpdatedAt + 1
    }
