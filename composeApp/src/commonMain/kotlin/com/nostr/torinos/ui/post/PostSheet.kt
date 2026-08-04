package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.encodeNevent
import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.CustomEmojiList
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.PreviewImage
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberOptimizedImagePickerLauncher
import com.nostr.torinos.ui.relay.RelaySettingsViewModel

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
    saveLocalDraftOnCancel: Boolean = true,
    onOpenCustomEmojiSettings: (PostMemoData?) -> Unit = {},
    onPosted: (eventId: String, replyToId: String?, noteContext: NoteContext) -> Unit = { _, _, _ -> },
    viewModel: PostViewModel? = null,
) {
    val postViewModel = viewModel ?: remember { PostViewModel() }
    val state by postViewModel.state.collectAsState()
    var showRelaySettingsDialog by remember { mutableStateOf(false) }
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

    fun cancelWithOptionalLocalDraft() {
        val draft = if (saveLocalDraftOnCancel) {
            postViewModel.currentMemoSnapshot(replyToId, replyToPubkey, noteContext)
        } else {
            null
        }
        onCancel(draft)
    }

    fun openCustomEmojiSettings() {
        val draft = if (saveLocalDraftOnCancel) {
            postViewModel.currentMemoSnapshot(replyToId, replyToPubkey, noteContext)
        } else {
            null
        }
        onOpenCustomEmojiSettings(draft)
    }

    LaunchedEffect(state.posted, state.postedEventId) {
        val postedEventId = state.postedEventId
        if (state.posted && postedEventId != null) {
            postViewModel.clearPosted()
            onDismiss()
            onPosted(postedEventId, replyToId, noteContext)
        }
    }

    LaunchedEffect(initialMemo, replyToId, replyToPubkey, noteContext) {
        if (initialMemo != null) {
            postViewModel.restoreMemo(initialMemo, initialMemoRestoreMessage)
        }
    }

    Dialog(
        onDismissRequest = ::cancelWithOptionalLocalDraft,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
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
                    onDeleteMemo = onDeleteMemo,
                    onPickImage = pickImage,
                    onOpenRelaySettings = { showRelaySettingsDialog = true },
                    onOpenCustomEmojiSettings = ::openCustomEmojiSettings,
                    onTextChange = postViewModel::onTextChange,
                    onRemoveImage = postViewModel::removeImage,
                    onSaveMemo = {
                        postViewModel.saveMemo(replyToId, replyToPubkey, noteContext) {
                            onDismiss()
                            onMemoSaved()
                        }
                    },
                    onPost = {
                        postViewModel.post(replyToId, replyToPubkey, noteContext, quoteReference)
                    },
                )
            }
        }
    }

    if (showRelaySettingsDialog) {
        PostRelaySettingsDialog(
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
    onOpenRelaySettings: () -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
    onTextChange: (String) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSaveMemo: () -> Unit,
    onPost: () -> Unit,
) {
    var textValue by remember { mutableStateOf(TextFieldValue(state.text)) }
    var showCustomEmojiPicker by remember { mutableStateOf(false) }
    val dismissKeyboard = rememberDismissKeyboard()

    LaunchedEffect(state.text) {
        if (state.text != textValue.text) {
            textValue = TextFieldValue(
                text = state.text,
                selection = TextRange(state.text.length),
            )
        }
    }

    fun insertCustomEmoji(emoji: CustomEmoji) {
        val insertion = ":${emoji.shortcode}:"
        val start = minOf(textValue.selection.start, textValue.selection.end)
        val end = maxOf(textValue.selection.start, textValue.selection.end)
        val newText = textValue.text.replaceRange(start, end, insertion)
        if (newText.length > MAX_CHARS) return
        val cursor = start + insertion.length
        textValue = TextFieldValue(
            text = newText,
            selection = TextRange(cursor),
        )
        CustomEmojiStore.markUsed(emoji.shortcode)
        onTextChange(newText)
        showCustomEmojiPicker = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
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
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
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

            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    if (it.text.length <= MAX_CHARS) {
                        textValue = it
                        onTextChange(it.text)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("今何してる？") },
                maxLines = 6,
            )

            if (state.images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.images, key = { it.id }) { attachment ->
                        ImageThumbnail(
                            attachment = attachment,
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
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            onPickImage()
                        },
                        modifier = Modifier.size(36.dp),
                        enabled = state.images.size < 4,
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = "画像を添付",
                            tint = if (state.images.size < 4) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            showCustomEmojiPicker = true
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.InsertEmoticon,
                            contentDescription = "カスタム絵文字",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            onOpenRelaySettings()
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.SettingsInputAntenna,
                            contentDescription = "リレー設定",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = state.text.length.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.text.length >= MAX_CHARS)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onSaveMemo,
                    enabled = state.canSaveMemo,
                ) {
                    if (state.isSavingMemo) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("メモ保存", maxLines = 1)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("キャンセル", maxLines = 1)
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
        }
    }

    if (showCustomEmojiPicker) {
        CustomEmojiPickerDialog(
            onDismiss = { showCustomEmojiPicker = false },
            onEmojiSelected = ::insertCustomEmoji,
            onOpenCustomEmojiSettings = onOpenCustomEmojiSettings,
        )
    }
}

