package com.nostr.torinos.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.PreviewImage
import com.nostr.torinos.ui.components.rememberClipboardImageReader
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.components.rememberOptimizedImagePickerLauncher
import com.nostr.torinos.ui.post.ComposedNote
import com.nostr.torinos.ui.post.ComposerBodyEditor
import com.nostr.torinos.ui.post.PostViewModel
import com.nostr.torinos.ui.post.composeNoteContent

/**
 * チャンネル作成シート(FR-13)。フィード投稿と同じ全画面シートで、名前・説明・アイコン・推奨リレー・
 * 最初の投稿を入力する。送信処理は [ChannelListViewModel.createChannel]。
 * 最初の投稿の本文・絵文字・画像添付は [PostViewModel] の別インスタンスで扱い、フィード投稿の下書きとは共有しない。
 */
@Composable
internal fun ChannelCreateSheet(
    dialog: ChannelListViewModel.CreateDialogState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onRelaySelectionChange: (Set<String>) -> Unit,
    onAddCustomRelay: (String) -> String?,
    onPictureSelected: (ByteArray, String) -> Unit,
    onPictureRemoved: () -> Unit,
    onSubmit: (ComposedNote?) -> Unit,
) {
    val bodyViewModel = accountSessionViewModel(key = "channel-create-composer") { session ->
        PostViewModel(session)
    }
    val body by bodyViewModel.state.collectAsState()
    val relayEntries by RelayStore.entries.collectAsState()
    val plan = remember(dialog.recommendedRelays, relayEntries) {
        ChannelCreatePlan.from(dialog.recommendedRelays, relayEntries)
    }
    val dismissKeyboard = rememberDismissKeyboard()
    val textFocusRequester = remember { FocusRequester() }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val locked = dialog.isCreating || dialog.isRetryingFirstPost

    LaunchedEffect(Unit) { bodyViewModel.reset() }

    val pickPicture = rememberOptimizedImagePickerLauncher { image ->
        if (image != null) onPictureSelected(image.uploadBytes, image.mimeType)
    }
    val pickBodyImage = rememberOptimizedImagePickerLauncher { image ->
        if (image != null) {
            bodyViewModel.uploadAndAppendImage(image.uploadBytes, image.mimeType, image.previewBytes)
        }
    }
    val pasteBodyImage = rememberClipboardImageReader { image ->
        if (image != null) {
            bodyViewModel.uploadAndAppendImage(image.uploadBytes, image.mimeType, image.previewBytes)
        } else {
            bodyViewModel.showImagePasteError()
        }
    }

    fun requestCancel() {
        if (dialog.isCreating) return
        if (dialog.hasInput || body.hasDraftContent || body.images.isNotEmpty()) {
            dismissKeyboard()
            showDiscardDialog = true
        } else {
            dismissKeyboard()
            onDismiss()
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
        DismissKeyboardOnLeave()
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::requestCancel, enabled = !dialog.isCreating) {
                        Text("キャンセル", maxLines = 1)
                    }
                    Text(
                        text = "新規チャンネル",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        maxLines = 1,
                    )
                    Button(
                        onClick = {
                            dismissKeyboard()
                            onSubmit(
                                composeNoteContent(
                                    text = body.text,
                                    images = body.images,
                                    customEmojis = body.customEmojis,
                                    setAddressOf = bodyViewModel::setAddressOf,
                                ),
                            )
                        },
                        enabled = dialog.canSubmit && !body.isUploadingAny && !body.hasFailedUpload &&
                            (!dialog.isRetryingFirstPost || body.canPost),
                    ) {
                        if (dialog.isCreating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (dialog.isRetryingFirstPost) "投稿を再送信" else "作成", maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChannelIconPicker(
                        picture = dialog.picture,
                        isUploading = dialog.isUploadingPicture,
                        enabled = !locked,
                        onPick = pickPicture,
                        onRemove = onPictureRemoved,
                    )
                    OutlinedTextField(
                        value = dialog.name,
                        onValueChange = onNameChange,
                        label = { Text("チャンネル名 *") },
                        singleLine = true,
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dialog.about,
                        onValueChange = onAboutChange,
                        label = { Text("説明") },
                        maxLines = 4,
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ExpandableRelaySelector(
                        sessionId = dialog.sessionId,
                        candidates = dialog.candidateRelays,
                        selected = dialog.selectedRelays,
                        recommendedRelays = dialog.recommendedRelays,
                        targets = plan.targets,
                        enabled = !locked,
                        onSelectionChange = onRelaySelectionChange,
                        onAddCustomRelay = onAddCustomRelay,
                        dismissKeyboard = dismissKeyboard,
                    )
                    HorizontalDivider()
                    Text(
                        text = "最初の投稿（任意）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ComposerBodyEditor(
                        state = body,
                        placeholder = "チャンネルの最初のメッセージ",
                        textModifier = Modifier
                            .heightIn(min = 140.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                        onPickImage = pickBodyImage,
                        onPasteImage = pasteBodyImage,
                        onOpenRelaySettings = null,
                        onOpenCustomEmojiSettings = null,
                        onTextChange = bodyViewModel::onTextChange,
                        onCustomEmojiInserted = bodyViewModel::onCustomEmojiInserted,
                        textFocusRequester = textFocusRequester,
                        onTextFocusChanged = {},
                        onRemoveImage = bodyViewModel::removeImage,
                    )
                    if (body.hasFailedUpload) {
                        Text(
                            text = "画像のアップロードに失敗しました。添付を削除するか、画像を選び直してください。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    dialog.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PublishResultList(dialog.publishState)
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(if (dialog.isRetryingFirstPost) "最初の投稿を破棄しますか？" else "作成をやめますか？") },
            text = {
                Text(
                    if (dialog.isRetryingFirstPost) {
                        "チャンネルは作成済みです。未送信の最初の投稿は破棄されます。"
                    } else {
                        "入力した内容は保存されません。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onDismiss()
                }) { Text("破棄", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("編集を続ける") }
            },
        )
    }
}

@Composable
internal fun ChannelIconPicker(
    picture: String,
    isUploading: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled && !isUploading, onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (picture.isNotBlank()) {
                PreviewImage(
                    data = picture,
                    contentDescription = "チャンネルアイコン",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.AddAPhoto,
                    contentDescription = "アイコン画像を選択",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isUploading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                }
            }
        }
        Column {
            Text("アイコン", style = MaterialTheme.typography.labelLarge)
            if (picture.isNotBlank() && enabled) {
                TextButton(onClick = onRemove) { Text("削除") }
            } else {
                Text(
                    text = if (isUploading) "アップロード中…" else "タップして画像を選択（任意）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpandableRelaySelector(
    sessionId: Long,
    candidates: List<String>,
    selected: Set<String>,
    recommendedRelays: List<String>,
    targets: List<String>,
    enabled: Boolean,
    onSelectionChange: (Set<String>) -> Unit,
    onAddCustomRelay: (String) -> String?,
    dismissKeyboard: () -> Unit,
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    var customInput by remember(sessionId) { mutableStateOf("") }
    var customError by remember(sessionId) { mutableStateOf<String?>(null) }
    var inputFocused by remember(sessionId) { mutableStateOf(false) }
    val inputBringIntoView = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(inputFocused, imeBottom) {
        if (inputFocused) inputBringIntoView.bringIntoView()
    }

    fun addRelay() {
        customError = onAddCustomRelay(customInput)
        if (customError == null) {
            customInput = ""
            dismissKeyboard()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "推奨リレー: ${recommendedRelays.size}件",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    expanded = !expanded
                    if (!expanded) dismissKeyboard()
                },
                enabled = enabled,
            ) { Text(if (expanded) "閉じる" else "変更") }
        }
        if (recommendedRelays.isEmpty()) {
            // 空でも作成できる(第19章 未決6)。他クライアントから見つけにくくなることだけ伝える。
            Text(
                text = "推奨リレーが未選択です。他のクライアントからメッセージが見えにくくなります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "送信先: ${targets.size}件（推奨リレー + あなたの書き込みリレー）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Text(
                text = "チャンネル情報に保存され、参加者がメッセージを読み書きするリレーです（最大10件）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = customInput,
                onValueChange = {
                    customInput = it
                    customError = null
                },
                label = { Text("リレーURLを追加") },
                placeholder = { Text("wss://") },
                singleLine = true,
                enabled = enabled,
                isError = customError != null,
                supportingText = customError?.let { error -> { Text(error) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(inputBringIntoView)
                    .onFocusChanged { inputFocused = it.isFocused },
            )
            Button(
                onClick = ::addRelay,
                enabled = enabled && customInput.isNotBlank(),
            ) { Text("追加") }
            if (candidates.isEmpty()) {
                Text("リレーが設定されていません", style = MaterialTheme.typography.bodySmall)
            }
            candidates.forEach { url ->
                val checked = url in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) {
                            if (!checked && selected.size >= 10) {
                                customError = "推奨リレーは10件までです"
                            } else {
                                customError = null
                                onSelectionChange(if (checked) selected - url else selected + url)
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Text(url, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

/** 送信後に失敗を含む場合だけ、リレー別の結果を並べる(FR-10)。 */
@Composable
private fun PublishResultList(state: ChannelPublishUiState) {
    if (state.phase != ChannelPublishUiState.Phase.Failed && state.phase != ChannelPublishUiState.Phase.PartialSuccess) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.targets.forEach { url ->
            val failure = state.failed[url]
            Row {
                Text(
                    text = url.relayHost(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        url in state.succeeded -> "成功"
                        failure != null -> "失敗"
                        else -> "応答待ち"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun String.relayHost(): String = removePrefix("wss://").removePrefix("ws://")
