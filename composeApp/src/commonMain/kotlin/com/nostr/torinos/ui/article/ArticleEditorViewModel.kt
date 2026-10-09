package com.nostr.torinos.ui.article

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.article.*
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.EventByIdFetcher
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.ui.SafeViewModel
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.text.TextRange

data class PublishedArticle(val pubkey: String, val identifier: String)
data class EditingArticle(val pubkey: String, val identifier: String, val publishedAt: Long?)
enum class ArticleSaveState { Unsaved, Saving, Saved, Failed }
enum class PastedPostStatus { Loading, Ready, AlreadyAdded, Unsupported, NotFound }
data class PastedPost(val reference: MatometrReference, val status: PastedPostStatus)
/** The "add posts" sheet; posts are only added once fetched and confirmed to be notes or comments. */
data class MatometrAddState(
    val input: String = "",
    val posts: List<PastedPost> = emptyList(),
    val unsupported: Int = 0,
    val error: String? = null,
) {
    val ready get() = posts.filter { it.status == PastedPostStatus.Ready }
}
data class ArticleEditorState(
    val title: String = "", val summary: String = "", val content: String = "",
    val coverImageUrl: String = "", val topicsInput: String = "",
    val initialTitle: String = "", val initialSummary: String = "", val initialContent: String = "",
    val initialCoverImageUrl: String = "", val initialTopicsInput: String = "",
    val initialMatometrTagEnabled: Boolean = true,
    val editingArticle: EditingArticle? = null,
    val draft: ArticleDraft? = null,
    val selection: TextRange = TextRange.Zero,
    val saveState: ArticleSaveState = ArticleSaveState.Unsaved,
    val savedAt: Long? = null,
    val isLoadingArticle: Boolean = false,
    val isUploadingCover: Boolean = false, val isUploadingBodyImage: Boolean = false,
    val isPublishing: Boolean = false, val frozen: Boolean = false,
    val error: String? = null, val publishedArticle: PublishedArticle? = null,
    val publishAttempt: ArticlePublishAttempt? = null,
    val quotedEvents: Map<String, NostrEvent> = emptyMap(),
    val quotedProfiles: Map<String, NostrProfile> = emptyMap(),
    val loadingQuoteIds: Set<String> = emptySet(),
    val updateConflict: Boolean = false,
    val addSheet: MatometrAddState? = null,
    /** 公開後に kind:1 のポストでも共有するか。[shareComment]は任意のひとこと。 */
    val shareAsNote: Boolean = false,
    val shareComment: String = "",
    /** 共有を済ませた公開版。同じ版の再送や再試行で二重に共有しない。 */
    val sharedAttemptId: String? = null,
    val shareError: String? = null,
) {
    val isEditing get() = editingArticle != null
    val hasUnsavedChanges: Boolean get() = if (isEditing) title != initialTitle || summary != initialSummary ||
        content != initialContent || coverImageUrl != initialCoverImageUrl || topicsInput != initialTopicsInput ||
        (draft?.matometrTagEnabled ?: true) != initialMatometrTagEnabled
        else title.isNotBlank() || summary.isNotBlank() || content.isNotBlank() || coverImageUrl.isNotBlank() || topicsInput.isNotBlank()
    val serializedContent get() = draft?.content ?: content
    val publishDisabledReason: String? get() = when {
        isLoadingArticle -> "記事を読み込んでいます"
        isUploadingCover || isUploadingBodyImage -> "画像をアップロードしています"
        isPublishing -> "受理応答を確認しています"
        frozen -> "アカウント切替前に保存しています"
        updateConflict -> "更新競合を解決してください"
        draft != null -> validateArticleDraft(draft)
        title.isBlank() -> "タイトルを入力してください"
        content.isBlank() -> "本文を入力してください"
        else -> null
    }
    val canPublish get() = publishDisabledReason == null
}

