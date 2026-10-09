package com.nostr.torinos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.nostr.torinos.crypto.isWebPlatform
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.ui.profile.EditProfileViewModel
import com.nostr.torinos.ui.profile.RegistrationProfileDialog
import com.nostr.torinos.ui.post.PostCompletion
import com.nostr.torinos.ui.post.PostSheet
import com.nostr.torinos.ui.post.PostViewModel
import com.nostr.torinos.ui.setup.KeySetupScreen

/**
 * ComposerCoordinator が所有する一時状態を、モーダル UI へ接続する。
 *
 * 簡易コンポーザーと `PostSheet` は同じ [postViewModel] を使う。投稿完了の監視はどちらのUIにも
 * 属さない場所であるここに置き、どの導線から投稿しても `onPosted` が一度だけ呼ばれるようにする。
 */
@Composable
internal fun ComposerHost(
    coordinator: ComposerCoordinator,
    postViewModel: PostViewModel,
    onDraftSaved: () -> Unit,
    onPostSheetOpened: (Long) -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
    onPosted: (PostCompletion) -> Unit,
    /** 送信中に入力状態が破棄された後で失敗したときの通知。 */
    onBackgroundPostFailed: (String) -> Unit,
) {
    val postState by postViewModel.state.collectAsState()

    LaunchedEffect(postState.completion) {
        if (postState.completion == null) return@LaunchedEffect
        // 取り出しと同時にnullへ戻すので、再コンポーズやUIの切り替えで二重に処理されない。
        val completion = postViewModel.consumeCompletion() ?: return@LaunchedEffect
        // 送信中に入力状態が破棄されていた場合、今開いている投稿UIは別の入力なので閉じない。
        if (completion.fromCurrentDraft) coordinator.dismissPost()
        onPosted(completion)
    }

    LaunchedEffect(postState.staleFailure) {
        if (postState.staleFailure == null) return@LaunchedEffect
        postViewModel.consumeStaleFailure()?.let(onBackgroundPostFailed)
    }

    if (coordinator.presentation == ComposerPresentation.FullScreen) {
        val opening = coordinator.openingGeneration
        // A restored input must also recreate sheet-local reply, relay, and draft selection state.
        key(opening) {
            PostSheet(
                onDismiss = coordinator::dismissPost,
                onOpened = {
                    coordinator.markInputInitialized()
                    onPostSheetOpened(opening)
                },
                onDraftSaved = onDraftSaved,
                replyTarget = coordinator.replyTarget,
                replyToPreview = coordinator.replyToPreview,
                quoteToId = coordinator.quoteToId,
                quoteToPubkey = coordinator.quoteToPubkey,
                quoteToPreview = coordinator.quoteToPreview,
                quoteArticle = coordinator.quoteToArticle,
                noteContext = coordinator.replyNoteContext,
                relayContext = coordinator.replyRelayContext,
                initialMemo = coordinator.localDraft,
                initialMemoRestoreMessage = if (coordinator.localDraft != null) {
                    "下書きを復元しました"
                } else {
                    null
                },
                initialState = coordinator.sheetInitialState,
                // iOS Safari はタップ中の focus でしかキーボードを出さないため、Web では自動フォーカスせずタップに任せる。
                autoFocus = !isWebPlatform &&
                    coordinator.replyTarget == null &&
                    coordinator.quoteToId == null &&
                    coordinator.quoteToArticle == null &&
                    coordinator.localDraft?.quotes.isNullOrEmpty(),
                preserveLocalDraftOnNavigation = true,
                onOpenCustomEmojiSettings = { draft ->
                    coordinator.localDraft = draft
                    coordinator.clearPostContext(clearDraft = false)
                    coordinator.hide()
                    onOpenCustomEmojiSettings()
                },
                viewModel = postViewModel,
            )
        }
    }

    if (coordinator.showRegistrationProfile) {
        val profileViewModel = accountSessionViewModel<EditProfileViewModel>(
            key = "registration-profile",
        ) { session -> EditProfileViewModel(session, loadExistingProfile = false) }
        RegistrationProfileDialog(
            viewModel = profileViewModel,
            onComplete = {
                coordinator.completeRegistration { text ->
                    postViewModel.reset()
                    postViewModel.onTextChange(text)
                }
            },
        )
    }

    if (coordinator.showKeySetup) {
        KeySetupScreen(
            onSetupComplete = {},
            onRegisteringAccount = coordinator.pendingRequests::requestRegistration,
            onDismiss = coordinator::dismissKeySetup,
        )
    }
}
