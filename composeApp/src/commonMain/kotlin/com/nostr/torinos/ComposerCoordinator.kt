package com.nostr.torinos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.model.toReplyTarget
import com.nostr.torinos.ui.channel.ComposerRelayContext
import com.nostr.torinos.ui.post.PostMemoData
import com.nostr.torinos.ui.post.PostSheetInitialState

/** 投稿UIの表示形態。簡易とシートが同時に開く不正状態を作らないため排他的にする。 */
internal enum class ComposerPresentation {
    Hidden,
    FeedInline,
    FullScreen,
}

/**
 * アカウントセッションをまたいで残す、投稿UIの再開要求。
 *
 * 鍵設定が完了するとアカウント状態が Anonymous から Active へ切り替わり、`AppSessionCoordinator` と
 * その `ComposerCoordinator` は作り直される。コーディネーター側に保存した要求は消えるため、
 * セッションより外側の階層でこのホルダーを保持する。
 */
internal class PendingComposerRequestHolder {
    private var newPostRequested = false
    var registeringPubkey by mutableStateOf<String?>(null)
        private set

    fun requestRegistration(pubkey: String?) {
        registeringPubkey = pubkey
    }

    fun consumeRegistration(pubkey: String): Boolean {
        if (registeringPubkey != pubkey) return false
        registeringPubkey = null
        newPostRequested = false
        return true
    }

    fun requestNewPost() {
        newPostRequested = true
    }

    /** 要求があれば true を返して同時に消費する。同じ要求は二度実行されない。 */
    fun consumeNewPost(): Boolean {
        val requested = newPostRequested
        newPostRequested = false
        return requested
    }

    fun clear() {
        registeringPubkey = null
        newPostRequested = false
    }
}

