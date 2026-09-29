package com.nostr.torinos.ui.post

import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.MediaMetadata
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.emoji.customEmojiTagsForContent
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

/**
 * 投稿の完了結果。送信時に `post()` が実際に受け取った返信先と [NoteContext] を確定値として持つので、
 * ホストは `PostSheet` のローカル状態（下書き一覧から選んだ返信先など）を推測せずに遷移先を決められる。
 */
data class PostCompletion(
    val eventId: String,
    val replyToId: String?,
    val noteContext: NoteContext,
    val publishResult: RelayPublishResult,
    val warning: String?,
    /**
     * 送信を始めた入力状態が、完了時点でも現在の入力状態であるとき true。
     * 送信中に `reset()` された場合（簡易コンポーザーを閉じた等）は false で、
     * ホストは投稿UIを閉じず、通知と遷移だけを行う。
     */
    val fromCurrentDraft: Boolean = true,
)

data class PostState(
    val text: String = "",
    val customEmojis: List<CustomEmoji> = emptyList(),
    val hasContentWarning: Boolean = false,
    val isPosting: Boolean = false,
    val isSavingMemo: Boolean = false,
    val isLoadingMemo: Boolean = false,
    val images: List<ImageAttachment> = emptyList(),
    val error: String? = null,
    val memoMessage: String? = null,
    /** 未消費の投稿完了。ホストが `consumeCompletion()` で一度だけ取り出す。 */
    val completion: PostCompletion? = null,
    /** 送信中に入力状態が破棄された後で失敗した場合の通知。現在の入力欄のエラーとは別に扱う。 */
    val staleFailure: String? = null,
) {
    val isUploadingAny: Boolean get() = images.any { it.isUploading }
    val hasFailedUpload: Boolean get() = images.any { !it.isUploading && it.uploadedUrl == null }
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
    val hasContentWarning: Boolean = false,
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
    val replyRootAddress: String? = null,
    val replyParentAddress: String? = null,
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
    val replyRootAddress: String? = null,
    val replyParentAddress: String? = null,
    val hasContentWarning: Boolean = false,
)

