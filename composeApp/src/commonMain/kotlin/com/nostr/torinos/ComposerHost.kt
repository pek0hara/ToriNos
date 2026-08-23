package com.nostr.torinos

import androidx.compose.runtime.Composable
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.post.PostSheet
import com.nostr.torinos.ui.setup.KeySetupScreen

/** ComposerCoordinator が所有する一時状態を、モーダル UI へ接続する。 */
@Composable
internal fun ComposerHost(
    coordinator: ComposerCoordinator,
    onMemoSaved: () -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
    onPosted: (String, String?, NoteContext, RelayPublishResult) -> Unit,
) {
    if (coordinator.showPostSheet) {
        PostSheet(
            onDismiss = coordinator::dismissPost,
            onCancel = coordinator::cancelPost,
            onMemoSaved = onMemoSaved,
            onDeleteMemo = coordinator.selectedMemoDeleteAction?.let { deleteAction ->
                {
                    coordinator.dismissPost()
                    deleteAction()
                }
            },
            replyToId = coordinator.replyToId,
            replyToPubkey = coordinator.replyToPubkey,
            replyToPreview = coordinator.replyToPreview,
            quoteToId = coordinator.quoteToId,
            quoteToPubkey = coordinator.quoteToPubkey,
            quoteToPreview = coordinator.quoteToPreview,
            noteContext = coordinator.replyNoteContext,
            initialMemo = coordinator.selectedMemo ?: coordinator.localDraft,
            initialMemoRestoreMessage = if (
                coordinator.selectedMemo == null && coordinator.localDraft != null
            ) {
                "下書きを復元しました"
            } else {
                null
            },
            autoFocus = coordinator.selectedMemo == null &&
                coordinator.replyToId == null &&
                coordinator.quoteToId == null,
            saveLocalDraftOnCancel = coordinator.selectedMemo == null,
            onOpenCustomEmojiSettings = { draft ->
                if (coordinator.selectedMemo == null) {
                    coordinator.localDraft = draft
                }
                coordinator.clearPostContext(clearDraft = false)
                coordinator.showPostSheet = false
                onOpenCustomEmojiSettings()
            },
            onPosted = onPosted,
        )
    }

    if (coordinator.showKeySetup) {
        KeySetupScreen(
            onSetupComplete = {},
            onDismiss = coordinator::dismissKeySetup,
        )
    }
}
