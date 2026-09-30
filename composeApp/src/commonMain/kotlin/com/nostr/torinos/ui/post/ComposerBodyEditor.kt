package com.nostr.torinos.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.nostr.torinos.crypto.isWebPlatform
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.ui.components.recordReactionUse
import com.nostr.torinos.ui.components.PreviewImage
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import kotlin.math.max

internal const val MAX_POST_CHARS = 800

/**
 * 投稿本文の入力欄・使用中の絵文字タグ・添付画像・ツールバー(画像/絵文字/リレー)。
 * フィード投稿(`PostSheet`)とチャンネル作成シート(FR-13)で共有する。状態は [PostViewModel] の [PostState]。
 *
 * [textModifier] で入力欄の高さを決める(全画面なら `weight(1f)`、スクロール内なら最小高さ)。
 * [onOpenRelaySettings] が null ならリレーボタンを出さない。
 */
@Composable
internal fun ColumnScope.ComposerBodyEditor(
    state: PostState,
    placeholder: String,
    textModifier: Modifier,
    onPickImage: () -> Unit,
    onPasteImage: () -> Unit,
    onOpenRelaySettings: (() -> Unit)?,
    onOpenCustomEmojiSettings: (() -> Unit)?,
    onContentWarningChange: ((Boolean) -> Unit)? = null,
    onTextChange: (String) -> Unit,
    onCustomEmojiInserted: (String, CustomEmoji) -> Unit,
    textFocusRequester: FocusRequester,
    onTextFocusChanged: (Boolean) -> Unit,
    onRemoveImage: (Int) -> Unit,
    extraMessages: @Composable () -> Unit = {},
) {
    // 簡易コンポーザーから展開したときも、本文の末尾から続けて入力できるようカーソルを末尾に置く。
    var textValue by remember {
        mutableStateOf(TextFieldValue(state.text, selection = TextRange(state.text.length)))
    }
    val accountSession = LocalAccountSession.current
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
        val inserted = insertDraftEmoji(
            text = textValue.text,
            selectionStart = textValue.selection.start,
            selectionEnd = textValue.selection.end,
            option = option,
            retained = state.customEmojis,
            maxLength = MAX_POST_CHARS,
        ) ?: return
        textValue = TextFieldValue(
            text = inserted.text,
            selection = TextRange(inserted.cursor),
        )
        accountSession.recordReactionUse(option)
        val customEmoji = inserted.customEmoji
        if (customEmoji != null) {
            onCustomEmojiInserted(inserted.text, customEmoji)
        } else {
            onTextChange(inserted.text)
        }
        showCustomEmojiPicker = false
    }

    BasicTextField(
        value = textValue,
        onValueChange = {
            if (it.text.length <= MAX_POST_CHARS) {
                textValue = it
                onTextChange(it.text)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(textModifier)
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
                        text = placeholder,
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
    extraMessages()

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
                        // Web版はクリップボードの画像読み取りに対応していないため、メニューを出さずに写真を選ぶ。
                        // ブラウザはタップ中にしかファイル選択を開かないので、ここで同期的に呼ぶ。
                        if (isWebPlatform) {
                            onPickImage()
                        } else {
                            showImageSourceMenu = !showImageSourceMenu
                        }
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
            if (onOpenRelaySettings != null) {
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
            }
            if (onContentWarningChange != null) {
                TextButton(
                    onClick = {
                        dismissKeyboard()
                        onContentWarningChange(!state.hasContentWarning)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (state.hasContentWarning) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(3.dp))
                    Text(
                        if (state.hasContentWarning) "閲覧注意 ON" else "閲覧注意",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = state.text.length.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.text.length >= MAX_POST_CHARS)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