@Composable
private fun CustomEmojiPickerDialog(
    onDismiss: () -> Unit,
    onEmojiSelected: (CustomEmoji) -> Unit,
    onOpenCustomEmojiSettings: () -> Unit,
) {
    val emojis by CustomEmojiStore.emojis.collectAsState()
    val emojiLists by CustomEmojiStore.emojiLists.collectAsState()
    val recentShortcodes by CustomEmojiStore.recentEmojiShortcodes.collectAsState()
    val emojiMap = remember(emojis) { emojis.associateBy { it.shortcode } }
    val recentEmojis = remember(recentShortcodes, emojiMap) {
        recentShortcodes.mapNotNull { emojiMap[it] }
    }
    var selectedListId by remember { mutableStateOf<String?>(null) }
    val selectedList = remember(selectedListId, emojiLists) {
        emojiLists.firstOrNull { it.id == selectedListId }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(selectedList?.name ?: "カスタム絵文字") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectedList == null) {
                    if (recentEmojis.isNotEmpty()) {
                        Text(
                            text = "最近使った絵文字",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(recentEmojis, key = { "recent-${it.shortcode}" }) { emoji ->
                                CustomEmojiTile(
                                    emoji = emoji,
                                    onClick = { onEmojiSelected(emoji) },
                                )
                            }
                        }
                    }

                    Text(
                        text = "絵文字リスト",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (emojiLists.isEmpty()) {
                        Text(
                            text = "登録済みの絵文字リストがありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(emojiLists, key = { it.id }) { list ->
                                CustomEmojiListRow(
                                    list = list,
                                    onClick = { selectedListId = list.id },
                                )
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { selectedListId = null }) {
                        Text("絵文字リストへ戻る")
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 72.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(selectedList.emojis, key = { it.shortcode }) { emoji ->
                            CustomEmojiTile(
                                emoji = emoji,
                                onClick = { onEmojiSelected(emoji) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenCustomEmojiSettings) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("絵文字を追加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun CustomEmojiListRow(
    list: CustomEmojiList,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = list.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${list.emojis.size}個",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            list.emojis.take(4).forEach { emoji ->
                NetworkImage(
                    url = emoji.imageUrl,
                    contentDescription = ":${emoji.shortcode}:",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun CustomEmojiTile(
    emoji: CustomEmoji,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(width = 64.dp, height = 72.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NetworkImage(
            url = emoji.imageUrl,
            contentDescription = ":${emoji.shortcode}:",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = ":${emoji.shortcode}:",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImageThumbnail(
    attachment: ImageAttachment,
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
                contentDescription = null,
                contentScale = ContentScale.Crop,
                maxDecodeSizePx = 256,
                modifier = Modifier.fillMaxSize(),
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
private fun PostRelaySettingsDialog(
    onDismiss: () -> Unit,
    viewModel: RelaySettingsViewModel = viewModel(key = "post-relays") { RelaySettingsViewModel() },
) {
    val relayEntries by viewModel.entries.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リレー設定") },
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
                                onToggle = { enabled -> viewModel.setEnabled(entry.url, enabled) },
                                onDelete = { viewModel.remove(entry.url) },
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
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = entry.enabled,
            onCheckedChange = onToggle,
        )
        Text(
            text = entry.url,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
