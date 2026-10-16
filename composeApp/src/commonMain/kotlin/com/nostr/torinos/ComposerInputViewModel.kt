package com.nostr.torinos

import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.article.*
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.ui.post.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** セッション内の投稿入力操作。画面は案内と遷移だけを担当する。 */
internal class ComposerInputViewModel(
    private val accountSession: AccountSession?,
    private val postViewModel: PostViewModel,
    pendingRequests: PendingComposerRequestHolder,
) : SafeViewModel() {
    val composer = ComposerCoordinator(pendingRequests)
    val replyResolver = ReplyComposerResolver(viewModelScope) {
        !postViewModel.state.value.isFrozen &&
            (accountSession == null || (!accountSession.inputFrozen.value && accountSession.resources.lease.isActive))
    }
    var recoveryInputId by mutableStateOf<String?>(null)
    var recoveryOpening by mutableStateOf<Long?>(null)
    var showPendingInputs by mutableStateOf(false)
    var pendingInputs by mutableStateOf<List<PendingPostInput>>(emptyList())
    var pendingInputBusy by mutableStateOf(false)
    var pendingInputError by mutableStateOf<String?>(null)
    var matometrError by mutableStateOf<String?>(null)
    var navigationDraftId by mutableStateOf<String?>(null)
    private val inputLock = Mutex()
    fun activeRecoveryInputId(): String? = recoveryInputId.takeIf { recoveryOpening == composer.inputGeneration }
    fun snapshot(): com.nostr.torinos.ui.post.PostMemoData? = composer.currentInputSnapshot(
        postViewModel.currentMemoSnapshot(composer.replyTarget, composer.replyNoteContext))
    fun restoreInput(memo: com.nostr.torinos.ui.post.PostMemoData, offerPendingInputs: Boolean = true) {
        val context = memo.channelId?.let { NoteContext.Channel(it) } ?: NoteContext.Timeline
        composer.clearPostContext(clearDraft = true)
        composer.replyNoteContext = context
        composer.replyTarget = memo.restoreReplyTarget(context)
        composer.quoteToId = memo.quoteEventId
        composer.quoteToPubkey = memo.quotePubkey
        composer.quoteToPreview = memo.quotePreview
        memo.selectedRelayUrls?.let { urls -> composer.replyRelayContext = com.nostr.torinos.ui.channel.ComposerRelayContext(urls, emptyList(), null) }
        composer.localDraft = memo
        postViewModel.restoreInputMemo(memo)
        composer.openFullScreen(PostSheetInitialState.KeepCurrent, offerPendingInputs)
    }
    fun offerPendingInputs(opening: Long) {
        if (!composer.shouldOfferPendingInputs(opening)) return
        val session = accountSession ?: return
        launch {
            inputLock.lock()
            try {
                if (session.inputFrozen.value || !composer.shouldOfferPendingInputs(opening)) return@launch
                val inputs = PendingPostInputs(session.articleDraftStore, session.pubkey).list(activeRecoveryInputId())
                if (session.inputFrozen.value || !composer.shouldOfferPendingInputs(opening)) return@launch
                pendingInputs = inputs
                pendingInputError = null
                showPendingInputs = pendingInputs.isNotEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { matometrError = e.message }
            finally { inputLock.unlock() }
        }
    }
    fun resolvePendingInput(input: PendingPostInput, apply: Boolean) {
        val session = accountSession ?: return
        if (pendingInputBusy || session.inputFrozen.value) return
        pendingInputBusy = true
        pendingInputError = null
        val generation = composer.inputGeneration
        val revision = postViewModel.editorRevision
        launch {
            var release: (() -> Unit)? = null
            inputLock.lock()
            try {
                check(!session.inputFrozen.value) { "アカウント切替の保存処理中です" }
                check(generation == composer.inputGeneration && revision == postViewModel.editorRevision) { "入力が変更されています。開き直してください" }
                check(composer.presentation != ComposerPresentation.Hidden) { "投稿画面が閉じられました。開き直してください" }
                val inputs = PendingPostInputs(session.articleDraftStore, session.pubkey)
                if (apply) {
                    val current = postViewModel.state.value
                    check(!current.isPosting && !current.isUploadingAny && !current.hasFailedUpload && !current.isSavingMemo && !current.isLoadingMemo) {
                        "送信・画像アップロード・下書き操作の完了後に適用してください"
                    }
                    release = postViewModel.beginInputReplacement()
                    inputs.apply(input, snapshot(), activeRecoveryInputId()) { memo ->
                        check(generation == composer.inputGeneration) { "投稿画面が変更されました" }
                        check(!session.inputFrozen.value) { "アカウント切替の保存処理中です" }
                        restoreInput(memo, offerPendingInputs = false)
                        recoveryInputId = input.id
                        recoveryOpening = composer.inputGeneration
                    }
                    recoveryInputId = "current"
                } else inputs.discard(input)
                showPendingInputs = false
                pendingInputs = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pendingInputError = e.message ?: "保留入力を処理できませんでした" }
            finally { release?.invoke(); inputLock.unlock(); pendingInputBusy = false }
        }
    }
    fun quoteSelected(newPost: Boolean) {
        val session = accountSession ?: return
        val selection = session.matometrSelection.beginHandoff() ?: return
        val generation = composer.inputGeneration
        val revision = postViewModel.editorRevision
        launch {
            var release: (() -> Unit)? = null
            inputLock.lock()
            try {
                val current = postViewModel.state.value
                check(!current.isPosting && !current.isUploadingAny && !current.hasFailedUpload && !current.isSavingMemo && !current.isLoadingMemo) {
                    "送信・画像アップロード・下書き操作の完了後に引用投稿へ切り替えてください"
                }
                check(generation == composer.inputGeneration && revision == postViewModel.editorRevision) { "入力が変更されています。引用を再試行してください" }
                release = postViewModel.beginInputReplacement()
                val previous = snapshot()
                val base = if (newPost) null else previous
                val next = buildMatometrQuoteMemo(base, selection.posts)
                val data = session.articleDraftStore.transaction(session.pubkey) { data ->
                    if (selection.handoffId in data.handoffs) data else {
                        val pending = if (newPost && previous != null) data.composers + ("pending-${articleLocalId()}" to previous) else data.composers
                        data.copy(composers = (pending - "current") + (selection.handoffId to next), handoffs = data.handoffs + selection.handoffId)
                    }
                }
                session.matometrSelection.markCommitted(selection.handoffId)
                check(!session.inputFrozen.value) { "アカウント切替の保存処理中です。引用投稿を再試行してください" }
                check(generation == composer.inputGeneration) { "投稿画面が変更されました" }
                val committed = data.composers[selection.handoffId] ?: error("引き継ぎ入力が見つかりません")
                if (newPost || previous == null) restoreInput(committed)
                else {
                    postViewModel.restoreInputMemo(committed)
                    composer.quoteToId = committed.quoteEventId
                    composer.quoteToPubkey = committed.quotePubkey
                    composer.quoteToPreview = committed.quotePreview
                    composer.localDraft = committed
                    composer.openFullScreen(PostSheetInitialState.KeepCurrent)
                }
                // Keep the committed input in this account session until the next normal input save.
                recoveryInputId = selection.handoffId
                recoveryOpening = composer.inputGeneration
                session.matometrSelection.finishHandoff()
            } catch (e: CancellationException) {
                session.matometrSelection.failHandoff("処理を中断しました")
                throw e
            } catch (e: Exception) { session.matometrSelection.failHandoff(e.message ?: "引用投稿を開けませんでした") }
            finally { release?.invoke(); inputLock.unlock() }
        }
    }
    fun createMatometr() {
        val session = accountSession ?: return
        val selection = session.matometrSelection.beginHandoff() ?: return
        launch {
            try {
                selection.committedId?.let { id ->
                    navigationDraftId = id
                    session.matometrSelection.finishHandoff()
                    return@launch
                }
                val store = session.articleDraftStore
                val references = selection.posts.map { it.reference }
                val target = selection.targetDraftId
                val committed = if (target == null) {
                    val data = store.read(session.pubkey)
                    data.drafts[selection.handoffId] ?: run {
                        val next = ArticleDraft(draftId = selection.handoffId, ownerPubkey = session.pubkey,
                            mode = ArticleDraftMode.MatometrStructured, references = references)
                        check(next.content.length <= ARTICLE_CONTENT_MAX_LENGTH) { "生成後の本文は10,000文字までです。選択する投稿を減らしてください" }
                        store.save(next, null)
                    }
                } else {
                    val draft = store.read(session.pubkey).drafts[target] ?: error("追加先の下書きが見つかりません")
                    if (draft.revision != selection.targetRevision) throw ArticleStoreConflict()
                    store.save(com.nostr.torinos.article.appendMatometrReferences(draft, references).getOrThrow(), draft.revision)
                }
                session.matometrSelection.markCommitted(committed.draftId)
                selection.posts.forEach { com.nostr.torinos.ui.article.ArticleMemoryCache.putEvent(null, it.event) }
                navigationDraftId = committed.draftId
                session.matometrSelection.finishHandoff()
            } catch (e: CancellationException) {
                session.matometrSelection.failHandoff("処理を中断しました")
                throw e
            } catch (e: Exception) { session.matometrSelection.failHandoff(e.message ?: "記事を作成できませんでした") }
        }
    }

    fun completePostedInput(fromCurrentDraft: Boolean) {
        if (!fromCurrentDraft) return
        val completedId = activeRecoveryInputId() ?: "current"
        recoveryInputId = null
        recoveryOpening = null
        launch {
            inputLock.lock()
            try {
                val session = accountSession ?: return@launch
                session.articleDraftStore.transaction(session.pubkey) { data ->
                    data.copy(composers = data.composers - completedId, handoffs = data.handoffs - completedId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { matometrError = "公開は完了しましたが、復旧用入力の整理に失敗しました: ${error.message}" }
            finally { inputLock.unlock() }
        }
    }

    private val unregister = accountSession?.registerInput("post-composer") { flush() }

    suspend fun flush() {
        val session = accountSession ?: return
        postViewModel.state.first { !it.isPosting && !it.isUploadingAny && !it.isSavingMemo && !it.isLoadingMemo }
        check(!postViewModel.state.value.hasFailedUpload) { "未アップロードの画像があります。再試行または削除してください" }
        session.matometrSelection.state.first { !it.busy }
        inputLock.lock()
        try {
            val memo = snapshot()
            session.articleDraftStore.transaction(session.pubkey) { data ->
                val inputId = activeRecoveryInputId() ?: "current"
                data.copy(composers = if (memo == null) data.composers - inputId else data.composers + (inputId to memo))
            }
        } finally { inputLock.unlock() }
    }

    override fun onCleared() { replyResolver.cancel(); unregister?.invoke(); super.onCleared() }
}