class ArticleEditorViewModel(
    private val editPubkey: String? = null,
    private val editIdentifier: String? = null,
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = null,
    private val draftId: String? = null,
    private val saveIdleMillis: Long = ARTICLE_SAVE_IDLE_MILLIS,
    private val saveMaxIntervalMillis: Long = ARTICLE_SAVE_MAX_INTERVAL_MILLIS,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleEditorState(isLoadingArticle = true))
    val state = _state.asStateFlow()
    private var testStore: ArticleDraftStore? = null
    internal constructor(accountSession: AccountSession, store: ArticleDraftStore, draftId: String? = null,
        saveIdleMillis: Long = ARTICLE_SAVE_IDLE_MILLIS, saveMaxIntervalMillis: Long = ARTICLE_SAVE_MAX_INTERVAL_MILLIS) :
        this(accountSession = accountSession, draftId = draftId, saveIdleMillis = saveIdleMillis,
            saveMaxIntervalMillis = saveMaxIntervalMillis) { testStore = store }
    private val store get() = testStore ?: accountSession?.articleDraftStore ?: error("ログインしてください")
    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private var referenceJob: Job? = null
    private var referenceGeneration = 0L
    private var expectedRevision: Long? = null
    private var editGeneration = 0L
    private var savedGeneration = -1L
    private var baseline: ArticleDraft? = null
    // Each relay save signs and encrypts a whole draft, so typing waits for a pause and only an edit unlike the saved one is sent.
    private var lastSaved: ArticleDraft? = null
    private var unsavedSince: TimeMark? = null
    // Set once the user discards; closing the screen afterwards (ON_STOP) must not save the discarded input.
    private var discarded = false
    private var unregister: (() -> Unit)? = null
    fun attachInputGate() {
        if (unregister != null) return
        unregister = accountSession?.registerInput("article-editor-${articleLocalId()}") {
        state.first { !it.isLoadingArticle && !it.isUploadingBodyImage && !it.isUploadingCover && !it.isPublishing }
        flush()
        check(state.value.draft != null) { state.value.error ?: "下書きを読み込めません" }
    }
    }
    fun detachInputGate() { unregister?.invoke(); unregister = null }
    init {
        attachInputGate()
        launch {
            accountSession?.inputFrozen?.collect { frozen -> _state.update { it.copy(frozen = frozen) } }
        }
        launch {
            try {
                val session = accountSession ?: error("記事の作成にはログインしてください")
                var draft = if (draftId != null) store.loadDraft(session.pubkey, draftId)
                    ?: error("下書きが見つかりません") else ArticleDraft(ownerPubkey = session.pubkey)
                if (draftId == null && editPubkey != null && editIdentifier != null) {
                    check(editPubkey == session.pubkey) { "この記事を公開したアカウントでログインしてください" }
                    val article = fetchLatestArticleByAddress(editPubkey, editIdentifier, relayUrl)
                        ?: error("編集する記事の最新版を確認できません")
                    val data = store.read(session.pubkey)
                    val archived = data.archives[editIdentifier]?.let { data.attempts[it] }
                    val restore = archived?.takeIf { it.event.id == article.event.id && it.draft.content == article.event.content &&
                        buildArticleTags(editIdentifier, it.draft.title, it.draft.summary, it.draft.coverImageUrl, it.draft.topicsInput,
                            it.event.tags.firstOrNull { tag -> tag.firstOrNull() == "published_at" }?.getOrNull(1)?.toLongOrNull() ?: 0,
                            it.draft.hasMatometrTag) == article.event.tags }
                    draft = restore?.draft?.copy(draftId = articleLocalId(), relayDraftIdentifier = null, revision = 0, baseEventId = article.event.id, originalPublishedAt = article.meta.publishedAt)
                        ?: draft.copy(articleIdentifier = editIdentifier, baseEventId = article.event.id,
                            originalPublishedAt = article.meta.publishedAt, title = article.meta.title.orEmpty(),
                            summary = article.meta.summary.orEmpty(), bodyMarkdown = article.event.content,
                            coverImageUrl = article.meta.imageUrl.orEmpty(), topicsInput = article.meta.topics.joinToString(" "),
                            mode = if ("matometr" in article.meta.topics) ArticleDraftMode.MatometrMarkdown else ArticleDraftMode.Regular)
                }
                expectedRevision = draft.revision.takeIf { it > 0 }
                baseline = draft
                applyDraft(draft)
                if (draft.revision > 0) { savedGeneration = editGeneration; lastSaved = draft.saveComparable() }
                val data = store.read(session.pubkey)
                _state.update { it.copy(publishAttempt = data.attempts.values.filter { a -> a.draft.draftId == draft.draftId && a.supersededBy == null }.maxByOrNull { a -> a.event.createdAt }) }
                if (draft.baseEventId != null) checkLatest(draft)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(isLoadingArticle = false, error = e.message) } }
        }
    }
    private fun applyDraft(draft: ArticleDraft) {
        _state.update { it.copy(title = draft.title, summary = draft.summary, content = draft.bodyMarkdown,
            coverImageUrl = draft.coverImageUrl, topicsInput = draft.topicsInput, draft = draft,
            initialTitle = draft.title, initialSummary = draft.summary, initialContent = draft.bodyMarkdown,
            initialCoverImageUrl = draft.coverImageUrl, initialTopicsInput = draft.topicsInput,
            initialMatometrTagEnabled = draft.matometrTagEnabled,
            editingArticle = draft.baseEventId?.let { EditingArticle(draft.ownerPubkey, draft.articleIdentifier, draft.originalPublishedAt) },
            isLoadingArticle = false, saveState = if (draft.revision > 0) ArticleSaveState.Saved else ArticleSaveState.Unsaved) }
    }
    private fun change(transform: (ArticleDraft) -> ArticleDraft) {
        val s = _state.value
        if (accountSession?.inputFrozen?.value == true || s.frozen || s.isLoadingArticle || s.isPublishing || s.draft == null) return
        // Compare before stamping updatedAt, or a selection-only change still schedules a relay save.
        val transformed = transform(s.draft)
        if (transformed == s.draft) return
        val d = transformed.copy(updatedAt = Clock.System.now().epochSeconds)
        editGeneration++
        _state.value = s.copy(draft = d, title = d.title, summary = d.summary, content = d.bodyMarkdown,
            topicsInput = d.topicsInput, coverImageUrl = d.coverImageUrl, saveState = ArticleSaveState.Unsaved, error = null)
        scheduleSave()
    }
    private fun scheduleSave() {
        saveJob?.cancel()
        val since = unsavedSince ?: TimeSource.Monotonic.markNow().also { unsavedSince = it }
        val wait = nextArticleSaveDelay(since.elapsedNow().inWholeMilliseconds, saveIdleMillis, saveMaxIntervalMillis)
        // Only the wait is cancellable by further typing; a save already sending to relays runs to completion.
        saveJob = launch { delay(wait); startSave() }
    }
    private fun startSave() = launch { runSave() }
    private suspend fun runSave() { try { flush() } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    suspend fun flush(): ArticleDraft? = saveMutex.withLock {
        val draft = _state.value.draft ?: return@withLock null
        if (discarded) return@withLock null
        if (savedGeneration == editGeneration && expectedRevision != null && draft.hasContent) return@withLock draft
        val generation = editGeneration
        if (expectedRevision != null && draft.hasContent && draft.saveComparable() == lastSaved) {
            savedGeneration = generation
            unsavedSince = null
            _state.update { it.copy(saveState = ArticleSaveState.Saved) }
            return@withLock draft
        }
        _state.update { it.copy(saveState = ArticleSaveState.Saving, error = null) }
        try {
            if (!draft.hasContent && draft.mode == ArticleDraftMode.Regular) {
                if (expectedRevision != null) store.delete(draft.ownerPubkey, draft.draftId, expectedRevision!!)
                expectedRevision = null
                savedGeneration = generation
                lastSaved = null
                if (generation == editGeneration) unsavedSince = null
                _state.update { current -> current.copy(draft = current.draft?.copy(revision = 0),
                    saveState = if (generation == editGeneration) ArticleSaveState.Saved else ArticleSaveState.Unsaved) }
                return@withLock draft.copy(revision = 0)
            }
            val saved = store.save(draft, expectedRevision)
            expectedRevision = saved.revision
            savedGeneration = generation
            lastSaved = saved.saveComparable()
            if (generation == editGeneration) unsavedSince = null
            // The store may fill in the first publication time; take it only when no edit (such as copyAsNew) came in meanwhile.
            _state.update { current -> current.copy(draft = current.draft?.copy(revision = saved.revision,
                    originalPublishedAt = if (generation == editGeneration) saved.originalPublishedAt else current.draft.originalPublishedAt),
                saveState = if (generation == editGeneration) ArticleSaveState.Saved else ArticleSaveState.Unsaved,
                savedAt = saved.updatedAt) }
            saved
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(saveState = ArticleSaveState.Failed, error = e.message) }; throw e }
    }
    fun saveNow() { saveJob?.cancel(); startSave() }
    fun leave(onSaved: () -> Unit) {
        if (_state.value.frozen || _state.value.isPublishing || _state.value.isLoadingArticle) return
        launch {
            try {
                state.first { !it.isUploadingBodyImage && !it.isUploadingCover }
                flush(); onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }
    fun discard(onDone: () -> Unit) {
        if (_state.value.isPublishing || _state.value.frozen || _state.value.isUploadingBodyImage || _state.value.isUploadingCover) return
        discarded = true
        launch { try {
            saveJob?.cancelAndJoin()
            saveMutex.withLock {
                val original = baseline ?: return@withLock
                if (expectedRevision != null) {
                    // A baseline that was never saved (new or freshly opened published article) had no draft to restore.
                    if (original.revision == 0L) store.delete(original.ownerPubkey, original.draftId, expectedRevision!!)
                    else store.save(original, expectedRevision)
                }
            }
            onDone()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { discarded = false; _state.update { it.copy(error = e.message) } } }
    }
    fun onTitleChange(v: String) { if (v.length <= ARTICLE_TITLE_MAX_LENGTH) change { it.copy(title = v) } }
    fun onSummaryChange(v: String) { if (v.length <= ARTICLE_SUMMARY_MAX_LENGTH) change { it.copy(summary = v) } }
    fun onContentChange(v: String) { if (v.length <= ARTICLE_CONTENT_MAX_LENGTH) change { it.copy(bodyMarkdown = v) } }
    fun onCoverImageUrlChange(v: String) { change { it.copy(coverImageUrl = v) } }
    fun onTopicsChange(v: String) { if (v.length <= ARTICLE_TOPICS_INPUT_MAX_LENGTH) change { updateArticleDraftTopics(it, v) } }
    fun onSelectionChange(v: TextRange) { _state.update { it.copy(selection = v) } }
    /** Wraps the selection; with nothing selected, inserts [placeholder] selected so typing replaces it. */
    fun wrapSelection(before: String, after: String, placeholder: String) {
        val edit = wrapMarkdownSelection(state.value.content, state.value.selection, before, after, placeholder)
        applyBodyEdit(edit)
    }
    /** Adds [prefix] at the start of the caret's line, or removes it when the line already has it. */
    fun toggleLinePrefix(prefix: String) {
        applyBodyEdit(toggleMarkdownLinePrefix(state.value.content, state.value.selection, prefix))
    }
    private fun applyBodyEdit(edit: Pair<String, TextRange>) {
        if (edit.first.length > ARTICLE_CONTENT_MAX_LENGTH) return
        onContentChange(edit.first)
        if (state.value.content == edit.first) onSelectionChange(edit.second)
    }
    fun onCommentary(id: String, v: String) { if (v.length <= ARTICLE_CONTENT_MAX_LENGTH) change { d -> d.copy(references = d.references.map { if (it.eventId == id) it.copy(commentaryMarkdown = v) else it }) } }
    fun removeReference(id: String) { change { it.copy(references = it.references.filterNot { ref -> ref.eventId == id }) } }
    fun moveReference(id: String, delta: Int) {
        val index = state.value.draft?.references?.indexOfFirst { it.eventId == id } ?: return
        if (index >= 0) moveReferenceTo(id, index + delta)
    }
    /** Moves the reference to [target] (0-based). Out-of-range targets are ignored, not clamped. */
    fun moveReferenceTo(id: String, target: Int) { change { d ->
        val refs = d.references.toMutableList(); val index = refs.indexOfFirst { it.eventId == id }
        if (index >= 0 && target in refs.indices) refs.add(target, refs.removeAt(index))
        d.copy(references = refs)
    } }
    /** Returns why the body cannot go back to cards, or null once converted. */
    fun useStructured(): String? {
        val draft = state.value.draft ?: return null
        return when (val result = toStructuredMatometr(draft)) {
            is StructuredConversion.Blocked -> result.reason
            is StructuredConversion.Ok -> { change { result.draft }; null }
        }
    }
    fun useFreeMarkdown() { change { it.copy(bodyMarkdown = it.content, references = emptyList(), mode = ArticleDraftMode.MatometrMarkdown) } }
    fun useRegularArticle() { change { it.copy(references = emptyList(), mode = ArticleDraftMode.Regular) } }
    fun addPosts(onSaved: () -> Unit) {
        leave {
            val d = state.value.draft ?: return@leave
            accountSession?.matometrSelection?.start(d.draftId, d.revision, d.title)
            onSaved()
        }
    }
    private var pasteJob: Job? = null
    fun openAddSheet() { if (state.value.draft?.isMatometr == true) _state.update { it.copy(addSheet = MatometrAddState()) } }
    fun closeAddSheet() { pasteJob?.cancel(); _state.update { it.copy(addSheet = null) } }
    fun onPasteInput(text: String) {
        _state.update { s -> s.copy(addSheet = s.addSheet?.copy(input = text, error = null)) }
        pasteJob?.cancel()
        pasteJob = launch { delay(300); resolvePaste(text) }
    }
    fun retryPaste() { val text = state.value.addSheet?.input ?: return; pasteJob?.cancel(); pasteJob = launch { resolvePaste(text) } }
    private fun updatePaste(text: String, transform: (MatometrAddState) -> MatometrAddState) =
        _state.update { s -> s.copy(addSheet = s.addSheet?.takeIf { it.input == text }?.let(transform) ?: s.addSheet) }
    private fun pastedStatus(id: String): PastedPostStatus {
        val s = state.value
        val draft = s.draft ?: return PastedPostStatus.Loading
        val existing = if (draft.mode == ArticleDraftMode.MatometrStructured) draft.references.map { it.eventId }
            else parseArticleMarkdown(draft.bodyMarkdown, emptyList()).sections.flatMap { it.quoteIds }
        if (id in existing) return PastedPostStatus.AlreadyAdded
        val event = s.quotedEvents[id] ?: return PastedPostStatus.Loading
        return if (event.canSelectForMatometr()) PastedPostStatus.Ready else PastedPostStatus.Unsupported
    }
    private suspend fun resolvePaste(text: String) {
        val parsed = parsePastedEventReferences(text)
        val references = parsed.references.take(MATOMETR_MAX_REFERENCES).map { it.toMatometrReference() }
        val cached = ArticleMemoryCache.events(relayUrl, references.map { it.eventId })
        _state.update { it.copy(quotedEvents = it.quotedEvents + cached) }
        fun refresh(final: Boolean) = updatePaste(text) { sheet ->
            sheet.copy(unsupported = parsed.unsupported, posts = references.map { ref ->
                val status = pastedStatus(ref.eventId).let { if (final && it == PastedPostStatus.Loading) PastedPostStatus.NotFound else it }
                // The fetched event names its author, which keeps the nevent we write complete.
                PastedPost(state.value.quotedEvents[ref.eventId]?.let { ref.copy(authorPubkey = it.pubkey) } ?: ref, status)
            })
        }
        refresh(false)
        val onEvent: (NostrEvent) -> Unit = { event ->
            _state.update { it.copy(quotedEvents = it.quotedEvents + (event.id to event)) }
            ArticleMemoryCache.putEvent(relayUrl, event)
            refresh(false)
        }
        val missing = { references.map { it.eventId }.filter { it !in state.value.quotedEvents }.toSet() }
        try {
            if (missing().isNotEmpty()) EventByIdFetcher().fetch(missing(), onEvent)
            val hinted = references.filter { it.eventId in missing() }
            // Hints come from pasted text, so only a few unknown relays are contacted.
            val hints = hinted.flatMap { it.relayHints.filter { url -> url.startsWith("wss://") }.take(2) }.distinct().take(MAX_HINT_RELAYS).toSet()
            if (hints.isNotEmpty()) EventByIdFetcher().fetch(hinted.map { it.eventId }.toSet(), RelayTarget.Explicit(hints), onEvent)
            val pubkeys = references.mapNotNull { state.value.quotedEvents[it.eventId]?.pubkey }.toSet()
            val profiles = ProfileRepository.awaitProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(15 * 60 * 1_000L))
            _state.update { it.copy(quotedProfiles = it.quotedProfiles + profiles) }
        } finally { refresh(true) }
    }
    fun commitAdd() {
        val s = state.value
        val sheet = s.addSheet ?: return
        if (s.frozen || s.isPublishing || sheet.ready.isEmpty()) return
        var failure: String? = null
        change { d -> appendMatometrReferences(d, sheet.ready.map { it.reference }).getOrElse { failure = it.message; d } }
        if (failure != null) _state.update { it.copy(addSheet = it.addSheet?.copy(error = failure)) }
        else { pasteJob?.cancel(); _state.update { it.copy(addSheet = null) } }
    }
    fun uploadCoverImage(bytes: ByteArray, mimeType: String) { upload(bytes, mimeType, true) }
    fun uploadBodyImage(bytes: ByteArray, mimeType: String) { upload(bytes, mimeType, false) }
    private fun upload(bytes: ByteArray, mimeType: String, cover: Boolean) {
        val s = state.value
        if (accountSession?.inputFrozen?.value == true || s.frozen || s.isPublishing || (if (cover) s.isUploadingCover else s.isUploadingBodyImage)) return
        _state.update { if (cover) it.copy(isUploadingCover = true) else it.copy(isUploadingBodyImage = true) }
        launch {
            try {
                val url = ImageUploader.upload(bytes, mimeType, accountSession?.signer).getOrThrow()
                // Started uploads finish before the transition gate flushes; apply even while frozen.
                val current = state.value
                val position = current.selection.min.coerceIn(0, current.content.length)
                val markdown = "\n\n![画像]($url)\n\n"
                val body = current.content.take(position) + markdown + current.content.drop(position)
                check(cover || body.length <= ARTICLE_CONTENT_MAX_LENGTH) { "本文の文字数上限を超えるため画像を追加できません" }
                val draft = current.draft ?: error("下書きがありません")
                val updated = (if (cover) draft.copy(coverImageUrl = url) else draft.copy(bodyMarkdown = body)).copy(updatedAt = Clock.System.now().epochSeconds)
                editGeneration++
                _state.update { it.copy(draft = updated, coverImageUrl = updated.coverImageUrl, content = updated.bodyMarkdown, saveState = ArticleSaveState.Unsaved) }
                scheduleSave()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "画像アップロードに失敗しました: ${e.message}") } }
            finally { _state.update { if (cover) it.copy(isUploadingCover = false) else it.copy(isUploadingBodyImage = false) } }
        }
    }
    private suspend fun checkLatest(draft: ArticleDraft) {
        val latest = fetchLatestArticleByAddress(draft.ownerPubkey, draft.articleIdentifier, relayUrl)
        if (latest != null) store.transaction(draft.ownerPubkey) { data ->
            val old = data.identities[draft.articleIdentifier]
            data.copy(identities = data.identities + (draft.articleIdentifier to ArticleIdentity(draft.articleIdentifier,
                old?.publishedAt ?: latest.meta.publishedAt ?: latest.event.createdAt, maxOf(old?.maxCreatedAt ?: 0, latest.event.createdAt))))
        }
        _state.update { it.copy(updateConflict = latest == null || latest.event.id != draft.baseEventId,
            error = if (latest == null) "記事の最新版を確認できません。下書きは保存できます" else if (latest.event.id != draft.baseEventId) "別の場所で記事が更新されています" else it.error) }
    }
    fun confirmOverwrite() { launch {
        val d = state.value.draft ?: return@launch
        val latest = fetchLatestArticleByAddress(d.ownerPubkey, d.articleIdentifier, relayUrl)
        if (latest == null) { _state.update { it.copy(error = "最新版を確認できません") }; return@launch }
        change { it.copy(baseEventId = latest.event.id, originalPublishedAt = latest.meta.publishedAt) }
        _state.update { it.copy(updateConflict = false) }
    } }
    fun copyAsNew() { change { it.copy(articleIdentifier = "torinos-${articleLocalId()}", baseEventId = null, originalPublishedAt = null) }; _state.update { it.copy(editingArticle = null, updateConflict = false) } }
    fun publish() {
        if (!state.value.canPublish) return
        performPublish(null)
    }
    fun retryPublish() { val attempt = state.value.publishAttempt ?: return; performPublish(attempt.id) }
    fun onShareAsNoteChange(enabled: Boolean) = _state.update { it.copy(shareAsNote = enabled, shareError = null) }
    fun onShareCommentChange(comment: String) = _state.update { it.copy(shareComment = comment) }

    /** 記事は公開できたが共有に失敗したときの再試行。共有できれば公開した記事を開く。 */
    fun retryShare() {
        val attempt = state.value.publishAttempt ?: return
        if (!attempt.accepted || state.value.isPublishing || state.value.frozen) return
        _state.update { it.copy(isPublishing = true, shareError = null) }
        launch {
            try {
                val session = accountSession ?: error("ログインしてください")
                if (shareIfRequested(session, attempt) && attempt.complete && attempt.draftCleanupError == null) {
                    _state.update { it.copy(publishedArticle = PublishedArticle(session.pubkey, attempt.draft.articleIdentifier)) }
                }
            } finally { _state.update { it.copy(isPublishing = false) } }
        }
    }

    /**
     * 共有を選んでいれば、受理された記事を kind:1 のポストで共有する。共有済みか不要なら true。
     * 失敗しても記事の公開は取り消さず、[ArticleEditorState.shareError]に残して再試行できるようにする。
     */
    private suspend fun shareIfRequested(session: AccountSession, attempt: ArticlePublishAttempt): Boolean {
        val current = state.value
        if (!current.shareAsNote || current.sharedAttemptId == attempt.id) return true
        return try {
            val relayHint = attempt.relays.filterValues { it.state == ArticleRelayState.Accepted }.keys.firstOrNull()
            val note = buildArticleShareNote(
                comment = current.shareComment,
                title = attempt.draft.title,
                pubkey = attempt.event.pubkey,
                identifier = attempt.draft.articleIdentifier,
                relayHint = relayHint,
            )
            val event = session.signer.sign(note.content, 1, note.tags)
            val targets = session.relayStore.writableRelayUrlsSnapshot()
            check(targets.isNotEmpty()) { "書き込み可能なリレーがありません" }
            val result = NostrRepository.publishToRelaysWithResult(event, targets)
            check(result.succeededRelays.isNotEmpty()) {
                "すべてのリレーへの送信に失敗しました: " + result.failedRelays.keys.joinToString()
            }
            _state.update { it.copy(sharedAttemptId = attempt.id, shareError = null) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(shareError = "記事は公開しましたが、ポストでの共有に失敗しました: ${e.message}") }
            false
        }
    }
    private fun performPublish(attemptId: String?) {
        if (state.value.isPublishing || state.value.frozen) return
        _state.update { it.copy(isPublishing = true, error = null) }
        launch {
            try {
                val session = accountSession ?: error("ログインしてください")
                val d = flush() ?: error("下書きがありません")
                if (attemptId == null && d.baseEventId != null) {
                    checkLatest(d); check(!state.value.updateConflict) { state.value.error ?: "最新版と競合しています" }
                }
                val publisher = ArticlePublisher(session.pubkey, session.signer, store, session.relayStore::writableRelayUrlsSnapshot)
                val result = if (attemptId == null) publisher.publish(d) else publisher.retry(attemptId)
                if (result.accepted) ArticleMemoryCache.publishArticle(result.event, result.relays.filterValues { it.state == ArticleRelayState.Accepted }.keys.toList())
                // 共有に失敗したら記事を開かずに留まり、共有だけを再試行できるようにする。
                val shared = !result.accepted || shareIfRequested(session, result)
                _state.update { it.copy(publishAttempt = result,
                    publishedArticle = if (shared && result.complete && result.draftCleanupError == null && result.draft.revision == d.revision) PublishedArticle(session.pubkey, result.draft.articleIdentifier) else null,
                    error = when {
                        result.draftCleanupError != null -> "記事は公開済みですが、下書きの削除に失敗しました: ${result.draftCleanupError}"
                        result.complete -> null
                        result.accepted -> "一部のリレーが受理しました。未受理のリレーへ再送できます"
                        result.relays.values.any { r -> r.state == ArticleRelayState.Unknown } -> "公開結果を確認できません。同じ署名済みイベントを再送できます"
                        else -> "公開が拒否されました。設定を確認して再送してください"
                    }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "公開に失敗しました") }
                val session = accountSession
                if (session != null) runCatching { store.read(session.pubkey).attempts.values.filter { it.draft.draftId == state.value.draft?.draftId && it.supersededBy == null }.maxByOrNull { it.event.createdAt } }.getOrNull()?.let { a -> _state.update { it.copy(publishAttempt = a) } }
            } finally { _state.update { it.copy(isPublishing = false) } }
        }
    }
    fun loadPreview() {
        val generation = ++previewGeneration
        previewJob?.cancel()
        val content = state.value.serializedContent
        val ids = parseArticleMarkdown(content, emptyList()).sections.flatMap { it.quoteIds }.distinct()
        previewJob = launch { fetchQuotes(ids) { previewGeneration == generation && state.value.serializedContent == content } }
    }
    fun stopPreview() { ++previewGeneration; previewJob?.cancel() }
    /** Loads the posts shown as cards in the structured editor; the preview keeps its own job so leaving it does not stop these. */
    fun loadReferenceCards() {
        val ids = state.value.draft?.references?.map { it.eventId }.orEmpty()
        val generation = ++referenceGeneration
        referenceJob?.cancel()
        referenceJob = launch { fetchQuotes(ids) { referenceGeneration == generation } }
    }
    private suspend fun fetchQuotes(ids: List<String>, active: () -> Boolean) {
        val cached = ArticleMemoryCache.events(relayUrl, ids) + accountSession?.matometrSelection?.state?.value?.posts.orEmpty()
            .filter { it.event.id in ids }.associate { it.event.id to it.event }
        _state.update { it.copy(quotedEvents = it.quotedEvents + cached) }
        val missing = ids.filterNot { it in state.value.quotedEvents }.toSet()
        _state.update { it.copy(loadingQuoteIds = it.loadingQuoteIds + missing) }
        try {
            if (missing.isNotEmpty()) EventByIdFetcher().fetch(missing) { event ->
                if (active()) _state.update { it.copy(quotedEvents = it.quotedEvents + (event.id to event), loadingQuoteIds = it.loadingQuoteIds - event.id) }
            }
            val pubkeys = ids.mapNotNull { state.value.quotedEvents[it]?.pubkey }.toSet()
            val profiles = ProfileRepository.awaitProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(15 * 60 * 1_000L))
            if (active()) _state.update { it.copy(quotedProfiles = it.quotedProfiles + profiles) }
        } finally { _state.update { it.copy(loadingQuoteIds = it.loadingQuoteIds - missing) } }
    }
    override fun onCleared() { detachInputGate(); super.onCleared() }
}

