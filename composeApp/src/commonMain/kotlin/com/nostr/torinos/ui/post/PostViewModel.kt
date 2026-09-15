package com.nostr.torinos.ui.post

import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.MediaMetadata
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.profile.customEmojiTagsForContent
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
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
    val mediaMetadata: MediaMetadata? = null,
)

data class PostState(
    val text: String = "",
    val customEmojis: List<CustomEmoji> = emptyList(),
    val isPosting: Boolean = false,
    val isSavingMemo: Boolean = false,
    val isLoadingMemo: Boolean = false,
    val images: List<ImageAttachment> = emptyList(),
    val error: String? = null,
    val memoMessage: String? = null,
    val posted: Boolean = false,
    val postedEventId: String? = null,
    val publishResult: RelayPublishResult? = null,
    val postWarning: String? = null,
    val draftDeleted: Boolean = false,
) {
    val isUploadingAny: Boolean get() = images.any { it.isUploading }
    val hasDraftContent: Boolean get() =
        text.isNotBlank() || images.any { it.uploadedUrl != null }
    val canPost: Boolean get() =
        hasDraftContent &&
            !isPosting && !isUploadingAny
    val canSaveMemo: Boolean get() =
        hasDraftContent &&
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
    val customEmojis: List<CustomEmoji> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val imageMetadata: List<MediaMetadata> = emptyList(),
    val replyToId: String? = null,
    val replyToPubkey: String? = null,
    val noteKind: Int = 1,
    val channelId: String? = null,
    val replyRootId: String? = null,
    val replyRootKind: Int? = null,
    val replyRootPubkey: String? = null,
    val replyParentKind: Int? = null,
    val replyRootRelayUrl: String? = null,
    val replyRootPubkeyRelayUrl: String? = null,
    val replyParentRelayUrl: String? = null,
    val replyParentPubkeyRelayUrl: String? = null,
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
    val sourceEventId: String? = null,
    val sourcePubkey: String? = null,
    val customEmojis: List<CustomEmoji> = emptyList(),
    val imageMetadata: List<MediaMetadata> = emptyList(),
    val replyRootId: String? = null,
    val replyRootKind: Int? = null,
    val replyRootPubkey: String? = null,
    val replyParentKind: Int? = null,
    val replyRootRelayUrl: String? = null,
    val replyRootPubkeyRelayUrl: String? = null,
    val replyParentRelayUrl: String? = null,
    val replyParentPubkeyRelayUrl: String? = null,
)

internal fun PostMemoPayload.toPostMemoData(
    identifier: String? = null,
    sourceEventId: String? = null,
    sourcePubkey: String? = null,
): PostMemoData =
    PostMemoData(
        text = text,
        customEmojis = customEmojis,
        imageUrls = imageUrls,
        imageMetadata = imageMetadata,
        replyToId = replyToId,
        replyToPubkey = replyToPubkey,
        noteKind = noteKind,
        channelId = channelId,
        replyRootId = replyRootId,
        replyRootKind = replyRootKind,
        replyRootPubkey = replyRootPubkey,
        replyParentKind = replyParentKind,
        replyRootRelayUrl = replyRootRelayUrl,
        replyRootPubkeyRelayUrl = replyRootPubkeyRelayUrl,
        replyParentRelayUrl = replyParentRelayUrl,
        replyParentPubkeyRelayUrl = replyParentPubkeyRelayUrl,
        updatedAt = updatedAt,
        identifier = identifier,
        sourceEventId = sourceEventId,
        sourcePubkey = sourcePubkey,
    )

internal fun PostMemoData.restoreReplyTarget(noteContext: NoteContext): ReplyTarget? {
    val parentId = replyToId ?: return null
    val parentPubkey = replyToPubkey ?: return null
    val parent = ReplyEventReference(
        id = parentId,
        kind = replyParentKind ?: 1,
        pubkey = parentPubkey,
        relayUrl = replyParentRelayUrl,
        pubkeyRelayUrl = replyParentPubkeyRelayUrl,
    )
    return when (noteContext) {
        is NoteContext.Channel -> ReplyTarget.Channel(noteContext.channelId, parent.copy(kind = 42))
        NoteContext.Timeline -> {
            val root = if (replyRootId != null && replyRootKind != null && replyRootPubkey != null) {
                ReplyEventReference(
                    replyRootId,
                    replyRootKind,
                    replyRootPubkey,
                    replyRootRelayUrl,
                    replyRootPubkeyRelayUrl,
                )
            } else {
                if (parent.kind != 1) return null
                parent
            }
            ReplyTarget.Timeline(root = root, parent = parent)
        }
    }
}