internal fun PostMemoPayload.toPostMemoData(
    identifier: String? = null,
    sourceEventId: String? = null,
    sourcePubkey: String? = null,
): PostMemoData =
    PostMemoData(
        text = text,
        customEmojis = customEmojis,
        hasContentWarning = hasContentWarning,
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
        replyRootAddress = replyRootAddress,
        replyParentAddress = replyParentAddress,
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
        address = replyParentAddress,
    )
    return when (noteContext) {
        // チャンネル返信では replyRootRelayUrl にチャンネルの relay hint を保存している。
        is NoteContext.Channel -> ReplyTarget.Channel(noteContext.channelId, parent.copy(kind = 42), replyRootRelayUrl)
        NoteContext.Timeline -> {
            val root = if (replyRootId != null && replyRootKind != null && replyRootPubkey != null) {
                ReplyEventReference(
                    replyRootId,
                    replyRootKind,
                    replyRootPubkey,
                    replyRootRelayUrl,
                    replyRootPubkeyRelayUrl,
                    replyRootAddress,
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
    private var draftGeneration = 0
    private var editingMemoIdentifier: String? = null
    private var editingMemoUpdatedAt: Long? = null
    private var editingMemoEventId: String? = null
    private var editingMemoPubkey: String? = null

    fun reset() {
        draftGeneration++
        editingMemoIdentifier = null
        editingMemoUpdatedAt = null
        editingMemoEventId = null
        editingMemoPubkey = null
        // 未消費の完了・失敗通知は、入力状態の破棄で失わない。
        _state.value = PostState(
            completion = _state.value.completion,
            staleFailure = _state.value.staleFailure,
        )
    }

    fun onTextChange(text: String) = updateText(text)

    fun onCustomEmojiInserted(text: String, emoji: CustomEmoji) = updateText(text, emoji)

    fun onContentWarningChange(enabled: Boolean) {
        _state.update {
            it.copy(
                hasContentWarning = enabled,
                error = null,
                memoMessage = null,
            )
        }
    }

    private fun registeredEmojis(): List<CustomEmoji> =
        accountSession?.customEmojis?.preferences?.value?.available.orEmpty()

    /** 送信時点で登録済みのセットに含まれていれば、そのアドレスを NIP-30 の4要素目に付ける。 */
    internal fun setAddressOf(emoji: CustomEmoji): EmojiSetAddress? =
        accountSession?.customEmojis?.preferences?.value?.setAddressOf(emoji)

    private fun updateText(text: String, selectedEmoji: CustomEmoji? = null) {
        _state.value = _state.value.copy(
            text = text,
            customEmojis = resolveDraftEmojis(
                text,
                _state.value.customEmojis + listOfNotNull(selectedEmoji),
                registeredEmojis(),
            ),
            error = null,
            memoMessage = null,
        )
    }

    fun uploadAndAppendImage(
        bytes: ByteArray,
        mimeType: String,
        previewBytes: ByteArray = bytes,
    ) {
        if (_state.value.images.size >= MAX_IMAGES) return
        val id = nextImageId++
        val generation = draftGeneration
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
                    if (generation != draftGeneration) return@onSuccess
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
                    if (generation != draftGeneration) return@onFailure
                    _state.update { s ->
                        if (s.images.none { it.id == id }) s else s.copy(
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

    /** 呼び出し側の事前検証エラーを、送信失敗と同じ場所に表示する。 */
    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun restoreMemo(memo: PostMemoData, message: String? = null) {
        draftGeneration++
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
            customEmojis = resolveDraftEmojis(memo.text, memo.customEmojis, registeredEmojis()),
            hasContentWarning = memo.hasContentWarning,
            images = restoredImages,
            memoMessage = message,
            completion = _state.value.completion,
            staleFailure = _state.value.staleFailure,
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
            hasContentWarning = current.hasContentWarning,
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
            replyRootRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.relayUrl
                ?: (replyTarget as? ReplyTarget.Channel)?.channelRelayUrl,
            replyRootPubkeyRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.pubkeyRelayUrl,
            replyParentRelayUrl = replyTarget?.parent?.relayUrl,
            replyParentPubkeyRelayUrl = replyTarget?.parent?.pubkeyRelayUrl,
            replyRootAddress = (replyTarget as? ReplyTarget.Timeline)?.root?.address,
            replyParentAddress = replyTarget?.parent?.address,
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
                hasContentWarning = current.hasContentWarning,
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
                replyRootRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.relayUrl
                ?: (replyTarget as? ReplyTarget.Channel)?.channelRelayUrl,
                replyRootPubkeyRelayUrl = (replyTarget as? ReplyTarget.Timeline)?.root?.pubkeyRelayUrl,
                replyParentRelayUrl = replyTarget?.parent?.relayUrl,
                replyParentPubkeyRelayUrl = replyTarget?.parent?.pubkeyRelayUrl,
                replyRootAddress = (replyTarget as? ReplyTarget.Timeline)?.root?.address,
                replyParentAddress = replyTarget?.parent?.address,
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
        if (current.isPosting) return
        // 入力欄が上限を守るので通常は到達しない。導線ごとの差を残さないための最後の防御。
        if (current.text.length > MAX_POST_CHARS) {
            _state.value = current.copy(error = "本文は${MAX_POST_CHARS}文字以内にしてください")
            return
        }
        val composed = composeNoteContent(
            text = current.text,
            images = current.images,
            customEmojis = current.customEmojis,
            quoteReference = quoteReference,
            setAddressOf = ::setAddressOf,
        )
            ?: return
        val text = composed.content
        val targetRelayUrls = relayUrls
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
        if (targetRelayUrls != null && targetRelayUrls.isEmpty()) {
            _state.value = current.copy(error = "送信先リレーを1つ以上選択してください")
            return
        }

        // 送信中に reset()/restoreMemo() されたら、結果を新しい入力状態へ書き込まない。
        val generation = draftGeneration
        val restoredMemo = EditingMemoRef(editingMemoEventId, editingMemoPubkey, editingMemoIdentifier)
        _state.value = _state.value.copy(isPosting = true, error = null)
        launch {
            fun failPost(message: String) {
                val isCurrent = generation == draftGeneration
                _state.update { reducePostFailure(it, isCurrent, message) }
            }

            val signer = accountSession?.signer ?: run {
                failPost("秘密鍵が設定されていません")
                return@launch
            }

            val tags = buildList {
                addAll(replyTarget?.tags().orEmpty())
                addAll(composed.tags)
                addAll(contentWarningTags(current.hasContentWarning))
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
                val deletionWarning = deleteRestoredMemoAfterPost(signer, restoredMemo)
                val isCurrent = generation == draftGeneration
                val completion = PostCompletion(
                    eventId = event.id,
                    replyToId = replyTarget?.parent?.id,
                    noteContext = noteContext,
                    publishResult = publishResult,
                    warning = deletionWarning,
                    fromCurrentDraft = isCurrent,
                )
                if (isCurrent && deletionWarning == null) clearEditingMemo()
                _state.update { reducePostSuccess(it, isCurrent, completion) }
            }.onFailure { e ->
                failPost(e.message ?: "ポストに失敗しました")
            }
        }
    }

    /** 未消費の投稿完了を一度だけ取り出す。 */
    fun consumeCompletion(): PostCompletion? {
        val completion = _state.value.completion ?: return null
        _state.update { it.copy(completion = null) }
        return completion
    }

    /** 送信中に入力状態が破棄された後の失敗通知を一度だけ取り出す。 */
    fun consumeStaleFailure(): String? {
        val message = _state.value.staleFailure ?: return null
        _state.update { it.copy(staleFailure = null) }
        return message
    }

    private fun clearEditingMemo() {
        editingMemoEventId = null
        editingMemoPubkey = null
        editingMemoIdentifier = null
        editingMemoUpdatedAt = null
    }

    /** 送信開始時点で編集中だった下書きの参照。送信中に別の下書きへ切り替わっても取り違えない。 */
    private class EditingMemoRef(
        val eventId: String?,
        val pubkey: String?,
        val identifier: String?,
    )

    private suspend fun deleteRestoredMemoAfterPost(signer: AccountSigner, memo: EditingMemoRef): String? {
        val eventId = memo.eventId ?: return null
        val sourcePubkey = memo.pubkey ?: signer.pubkey
        if (sourcePubkey != signer.pubkey) {
            return "投稿しましたが、下書きの所有者を確認できないため削除できませんでした"
        }
        return try {
            val deletion = signer.sign(
                content = "",
                kind = 5,
                tags = draftDeletionTags(eventId, sourcePubkey, memo.identifier),
            )
            NostrRepository.publish(deletion)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            "投稿しましたが、下書きを削除できませんでした: ${error.message ?: "不明なエラー"}"
        }
    }

    private fun memoIdentifier(replyToId: String?): String =
        replyToId?.let { "torinos-reply-memo-$it" } ?: MEMO_IDENTIFIER_POST

    private fun memoEventIdentifier(replyToId: String?, updatedAt: Long): String =
        "${memoIdentifier(replyToId)}-$updatedAt"
}

/**
 * 送信成功時の状態。送信を始めた入力状態が現在も有効（[isCurrent]）なら入力を空にして完了だけを残す。
 * 送信中に `reset()` された場合は、いま入力中の状態を上書きせず完了だけを追加する。
 * どちらの場合も、未消費の別送信の失敗通知は失わない。
 */
internal fun reducePostSuccess(state: PostState, isCurrent: Boolean, completion: PostCompletion): PostState =
    if (isCurrent) {
        PostState(completion = completion, staleFailure = state.staleFailure)
    } else {
        state.copy(completion = completion)
    }

/**
 * 送信失敗時の状態。現在の入力状態なら本文を残してエラーを出す。
 * 送信中に破棄された入力状態の失敗は、いまの入力欄ではなく別枠の通知として残す。
 */
internal fun reducePostFailure(state: PostState, isCurrent: Boolean, message: String): PostState =
    if (isCurrent) state.copy(isPosting = false, error = message) else state.copy(staleFailure = message)

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

internal fun contentWarningTags(enabled: Boolean): List<List<String>> =
    if (enabled) listOf(listOf("content-warning")) else emptyList()

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

/** 本文と、本文から導かれるタグ(カスタム絵文字・引用・imeta)。返信タグと client タグは呼び出し側が付ける。 */
internal data class ComposedNote(val content: String, val tags: List<List<String>>)

/**
 * 投稿画面の入力から kind 1 / kind 42 共通の本文とタグを組み立てる。フィード投稿とチャンネル作成の
 * 最初の投稿(FR-13)で同じ規則を使うため、ViewModel から切り出している。本文が空なら null。
 */
internal fun composeNoteContent(
    text: String,
    images: List<ImageAttachment>,
    customEmojis: List<CustomEmoji>,
    quoteReference: String? = null,
    setAddressOf: (CustomEmoji) -> EmojiSetAddress? = { null },
): ComposedNote? {
    val body = text.trim()
    val attachments = buildList {
        addAll(images.mapNotNull { it.uploadedUrl }.filter { it.isNotBlank() })
        quoteReference?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val content = when {
        attachments.isEmpty() -> body
        body.isBlank() -> attachments.joinToString("\n")
        else -> "$body\n${attachments.joinToString("\n")}"
    }
    if (content.isBlank()) return null
    val tags = buildList {
        addAll(customEmojiTagsForContent(content, customEmojis, setAddressOf))
        extractNostrEventReferences(content).forEach { reference ->
            add(
                buildList {
                    add("q")
                    add(reference.eventId)
                    reference.relayUrls.firstOrNull()?.let { add(it) }
                },
            )
            reference.authorPubkey?.let { add(listOf("p", it)) }
        }
        addAll(imetaTagsForAttachments(content, images))
    }
    return ComposedNote(content, tags)
}