internal fun wrapMarkdownSelection(content: String, selection: TextRange, before: String, after: String, placeholder: String): Pair<String, TextRange> {
    val a = selection.min.coerceIn(0, content.length); val z = selection.max.coerceIn(a, content.length)
    val inner = if (a == z) placeholder else content.substring(a, z)
    val text = content.take(a) + before + inner + after + content.drop(z)
    return text to TextRange(a + before.length, a + before.length + inner.length)
}

internal fun toggleMarkdownLinePrefix(content: String, selection: TextRange, prefix: String): Pair<String, TextRange> {
    val caret = selection.min.coerceIn(0, content.length)
    val lineStart = content.lastIndexOf('\n', caret - 1) + 1
    val existing = Regex("^#{1,6} ").find(content.substring(lineStart))?.value
    val removed = existing ?: ""
    val added = if (existing == prefix) "" else prefix
    val text = content.take(lineStart) + added + content.drop(lineStart + removed.length)
    val shift = added.length - removed.length
    fun move(i: Int) = if (i < lineStart) i else maxOf(lineStart, i + shift)
    return text to TextRange(move(selection.start.coerceIn(0, content.length)), move(selection.end.coerceIn(0, content.length)))
}

/** Waits for a pause in typing, but never lets an edit stay unsaved longer than [maxIntervalMillis]. */
internal fun nextArticleSaveDelay(elapsedSinceUnsavedMillis: Long, idleMillis: Long, maxIntervalMillis: Long): Long =
    minOf(idleMillis, (maxIntervalMillis - elapsedSinceUnsavedMillis).coerceAtLeast(0))

