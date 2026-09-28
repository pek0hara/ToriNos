package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.ReactionOption

@Composable
fun AppFloatingActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
    }
}

/** 絵文字を挿入した後の本文とカーソル位置。 */
data class TextInsertion(val text: String, val cursor: Int)

/**
 * 1行入力と送信ボタン。[onInsertEmoji] を渡すと絵文字ボタンを出し、選んだ絵文字をカーソル位置へ挿入する。
 * 挿入は呼び出し側が行い（下書きの絵文字を保持するため）、結果の本文とカーソル位置を返す。
 */
@Composable
fun AppMessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSending: Boolean = false,
    error: String? = null,
    onInsertEmoji: ((text: String, selectionStart: Int, selectionEnd: Int, option: ReactionOption) -> TextInsertion?)? = null,
    onOpenCustomEmojiSettings: (() -> Unit)? = null,
) {
    DismissKeyboardOnLeave()
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val dismissKeyboard = rememberDismissKeyboard()
    // 送信後のクリアなど、外から本文が変わったときだけ追従する。
    LaunchedEffect(text) {
        if (value.text != text) {
            value = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onInsertEmoji != null) {
                    IconButton(
                        onClick = {
                            dismissKeyboard()
                            showEmojiPicker = true
                        },
                        enabled = enabled && !isSending,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertEmoticon,
                            contentDescription = "絵文字",
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        value = next
                        if (next.text != text) onTextChange(next.text)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    enabled = enabled && !isSending,
                    minLines = 1,
                    maxLines = 4,
                )
                IconButton(
                    onClick = onSend,
                    enabled = enabled && !isSending && text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "送信",
                    )
                }
            }
            error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Spacer(modifier = Modifier.size(0.dp))
        }
    }

    if (showEmojiPicker && onInsertEmoji != null) {
        StandardEmojiPickerSheet(
            onDismiss = { showEmojiPicker = false },
            onSelect = { option ->
                showEmojiPicker = false
                onInsertEmoji(value.text, value.selection.start, value.selection.end, option)?.let { inserted ->
                    value = TextFieldValue(text = inserted.text, selection = TextRange(inserted.cursor))
                }
            },
            onOpenCustomEmojiSettings = onOpenCustomEmojiSettings,
        )
    }
}