/** 投稿、返信、引用にまたがる一時状態の唯一の所有者。 */
internal class ComposerCoordinator(
    val pendingRequests: PendingComposerRequestHolder = PendingComposerRequestHolder(),
) {
    var presentation by mutableStateOf(ComposerPresentation.Hidden)
        private set
    /** `presentation == FullScreen` のとき、`PostSheet` を開く時点の初期化方法。 */
    var sheetInitialState by mutableStateOf(PostSheetInitialState.Reset)
        private set
    /**
     * 簡易コンポーザーを「…」でフッターメニューへ切り替えた後も、その入力を保持しているか。
     * true の間はフィードの＋で同じ入力を再表示する。
     */
    var hasHeldInlineDraft by mutableStateOf(false)
        private set
    var showRegistrationProfile by mutableStateOf(false)
        private set

    fun resumeAfterKeySetup(pubkey: String, useFooterComposer: Boolean, resetPost: () -> Unit) {
        if (pendingRequests.consumeRegistration(pubkey)) {
            dismissPost()
            showRegistrationProfile = true
        } else if (pendingRequests.consumeNewPost()) {
            openNewPost(useFooterComposer, resetPost)
        }
    }

    fun completeRegistration(preparePost: (String) -> Unit) {
        if (!showRegistrationProfile) return
        showRegistrationProfile = false
        clearPostContext(clearDraft = true)
        preparePost("Nostr、はじめました🐦")
        openFullScreen(PostSheetInitialState.KeepCurrent)
    }

    var showStatusComposer by mutableStateOf(false)
    var replyTarget by mutableStateOf<ReplyTarget?>(null)
    var replyToPreview by mutableStateOf<String?>(null)
    var quoteToId by mutableStateOf<String?>(null)
    var quoteToPubkey by mutableStateOf<String?>(null)
    var quoteToPreview by mutableStateOf<String?>(null)
    var replyNoteContext by mutableStateOf<NoteContext>(NoteContext.Timeline)
    /** チャンネル返信の送信先と relay hint(第16.14節)。通常投稿・タイムライン返信では null。 */
    var replyRelayContext by mutableStateOf<ComposerRelayContext?>(null)
    var localDraft by mutableStateOf<PostMemoData?>(null)
    var journalToggleCalendarRequest by mutableStateOf(0)
    var journalShowCalendarRequest by mutableStateOf(0)
    var showKeySetup by mutableStateOf(false)
    var pendingKeyAction by mutableStateOf<PendingKeyAction?>(null)

    fun prepareQuote(event: NostrEvent) {
        clearPostContext(clearDraft = true)
        quoteToId = event.id
        quoteToPubkey = event.pubkey
        quoteToPreview = event.content.ifBlank { "投稿 ${event.id.take(8)}" }
    }

    fun prepareReply(
        event: NostrEvent,
        preview: String?,
        noteContext: NoteContext,
    ): Boolean {
        val target = event.toReplyTarget(noteContext) ?: return false
        prepareReply(target, preview, noteContext)
        return true
    }

    fun prepareReply(
        target: ReplyTarget,
        preview: String?,
        noteContext: NoteContext,
        relayContext: ComposerRelayContext? = null,
    ) {
        replyRelayContext = relayContext
        quoteToId = null
        quoteToPubkey = null
        quoteToPreview = null
        replyTarget = target
        replyToPreview = preview
        replyNoteContext = noteContext
    }

    /** 返信、引用、下書き復元など、既存導線から全画面の `PostSheet` を開く。 */
    fun openFullScreen(initialState: PostSheetInitialState = PostSheetInitialState.Reset) {
        // 展開（KeepCurrent）ならシートが入力を引き継ぎ、それ以外は新しい入力で始まるので、保持は終える。
        hasHeldInlineDraft = false
        sheetInitialState = initialState
        presentation = ComposerPresentation.FullScreen
    }

    /** 新規投稿のシートを全画面で開く。ジャーナルなど、簡易コンポーザーを持たない画面から使う。 */
    fun openNewPostSheet(initialState: PostSheetInitialState = PostSheetInitialState.Reset) {
        replyTarget = null
        replyToPreview = null
        replyNoteContext = NoteContext.Timeline
        openFullScreen(initialState)
    }

    /**
     * フィードの＋。ローカル下書きがある場合は現行どおりシートで復元する
     * （カスタム絵文字設定へ移動する間に退避した本文を失わないため）。
     * 「…」で保持した入力があれば、それをそのまま簡易コンポーザーに戻す。
     * どちらもない場合は投稿状態を空にして簡易コンポーザーを開く。
     */
    fun openNewPost(useFooterComposer: Boolean = true, resetPost: () -> Unit) {
        // フッター投稿を使わない設定では、従来どおり全画面のシートを開く。
        if (!useFooterComposer) {
            openNewPostSheet()
            return
        }
        if (localDraft != null) {
            openNewPostSheet(PostSheetInitialState.RestoreMemo)
            return
        }
        if (hasHeldInlineDraft) {
            hasHeldInlineDraft = false
            presentation = ComposerPresentation.FeedInline
            return
        }
        clearPostContext(clearDraft = true)
        resetPost()
        sheetInitialState = PostSheetInitialState.Reset
        presentation = ComposerPresentation.FeedInline
    }

    /** 簡易コンポーザーの△。本文を保ったまま `PostSheet` を開く。 */
    fun expandInline() {
        if (presentation != ComposerPresentation.FeedInline) return
        openFullScreen(PostSheetInitialState.KeepCurrent)
    }

    /**
     * FABの「…」とBack。簡易コンポーザーをフッターメニューへ切り替え、入力は保持する。
     * 送信中でも切り替えられる。完了は `ComposerHost` が処理し、成功すれば保持も終わる。
     */
    fun switchInlineToMenu() {
        if (presentation != ComposerPresentation.FeedInline) return
        hasHeldInlineDraft = true
        presentation = ComposerPresentation.Hidden
    }

    /**
     * 簡易コンポーザー、または「…」で保持中の入力を破棄する（フィード以外への遷移）。
     * 送信中は `reset()` しない。送信結果を失わず、その後の入力が古い結果で上書きされないよう、
     * 送信の完了側が世代で扱う。
     */
    fun closeInline(isPosting: Boolean, resetPost: () -> Unit) {
        if (presentation != ComposerPresentation.FeedInline && !hasHeldInlineDraft) return
        hasHeldInlineDraft = false
        clearPostContext(clearDraft = true)
        if (presentation == ComposerPresentation.FeedInline) presentation = ComposerPresentation.Hidden
        if (!isPosting) resetPost()
    }

    /** 表示だけを閉じる。ローカル下書きと返信コンテキストには触れない。 */
    fun hide() {
        presentation = ComposerPresentation.Hidden
    }

    fun dismissPost() {
        hasHeldInlineDraft = false
        clearPostContext(clearDraft = true)
        presentation = ComposerPresentation.Hidden
    }

    fun dismissKeySetup() {
        pendingKeyAction = null
        pendingRequests.clear()
        clearPostContext(clearDraft = false)
        showKeySetup = false
    }

    fun clearPostContext(clearDraft: Boolean) {
        if (clearDraft) localDraft = null
        replyTarget = null
        replyToPreview = null
        quoteToId = null
        quoteToPubkey = null
        quoteToPreview = null
        replyNoteContext = NoteContext.Timeline
        replyRelayContext = null
    }
}

internal class ReplyResolutionTracker {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun isCurrent(request: Long): Boolean = request == generation
}
