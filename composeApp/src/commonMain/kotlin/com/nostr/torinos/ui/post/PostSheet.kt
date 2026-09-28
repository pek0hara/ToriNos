package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.ui.channel.ComposerRelayContext
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.encodeNevent
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.RelayMultiSelectDialog
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberClipboardImageReader
import com.nostr.torinos.ui.components.rememberOptimizedImagePickerLauncher
import kotlin.math.max
import kotlinx.coroutines.delay
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave

private const val KEYBOARD_DISMISS_DELAY_MS = 300L

@Composable
fun PostSheet(
    onDismiss: () -> Unit,
    onDraftSaved: () -> Unit,
    replyTarget: ReplyTarget? = null,
    replyToPreview: String? = null,
    quoteToId: String? = null,
    quoteToPubkey: String? = null,
    quoteToPreview: String? = null,
    noteContext: NoteContext = NoteContext.Timeline,
    /** チャンネル返信の送信先の初期選択と推奨リレー(第16.14節)。 */
    relayContext: ComposerRelayContext? = null,
    initialMemo: PostMemoData? = null,
    initialMemoRestoreMessage: String? = null,
    autoFocus: Boolean = false,
    preserveLocalDraftOnNavigation: Boolean = true,
    onOpenCustomEmojiSettings: (PostMemoData?) -> Unit = {},
    onPosted: (
        eventId: String,
        replyToId: String?,
        noteContext: NoteContext,
        publishResult: RelayPublishResult,
        warning: String?,
    ) -> Unit = { _, _, _, _, _ -> },
    viewModel: PostViewModel? = null,
) {
    val postViewModel = viewModel ?: accountSessionViewModel(
        key = "post-composer",
    ) { accountSession -> PostViewModel(accountSession) }
    val state by postViewModel.state.collectAsState()
    val textFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = rememberDismissKeyboard()
    var isTextFocused by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    var showRelaySettingsDialog by remember { mutableStateOf(false) }
    var showDraftListSheet by remember { mutableStateOf(false) }
    var showSaveDraftDialog by remember { mutableStateOf(false) }
    var isWaitingToShowSaveDraftDialog by remember { mutableStateOf(false) }
    var selectedDraft by remember { mutableStateOf<PostMemoData?>(null) }
    var postRelayUrls by remember { mutableStateOf<Set<String>?>(relayContext?.initialRelayUrls) }
    val activeMemo = selectedDraft ?: initialMemo
    val activeNoteContext = selectedDraft?.let { memo ->
        memo.channelId?.takeIf { it.isNotBlank() }?.let(NoteContext::Channel)
            ?: NoteContext.Timeline
    } ?: noteContext
    val activeReplyTarget = if (selectedDraft != null) {
        selectedDraft?.restoreReplyTarget(activeNoteContext)
    } else {
        replyTarget ?: initialMemo?.restoreReplyTarget(noteContext)
    }
    val replyToId = activeReplyTarget?.parent?.id
    val quoteReference = remember(quoteToId, quoteToPubkey, selectedDraft) {
        quoteToId?.takeIf { selectedDraft == null }?.let {
            "nostr:${encodeNevent(eventId = it, authorPubkey = quoteToPubkey)}"
        }
    }

    val pickImage = rememberOptimizedImagePickerLauncher { image ->
        if (image != null) {
            postViewModel.uploadAndAppendImage(
                bytes = image.uploadBytes,
                mimeType = image.mimeType,
                previewBytes = image.previewBytes,
            )
        }
    }
    val pasteImage = rememberClipboardImageReader { image ->
        if (image != null) {
            postViewModel.uploadAndAppendImage(
                bytes = image.uploadBytes,
                mimeType = image.mimeType,
                previewBytes = image.previewBytes,
            )
        } else {
            postViewModel.showImagePasteError()
        }
    }

    fun closeOverlay(onClosed: () -> Unit) {
        if (isClosing) return
        isClosing = true
        dismissKeyboard()
        onClosed()
    }

    fun requestCancel() {
        if (isClosing || showSaveDraftDialog || isWaitingToShowSaveDraftDialog) return
        if (state.hasDraftContent) {
            dismissKeyboard()
            isWaitingToShowSaveDraftDialog = true
        } else {
            closeOverlay(onDismiss)
        }
    }

    fun openCustomEmojiSettings() {
        val draft = if (preserveLocalDraftOnNavigation) {
            postViewModel.currentMemoSnapshot(activeReplyTarget, activeNoteContext)
        } else {
            null
        }
        closeOverlay { onOpenCustomEmojiSettings(draft) }
    }

    LaunchedEffect(isWaitingToShowSaveDraftDialog) {
        if (isWaitingToShowSaveDraftDialog) {
            delay(KEYBOARD_DISMISS_DELAY_MS)
            isWaitingToShowSaveDraftDialog = false
            if (!isClosing) showSaveDraftDialog = true
        }
    }

    LaunchedEffect(
        state.posted,
        state.postedEventId,
        state.publishResult,
        state.postWarning,
    ) {
        val postedEventId = state.postedEventId
        val publishResult = state.publishResult
        if (state.posted && postedEventId != null && publishResult != null) {
            postViewModel.clearPosted()
            closeOverlay {
                onDismiss()
                onPosted(
                    postedEventId,
                    replyToId,
                    activeNoteContext,
                    publishResult,
                    state.postWarning,
                )
            }
        }
    }

    LaunchedEffect(activeMemo, activeReplyTarget, activeNoteContext) {
        if (activeMemo != null) {
            postViewModel.restoreMemo(
                activeMemo,
                if (selectedDraft != null) "下書きを復元しました" else initialMemoRestoreMessage,
            )
        } else {
            postViewModel.reset()
        }
    }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            // Draw the sheet for at least one frame before starting the keyboard animation.
            withFrameNanos { }
            if (!isClosing && !isTextFocused) {
                textFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    Dialog(
        onDismissRequest = ::requestCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = ::requestCancel),
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                PostSheetContent(
                    state = state,
                    replyToPreview = replyToPreview.takeIf { selectedDraft == null },
                    quoteToPreview = quoteToPreview.takeIf { selectedDraft == null },
                    hasQuote = quoteReference != null,
                    onDismiss = ::requestCancel,
                    onOpenDrafts = {
                        dismissKeyboard()
                        showDraftListSheet = true
                    },
                    onPickImage = pickImage,
                    onPasteImage = pasteImage,
                    onOpenRelaySettings = { showRelaySettingsDialog = true },
                    onOpenCustomEmojiSettings = ::openCustomEmojiSettings,
                    onContentWarningChange = postViewModel::onContentWarningChange,
                    onTextChange = postViewModel::onTextChange,
                    onCustomEmojiInserted = postViewModel::onCustomEmojiInserted,
                    textFocusRequester = textFocusRequester,
                    onTextFocusChanged = { isTextFocused = it },
                    onRemoveImage = postViewModel::removeImage,
                    onPost = {
                        postViewModel.post(
                            replyTarget = activeReplyTarget,
                            noteContext = activeNoteContext,
                            quoteReference = quoteReference,
                            relayUrls = postRelayUrls ?: RelayStore.writableRelayUrlsSnapshot(),
                        )
                    }
                )
            }
            if (isWaitingToShowSaveDraftDialog || showSaveDraftDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isWaitingToShowSaveDraftDialog) {
                                Color.Black.copy(alpha = 0.32f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
            }
        }
    }

    if (showRelaySettingsDialog) {
        PostRelaySettingsDialog(
            recommendedRelayUrls = relayContext?.recommendedRelayUrls.orEmpty(),
            selectedRelayUrls = postRelayUrls ?: RelayStore.writableRelayUrlsSnapshot().toSet(),
            onSelectionChange = { postRelayUrls = it },
            onDismiss = { showRelaySettingsDialog = false },
        )
    }

    if (showDraftListSheet) {
        DraftListSheet(
            onDismiss = { showDraftListSheet = false },
            onDraftClick = { memo ->
                selectedDraft = memo
                showDraftListSheet = false
            },
        )
    }

    if (showSaveDraftDialog) {
        Dialog(
            onDismissRequest = {
                if (!state.isSavingMemo) showSaveDraftDialog = false
            },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.error?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            onClick = {
                                postViewModel.saveMemo(activeReplyTarget, activeNoteContext) {
                                    closeOverlay {
                                        onDismiss()
                                        onDraftSaved()
                                    }
                                }
                            },
                            enabled = state.canSaveMemo,
                        ) {
                            if (state.isSavingMemo) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("下書きに保存")
                            }
                        }
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            onClick = { closeOverlay(onDismiss) },
                            enabled = !state.isSavingMemo,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("保存せず閉じる")
                        }
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            onClick = { showSaveDraftDialog = false },
                            enabled = !state.isSavingMemo,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text("編集を続ける")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostSheetContent(
    state: PostState,
    replyToPreview: String?,
    quoteToPreview: String?,
    hasQuote: Boolean,
    onDismiss: () -> Unit,
    onOpenDrafts: () -> Unit,
    onPickImage: () -> Unit,
    onPasteImage: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
    onContentWarningChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onCustomEmojiInserted: (String, CustomEmoji) -> Unit,
    textFocusRequester: FocusRequester,
    onTextFocusChanged: (Boolean) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPost: () -> Unit,
) {
    DismissKeyboardOnLeave()
    val dismissKeyboard = rememberDismissKeyboard()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 12.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    dismissKeyboard()
                    onDismiss()
                },
            ) {
                Text("キャンセル", maxLines = 1)
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    dismissKeyboard()
                    onOpenDrafts()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text("下書き", maxLines = 1)
            }
            Button(
                onClick = onPost,
                enabled = state.canPost ||
                    (hasQuote && !state.isPosting && !state.isUploadingAny),
            ) {
                if (state.isPosting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("ポスト", maxLines = 1)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        quoteToPreview?.takeIf { it.isNotBlank() }?.let { preview ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "引用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        replyToPreview?.takeIf { it.isNotBlank() }?.let { preview ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "返信先",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        ComposerBodyEditor(
            state = state,
            placeholder = "今何してる？",
            textModifier = Modifier.weight(1f),
            onPickImage = onPickImage,
            onPasteImage = onPasteImage,
            onOpenRelaySettings = onOpenRelaySettings,
            onOpenCustomEmojiSettings = onOpenCustomEmojiSettings,
            onContentWarningChange = onContentWarningChange,
            onTextChange = onTextChange,
            onCustomEmojiInserted = onCustomEmojiInserted,
            textFocusRequester = textFocusRequester,
            onTextFocusChanged = onTextFocusChanged,
            onRemoveImage = onRemoveImage,
            extraMessages = {
                state.memoMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
    }
}

@Composable
private fun PostRelaySettingsDialog(
    recommendedRelayUrls: List<String>,
    selectedRelayUrls: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val relayEntries by RelayStore.entries.collectAsState()
    val writable = relayEntries.filter { it.enabled && it.write }.map { it.url }
    // チャンネル返信ではユーザー設定に無い推奨リレーも候補に出す。
    val candidates = (recommendedRelayUrls + writable + selectedRelayUrls).distinct()
    val allRecommendedRemoved = recommendedRelayUrls.isNotEmpty() && recommendedRelayUrls.none { it in selectedRelayUrls }
    RelayMultiSelectDialog(
        title = "この投稿のリレー",
        candidates = candidates,
        selected = selectedRelayUrls,
        onSelectionChange = onSelectionChange,
        onDismiss = onDismiss,
        note = when {
            allRecommendedRemoved -> "チャンネルの推奨リレーがすべて外れています。他のクライアントから見えにくくなります。"
            recommendedRelayUrls.isNotEmpty() -> "チャンネルの推奨リレー: ${recommendedRelayUrls.size}件"
            else -> null
        },
    )
}
