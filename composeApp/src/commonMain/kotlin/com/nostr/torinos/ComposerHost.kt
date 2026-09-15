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
    onDraftSaved: () -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
    onPosted: (String, String?, NoteContext, RelayPublishResult, String?) -> Unit,
) {
    if (coordinator.showPostSheet) {
        PostSheet(
            onDismiss = coordinator::dismissPost,
            onDraftSaved = onDraftSaved,
            replyTarget = coordinator.replyTarget,
            replyToPreview = coordinator.replyToPreview,
            quoteToId = coordinator.quoteToId,
            quoteToPubkey = coordinator.quoteToPubkey,
            quoteToPreview = coordinator.quoteToPreview,
            noteContext = coordinator.replyNoteContext,
            initialMemo = coordinator.localDraft,
            initialMemoRestoreMessage = if (coordinator.localDraft != null) {
                "下書きを復元しました"
            } else {
                null
            },
            autoFocus = coordinator.replyTarget == null &&
                coordinator.quoteToId == null,
            preserveLocalDraftOnNavigation = true,
            onOpenCustomEmojiSettings = { draft ->
                coordinator.localDraft = draft
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