class PostViewModel(
    private val accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(PostState())
    val state: StateFlow<PostState> = _state.asStateFlow()
    private var nextImageId = 0
    private var editingMemoIdentifier: String? = null
    private var editingMemoUpdatedAt: Long? = null
    private var editingMemoEventId: String? = null
    private var editingMemoPubkey: String? = null

    fun reset() {
        editingMemoIdentifier = null
        editingMemoUpdatedAt = null
        editingMemoEventId = null
        editingMemoPubkey = null
        nextImageId = 0
        _state.value = PostState()
    }

    fun onTextChange(text: String) = updateText(text)

    fun onCustomEmojiInserted(text: String, emoji: CustomEmoji) = updateText(text, emoji)

    private fun updateText(text: String, selectedEmoji: CustomEmoji? = null) {
        _state.value = _state.value.copy(
            text = text,
            customEmojis = resolveDraftEmojis(
                text,
                _state.value.customEmojis + listOfNotNull(selectedEmoji),
                CustomEmojiStore.emojis.value,
            ),
            error = null,
            memoMessage = null,
            posted = false,
            postedEventId = null,
            publishResult = null,
            postWarning = null,
            draftDeleted = false,
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
                ImageUploader.uploadMedia(bytes, mimeType, accountSession?.signer)
            }
                .onSuccess { metadata ->
                    _state.update { s ->
                        s.copy(
                            images = s.images.map {
                                if (it.id == id) {
                                    it.copy(
                                        uploadedUrl = metadata.url,
                                        isUploading = false,
                                        mediaMetadata = metadata,
                                    )
                                } else {
                                    it
                                }
                            },
                        )
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

    fun showImagePasteError() {
        _state.update { it.copy(error = "クリップボードに貼り付け可能な画像がありません") }
    }

    fun restoreMemo(memo: PostMemoData, message: String? = null) {
        val metadataByUrl = memo.imageMetadata.associateBy { it.url }
        val restoredImages = memo.imageUrls
            .filter { it.isNotBlank() }
            .take(MAX_IMAGES)
            .map { url ->
                ImageAttachment(
                    id = nextImageId++,
                    previewBytes = null,
                    uploadedUrl = url,
                    isUploading = false,
                    mediaMetadata = metadataByUrl[url],
                )
        }
        editingMemoIdentifier = memo.identifier
        editingMemoUpdatedAt = memo.updatedAt
        editingMemoEventId = memo.sourceEventId
        editingMemoPubkey = memo.sourcePubkey
        _state.value = PostState(
            text = memo.text,
            customEmojis = resolveDraftEmojis(memo.text, memo.customEmojis, CustomEmojiStore.emojis.value),
            images = restoredImages,
            memoMessage = message,
        )
    }

    fun currentMemoSnapshot(
        replyTarget: ReplyTarget? = null,
        noteContext: NoteContext = NoteContext.Timeline,
    ): PostMemoData? {
        val current = _state.value
        val uploadedUrls = current.images.mapNotNull { it.uploadedUrl }
        if (current.text.isBlank() && uploadedUrls.isEmpty()) return null
        return PostMemoData(
            text = current.text,
            customEmojis = current.customEmojis,
            imageUrls = uploadedUrls,
            imageMetadata = current.images.mapNotNull { attachment ->
                attachment.mediaMetadata?.takeIf { metadata ->
                    attachment.uploadedUrl == metadata.url
                }
            },
            replyToId = replyTarget?.parent?.id,
            replyToPubkey = replyTarget?.parent?.pubkey,
            noteKind = replyTarget?.eventKind ?: noteContext.eventKind,
            channelId = (noteContext as? NoteContext.Channel)?.channelId,
            replyRootId = (replyTarget as? ReplyTarget.Timeline)?.root?.id,
            replyRootKind = (replyTarget as? ReplyTarget.Timeline)?.root?.kind,
            replyRootPubkey = (replyTarget as? ReplyTarget.Timeline)?.root?.pubkey,
            replyParentKind = replyTarget?.parent?.kind,
            replyRootRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.relayUrl,
            replyRootPubkeyRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.pubkeyRelayUrl,
            replyParentRelayUrl = replyTarget?.parent?.relayUrl,
            replyParentPubkeyRelayUrl = replyTarget?.parent?.pubkeyRelayUrl,
            updatedAt = Clock.System.now().epochSeconds,
            identifier = editingMemoIdentifier,
            sourceEventId = editingMemoEventId,
            sourcePubkey = editingMemoPubkey,
        )
    }

    fun saveMemo(
        replyTarget: ReplyTarget? = null,
        noteContext: NoteContext = NoteContext.Timeline,
        onSaved: (() -> Unit)? = null,
    ) {
        val current = _state.value
        val uploadedUrls = current.images.mapNotNull { it.uploadedUrl }
        if (current.text.isBlank() && uploadedUrls.isEmpty()) return

        _state.value = current.copy(isSavingMemo = true, error = null, memoMessage = null)
        launch {
            val signer = accountSession?.signer ?: run {
                _state.value = _state.value.copy(isSavingMemo = false, error = "秘密鍵が設定されていません")
                return@launch
            }
            val updatedAt = nextMemoUpdatedAt(
                now = Clock.System.now().epochSeconds,
                previousUpdatedAt = editingMemoUpdatedAt,
            )
            val identifier = editingMemoIdentifier ?: memoEventIdentifier(replyTarget?.parent?.id, updatedAt)
            val memo = PostMemoPayload(
                text = current.text,
                customEmojis = current.customEmojis,
                imageUrls = uploadedUrls,
                imageMetadata = current.images.mapNotNull { attachment ->
                    attachment.mediaMetadata?.takeIf { metadata ->
                        attachment.uploadedUrl == metadata.url
                    }
                },
                replyToId = replyTarget?.parent?.id,
                replyToPubkey = replyTarget?.parent?.pubkey,
                noteKind = replyTarget?.eventKind ?: noteContext.eventKind,
                channelId = (noteContext as? NoteContext.Channel)?.channelId,
                replyRootId = (replyTarget as? ReplyTarget.Timeline)?.root?.id,
                replyRootKind = (replyTarget as? ReplyTarget.Timeline)?.root?.kind,
                replyRootPubkey = (replyTarget as? ReplyTarget.Timeline)?.root?.pubkey,
                replyParentKind = replyTarget?.parent?.kind,
                replyRootRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.relayUrl,
                replyRootPubkeyRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.pubkeyRelayUrl,
                replyParentRelayUrl = replyTarget?.parent?.relayUrl,
                replyParentPubkeyRelayUrl = replyTarget?.parent?.pubkeyRelayUrl,
                updatedAt = updatedAt,
            )

            runCatching {
                val plaintext = memoJson.encodeToString(memo)
                val content = signer.encryptToSelf(plaintext)
                val event = signer.sign(
                    content = content,
                    kind = MEMO_EVENT_KIND,
                    tags = listOf(
                        listOf("d", identifier),
                        listOf("client", "ToriNos"),
                    ),
                    createdAt = updatedAt,
                )
                NostrRepository.publish(event)
                event
            }.onSuccess {
                editingMemoIdentifier = identifier
                editingMemoUpdatedAt = updatedAt
                editingMemoEventId = it.id
                editingMemoPubkey = it.pubkey
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
        replyTarget: ReplyTarget? = null,
        noteContext: NoteContext = NoteContext.Timeline,
        quoteReference: String? = null,
        relayUrls: Collection<String>? = null,
    ) {
        val current = _state.value
        val uploadedUrls = current.images.mapNotNull { it.uploadedUrl }
        val text = buildPostContent(current.text, uploadedUrls, quoteReference)
        if (text.isBlank()) return
        val targetRelayUrls = relayUrls
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
        if (targetRelayUrls != null && targetRelayUrls.isEmpty()) {
            _state.value = current.copy(error = "送信先リレーを1つ以上選択してください")
            return
        }

        _state.value = _state.value.copy(isPosting = true, error = null)
        launch {
            val signer = accountSession?.signer ?: run {
                _state.value = _state.value.copy(isPosting = false, error = "秘密鍵が設定されていません")
                return@launch
            }

            val tags = buildList {
                addAll(replyTarget?.tags().orEmpty())
                addAll(customEmojiTagsForContent(text, current.customEmojis))
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
                addAll(imetaTagsForAttachments(text, current.images))
                add(listOf("client", "ToriNos"))
            }

            runCatching {
                val event = signer.sign(text, kind = replyTarget?.eventKind ?: noteContext.eventKind, tags = tags)
                val publishResult = if (targetRelayUrls == null) {
                    NostrRepository.publish(event)
                } else {
                    NostrRepository.publishToRelaysWithResult(event, targetRelayUrls).also { result ->
                        check(result.succeededRelays.isNotEmpty()) {
                            "すべてのリレーへの送信に失敗しました: " +
                                result.failedRelays.keys.joinToString()
                        }
                    }
                }
                event to publishResult
            }.onSuccess { (event, publishResult) ->
                val hadRestoredMemo = editingMemoEventId != null
                val deletionWarning = deleteRestoredMemoAfterPost(signer)
                _state.value = PostState(
                    posted = true,
                    postedEventId = event.id,
                    publishResult = publishResult,
                    postWarning = deletionWarning,
                    draftDeleted = hadRestoredMemo && deletionWarning == null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(isPosting = false, error = e.message ?: "ポストに失敗しました")
            }
        }
    }

    fun clearPosted() {
        _state.value = _state.value.copy(
            posted = false,
            postedEventId = null,
            publishResult = null,
            postWarning = null,
            draftDeleted = false,
        )
    }

    private suspend fun deleteRestoredMemoAfterPost(signer: AccountSigner): String? {
        val eventId = editingMemoEventId ?: return null
        val sourcePubkey = editingMemoPubkey ?: signer.pubkey
        if (sourcePubkey != signer.pubkey) {
            return "投稿しましたが、下書きの所有者を確認できないため削除できませんでした"
        }
        return try {
            val deletion = signer.sign(
                content = "",
                kind = 5,
                tags = draftDeletionTags(eventId, sourcePubkey, editingMemoIdentifier),
            )
            NostrRepository.publish(deletion)
            editingMemoEventId = null
            editingMemoPubkey = null
            editingMemoIdentifier = null
            editingMemoUpdatedAt = null
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            "投稿しましたが、下書きを削除できませんでした: ${error.message ?: "不明なエラー"}"
        }
    }

    private fun buildPostContent(
        text: String,
        imageUrls: List<String>,
        quoteReference: String? = null,
    ): String {
        val body = text.trim()
        val attachments = buildList {
            addAll(imageUrls.filter { it.isNotBlank() })
            quoteReference?.takeIf { it.isNotBlank() }?.let(::add)
        }
        if (attachments.isEmpty()) return body
        val attachmentBlock = attachments.joinToString("\n")
        return if (body.isBlank()) attachmentBlock else "$body\n$attachmentBlock"
    }

    private fun memoIdentifier(replyToId: String?): String =
        replyToId?.let { "torinos-reply-memo-$it" } ?: MEMO_IDENTIFIER_POST

    private fun memoEventIdentifier(replyToId: String?, updatedAt: Long): String =
        "${memoIdentifier(replyToId)}-$updatedAt"
}

internal fun draftDeletionTags(
    eventId: String,
    pubkey: String,
    identifier: String?,
): List<List<String>> = buildList {
    add(listOf("e", eventId))
    identifier?.takeIf { it.isNotBlank() }?.let {
        add(listOf("a", "$MEMO_EVENT_KIND:$pubkey:$it"))
    }
    add(listOf("k", MEMO_EVENT_KIND.toString()))
    add(listOf("client", "ToriNos"))
}

internal fun nextMemoUpdatedAt(now: Long, previousUpdatedAt: Long?): Long =
    when {
        previousUpdatedAt == null || previousUpdatedAt < now -> now
        previousUpdatedAt == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> previousUpdatedAt + 1
    }

internal fun imetaTagsForAttachments(
    content: String,
    attachments: List<ImageAttachment>,
): List<List<String>> = attachments
    .mapNotNull { attachment ->
        val url = attachment.uploadedUrl ?: return@mapNotNull null
        if (!content.contains(url)) return@mapNotNull null
        attachment.mediaMetadata
            ?.takeIf { it.url == url }
            ?.toImetaTag()
            ?.takeIf { it.size > 2 }
    }
    .distinctBy { tag -> tag.firstOrNull { it.startsWith("url ") } }