/** Fields that do not change what a relay save would carry are cleared, so an edit undone before saving is not sent. */
private fun ArticleDraft.saveComparable() = copy(updatedAt = 0, revision = 0)

internal fun buildArticleTags(
    identifier: String,
    title: String,
    summary: String,
    coverImageUrl: String,
    topicsInput: String,
    publishedAt: Long,
    matometr: Boolean = false,
): List<List<String>> = buildList {
    add(listOf("d", identifier))
    add(listOf("title", title.trim()))
    summary.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("summary", it)) }
    coverImageUrl.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("image", it)) }
    add(listOf("published_at", publishedAt.toString()))
    val topics = articlePublicationTopics(topicsInput, matometr)
    topics.forEach { add(listOf("t", it)) }
    add(listOf("client", "ToriNos"))
}

internal fun updateArticleDraftTopics(draft: ArticleDraft, value: String): ArticleDraft {
    val tagEnabled = if (draft.isMatometr) "matometr" in parseArticleTopics(value) else draft.matometrTagEnabled
    return draft.copy(topicsInput = value, matometrTagEnabled = tagEnabled)
}

internal fun articlePublicationTopics(value: String, matometr: Boolean): List<String> =
    (if (matometr) listOf("matometr") + parseArticleTopics(value) else parseArticleTopics(value))
        .distinct()
        .take(ARTICLE_TOPICS_MAX_COUNT)

internal fun parseArticleTopics(value: String, maxCount: Int = ARTICLE_TOPICS_MAX_COUNT): List<String> =
    value
        .split(Regex("""[\s,、]+"""))
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(maxCount)

internal fun containsHtml(value: String): Boolean =
    HTML_TAG_REGEX.containsMatchIn(value)

private val HTML_TAG_REGEX = Regex(
    pattern = """<\s*/?\s*[a-zA-Z][^>]*>""",
)

private const val MAX_HINT_RELAYS = 4
internal const val ARTICLE_SAVE_IDLE_MILLIS = 10_000L
internal const val ARTICLE_SAVE_MAX_INTERVAL_MILLIS = 60_000L
internal const val ARTICLE_TITLE_MAX_LENGTH = 200
internal const val ARTICLE_SUMMARY_MAX_LENGTH = 500
internal const val ARTICLE_TOPICS_INPUT_MAX_LENGTH = 500
internal const val ARTICLE_TOPICS_MAX_COUNT = 20
