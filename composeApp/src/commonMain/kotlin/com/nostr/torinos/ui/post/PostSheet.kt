package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.model.encodeNevent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.PreviewImage
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberClipboardImageReader
import com.nostr.torinos.ui.components.rememberOptimizedImagePickerLauncher
import kotlin.math.max

private const val MAX_CHARS = 800

@Composable
fun PostSheet(
    onDismiss: () -> Unit,
    onCancel: (PostMemoData?) -> Unit,
    onMemoSaved: () -> Unit,
    onDeleteMemo: (() -> Unit)? = null,
    replyToId: String? = null,
    replyToPubkey: String? = null,
    replyToPreview: String? = null,
    quoteToId: String? = null,
    quoteToPubkey: String? = null,
    quoteToPreview: String? = null,
    noteContext: NoteContext = NoteContext.Timeline,
    initialMemo: PostMemoData? = null,
    initialMemoRestoreMessage: String? = null,
    autoFocus: Boolean = false,
    saveLocalDraftOnCancel: Boolean = true,
    onOpenCustomEmojiSettings: (PostMemoData?) -> Unit = {},
    onPosted: (
        eventId: String,
        replyToId: String?,
        noteContext: NoteContext,
        publishResult: RelayPublishResult,
    ) -> Unit = { _, _, _, _ -> },
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
    var postRelayUrls by remember { mutableStateOf<Set<String>?>(null) }
    val quoteReference = remember(quoteToId, quoteToPubkey) {
        quoteToId?.let {
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

    fun cancelWithOptionalLocalDraft() {
        val draft = if (saveLocalDraftOnCancel) {
            postViewModel.currentMemoSnapshot(replyToId, replyToPubkey, noteContext)
        } else {
            null
        }
        closeOverlay { onCancel(draft) }
    }

    fun openCustomEmojiSettings() {
        val draft = if (saveLocalDraftOnCancel) {
            postViewModel.currentMemoSnapshot(replyToId, replyToPubkey, noteContext)
        } else {
            null
        }
        closeOverlay { onOpenCustomEmojiSettings(draft) }
    }

    LaunchedEffect(state.posted, state.postedEventId, state.publishResult) {
        val postedEventId = state.postedEventId
        val publishResult = state.publishResult
        if (state.posted && postedEventId != null && publishResult != null) {
            postViewModel.clearPosted()
            closeOverlay {
                onDismiss()
                onPosted(postedEventId, replyToId, noteContext, publishResult)
            }
        }
    }

    LaunchedEffect(initialMemo, replyToId, replyToPubkey, noteContext) {
        if (initialMemo != null) {
            postViewModel.restoreMemo(initialMemo, initialMemoRestoreMessage)
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
        onDismissRequest = ::cancelWithOptionalLocalDraft,
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
                    .clickable(onClick = ::cancelWithOptionalLocalDraft),
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                PostSheetContent(
                    state = state,
                    title = when {
                        replyToId != null -> "返信"
                        quoteReference != null -> "投稿を引用"
                        else -> "新しいポスト"
                    },
                    replyToPreview = replyToPreview,
                    quoteToPreview = quoteToPreview,
                    hasQuote = quoteReference != null,
                    onDismiss = ::cancelWithOptionalLocalDraft,
                    onDeleteMemo = onDeleteMemo?.let { deleteMemo ->
                        { closeOverlay(deleteMemo) }
                    },
                    onPickImage = pickImage,
                    onPasteImage = pasteImage,
                    onOpenRelaySettings = { showRelaySettingsDialog = true },
                    onOpenCustomEmojiSettings = ::openCustomEmojiSettings,
                    onTextChange = postViewModel::onTextChange,
                    onCustomEmojiInserted = postViewModel::onCustomEmojiInserted,
                    textFocusRequester = textFocusRequester,
                    onTextFocusChanged = { isTextFocused = it },
                    onRemoveImage = postViewModel::removeImage,
                    onSaveMemo = {
                        postViewModel.saveMemo(replyToId, replyToPubkey, noteContext) {
                            closeOverlay {
                                onDismiss()
                                onMemoSaved()
                            }
                        }
                    },
                    onPost = {
                        postViewModel.post(
                            replyToId = replyToId,
                            replyToPubkey = replyToPubkey,
                            noteContext = noteContext,
                            quoteReference = quoteReference,
                            relayUrls = postRelayUrls ?: RelayStore.enabledRelayUrlsSnapshot(),
                        )
                    }
                )
            }
        }
    }

    if (showRelaySettingsDialog) {
        PostRelaySettingsDialog(
            selectedRelayUrls = postRelayUrls ?: RelayStore.enabledRelayUrlsSnapshot().toSet(),
            onSelectionChange = { postRelayUrls = it },
            onDismiss = { showRelaySettingsDialog = false },
        )
    }
}

@Composable
private fun PostSheetContent(
    state: PostState,
    title: String,
    replyToPreview: String?,
    quoteToPreview: String?,
    hasQuote: Boolean,
    onDismiss: () -> Unit,
    onDeleteMemo: (() -> Unit)?,
    onPickImage: () -> Unit,
    onPasteImage: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
    onTextChange: (String) -> Unit,
    onCustomEmojiInserted: (String, CustomEmoji) -> Unit,
    textFocusRequester: FocusRequester,
    onTextFocusChanged: (Boolean) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSaveMemo: () -> Unit,
    onPost: () -> Unit,
) {
    var textValue by remember { mutableStateOf(TextFieldValue(state.text)) }
    var showCustomEmojiPicker by remember { mutableStateOf(false) }
    var showImageSourceMenu by remember { mutableStateOf(false) }
    var expandedImageData by remember { mutableStateOf<Any?>(null) }
    val dismissKeyboard = rememberDismissKeyboard()

    LaunchedEffect(state.text) {
        if (state.text != textValue.text) {
            textValue = TextFieldValue(
                text = state.text,
                selection = TextRange(state.text.length),
            )
        }
    }

    fun insertEmoji(option: ReactionOption) {
        val customEmoji = (option as? ReactionOption.Custom)?.let {
            uniqueDraftEmoji(CustomEmoji(it.shortcode, it.imageUrl), state.customEmojis, textValue.text)
        }
        val insertion = customEmoji?.let { ":${it.shortcode}:" } ?: option.eventContent
        val start = minOf(textValue.selection.start, textValue.selection.end)
        val end = maxOf(textValue.selection.start, textValue.selection.end)
        val newText = textValue.text.replaceRange(start, end, insertion)
        if (newText.length > MAX_CHARS) return
        val cursor = start + insertion.length
        textValue = TextFieldValue(
            text = newText,
            selection = TextRange(cursor),
        )
        when (option) {
            is ReactionOption.Unicode -> CustomEmojiStore.markUnicodeUsed(option.value)
            is ReactionOption.Custom -> CustomEmojiStore.markCustomReactionUsed(option.shortcode)
        }
        if (customEmoji != null) {
            onCustomEmojiInserted(newText, customEmoji)
        } else {
            onTextChange(newText)
        }
        showCustomEmojiPicker = false
    }

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
            TextButton(onClick = onDismiss) {
                Text("キャンセル", maxLines = 1)
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (onDeleteMemo != null) {
                IconButton(
                    onClick = onDeleteMemo,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "メモを削除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
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

        BasicTextField(
            value = textValue,
            onValueChange = {
                if (it.text.length <= MAX_CHARS) {
                    textValue = it
                    onTextChange(it.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(textFocusRequester)
                .onFocusChanged {
                    if (it.isFocused) showImageSourceMenu = false
                    onTextFocusChanged(it.isFocused)
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = Int.MAX_VALUE,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (textValue.text.isEmpty()) {
                        Text(
                            text = "今何してる？",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )

        if (state.customEmojis.isNotEmpty()) {
            Text(
                text = "使用中の絵文字タグ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.customEmojis, key = { it.shortcode }) { emoji ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { expandedImageData = emoji.imageUrl },
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PreviewImage(
                                data = emoji.imageUrl,
                                contentDescription = emoji.shortcode,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(modifier = Modifier.widthIn(max = 220.dp)) {
                                Text(
                                    ":${emoji.shortcode}:",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    emoji.imageUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.images, key = { it.id }) { attachment ->
                    ImageThumbnail(
                        attachment = attachment,
                        onPreview = {
                            expandedImageData = attachment.previewBytes ?: attachment.uploadedUrl
                        },
                        onRemove = { onRemoveImage(attachment.id) },
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.memoMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(
                        onClick = {
                            dismissKeyboard()
                            showImageSourceMenu = !showImageSourceMenu
                        },
                        enabled = state.images.size < 4,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(3.dp))
                        Text("画像", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = showImageSourceMenu,
                        onDismissRequest = { showImageSourceMenu = false },
                        properties = PopupProperties(
                            focusable = false,
                            // Let the image button toggle the menu and text focus close it.
                            // Outside dismissal runs before the button click and would reopen it.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        DropdownMenuItem(
                            text = { Text("写真から選択") },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            onClick = {
                                showImageSourceMenu = false
                                onPickImage()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("クリップボードから貼り付け") },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                            onClick = {
                                showImageSourceMenu = false
                                onPasteImage()
                            },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        dismissKeyboard()
                        showCustomEmojiPicker = true
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(
                        Icons.Default.InsertEmoticon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(3.dp))
                    Text("絵文字", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = {
                        dismissKeyboard()
                        onOpenRelaySettings()
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(3.dp))
                    Text("リレー", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = onSaveMemo,
                    enabled = state.canSaveMemo,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    if (state.isSavingMemo) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.size(3.dp))
                    Text("メモ保存", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = state.text.length.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.text.length >= MAX_CHARS)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCustomEmojiPicker) {
        StandardEmojiPickerSheet(
            onDismiss = { showCustomEmojiPicker = false },
            onSelect = ::insertEmoji,
            onOpenCustomEmojiSettings = onOpenCustomEmojiSettings,
        )
    }

    expandedImageData?.let { data ->
        PostImagePreviewDialog(
            data = data,
            onDismiss = { expandedImageData = null },
        )
    }
}

@Composable
private fun ImageThumbnail(
    attachment: ImageAttachment,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(90.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        val previewData: Any? = attachment.previewBytes ?: attachment.uploadedUrl
        if (previewData != null) {
            PreviewImage(
                data = previewData,
                contentDescription = "画像を拡大表示",
                contentScale = ContentScale.Fit,
                maxDecodeSizePx = 256,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onPreview),
            )
        }
        if (attachment.isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
        } else {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "削除",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PostImagePreviewDialog(
    data: Any,
    onDismiss: () -> Unit,
) {
    var scale by remember(data) { mutableStateOf(1f) }
    var offset by remember(data) { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        offset = if (nextScale == 1f) {
            Offset.Zero
        } else {
            val maxOffset = 2400f * max(1f, nextScale - 1f)
            Offset(
                x = (offset.x + panChange.x).coerceIn(-maxOffset, maxOffset),
                y = (offset.y + panChange.y).coerceIn(-maxOffset, maxOffset),
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            PreviewImage(
                data = data,
                contentDescription = "拡大画像",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .transformable(
                        state = transformableState,
                        canPan = { scale > 1f },
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "閉じる",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PostRelaySettingsDialog(
    selectedRelayUrls: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val relayEntries by RelayStore.entries.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("この投稿のリレー") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (relayEntries.isEmpty()) {
                    Text(
                        text = "リレーが設定されていません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(relayEntries, key = { it.url }) { entry ->
                            PostRelayRow(
                                entry = entry,
                                checked = entry.url in selectedRelayUrls,
                                onToggle = { enabled ->
                                    onSelectionChange(
                                        if (enabled) {
                                            selectedRelayUrls + entry.url
                                        } else {
                                            selectedRelayUrls - entry.url
                                        },
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun PostRelayRow(
    entry: RelayEntry,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
        )
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
