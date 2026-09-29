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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

/**
 * 入力欄のフォーカス変化でキーボードを閉じるか。
 * 初回Compositionで未フォーカスが通知されても閉じないよう、直前まで入力欄がフォーカスされていたことを条件にする。
 */
internal fun shouldHideKeyboardOnFocusChange(
    dismissKeyboardOnFocusLoss: Boolean,
    wasFocused: Boolean,
    isFocused: Boolean,
): Boolean = dismissKeyboardOnFocusLoss && wasFocused && !isFocused

/** [maxLength] が null なら無制限。超える入力は受け付けない。 */
internal fun isWithinMaxLength(length: Int, maxLength: Int?): Boolean =
    maxLength == null || length <= maxLength

/** 絵文字を挿入した後の本文とカーソル位置。 */
data class TextInsertion(val text: String, val cursor: Int)

/**
 * 1行入力と送信ボタン。[onInsertEmoji] を渡すと絵文字ボタンを出し、選んだ絵文字をカーソル位置へ挿入する。
 * 挿入は呼び出し側が行い（下書きの絵文字を保持するため）、結果の本文とカーソル位置を返す。
 *
 * ポスト詳細の返信欄とフィードの簡易投稿欄が共有する。画面固有の差は次の省略可能な引数で表す。
 *
 * @param leadingContent 行の先頭（絵文字ボタンより左）に置くコンテンツ。
 * @param applyNavigationBarsPadding 下端にナビゲーションバーのInsetを足すか。下にボトムナビがある画面では false。
 * @param sendContentDescription 送信ボタンの説明。
 * @param onFocusChanged 入力欄のフォーカス変化の通知。
 * @param dismissKeyboardOnFocusLoss true のとき、入力欄が一度フォーカスされた後にフォーカスを失うとキーボードを閉じる。
 *   送信中に入力欄を無効化するとフォーカスを失いキーボードが閉じてしまうので、この場合は無効化せず読み取り専用にする。
 * @param canSend 送信ボタンの有効条件。null のときは本文が空白でないこと。
 * @param maxLength 入力できる最大文字数。超える入力は受け付けない。
 * @param autoFocus 表示直後にフォーカスしてキーボードを開く。
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
    leadingContent: (@Composable () -> Unit)? = null,
    applyNavigationBarsPadding: Boolean = true,
    sendContentDescription: String = "送信",
    onFocusChanged: ((Boolean) -> Unit)? = null,
    dismissKeyboardOnFocusLoss: Boolean = false,
    canSend: Boolean? = null,
    maxLength: Int? = null,
    autoFocus: Boolean = false,
) {
    DismissKeyboardOnLeave()
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val dismissKeyboard = rememberDismissKeyboard()
    val hideKeyboard = rememberHideKeyboard()
    val softwareKeyboardVisible = rememberSoftwareKeyboardVisible()
    val keyboardController = LocalSoftwareKeyboardController.current
    val textFocusRequester = remember { FocusRequester() }
    // 初回Compositionの未フォーカス通知でキーボード制御をしないよう、一度フォーカスされたかを覚える。
    var wasTextFocused by remember { mutableStateOf(false) }
    val sendEnabled = canSend ?: text.isNotBlank()
    // キーボードを閉じない設定のときは、送信中も入力欄をフォーカスしたまま編集だけを止める。
    val keepFocusWhileSending = dismissKeyboardOnFocusLoss
    // 送信後のクリアなど、外から本文が変わったときだけ追従する。
    LaunchedEffect(text) {
        if (value.text != text) {
            value = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            // 一フレーム描いてからキーボードのアニメーションを始める。
            withFrameNanos { }
            textFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ホームインジケーター分の余白は背景色の内側に取り、キーボード表示中は取らない
                // （キーボードの直上に入力欄を置き、間に隙間を作らない）。
                .then(
                    if (applyNavigationBarsPadding && !softwareKeyboardVisible) {
                        Modifier.navigationBarsPadding()
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leadingContent?.invoke()
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
                        if (isWithinMaxLength(next.text.length, maxLength)) {
                            value = next
                            if (next.text != text) onTextChange(next.text)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(textFocusRequester)
                        // iOSで入力欄を閉じた後にスクロールが重くなり続けるのを防ぐ（詳細は関数のコメント）。
                        .avoidStaleAccessibilityFocus()
                        .onFocusChanged { focus ->
                            if (
                                shouldHideKeyboardOnFocusChange(
                                    dismissKeyboardOnFocusLoss = dismissKeyboardOnFocusLoss,
                                    wasFocused = wasTextFocused,
                                    isFocused = focus.isFocused,
                                )
                            ) {
                                hideKeyboard()
                            }
                            wasTextFocused = focus.isFocused
                            onFocusChanged?.invoke(focus.isFocused)
                        },
                    placeholder = { Text(placeholder) },
                    enabled = enabled && (keepFocusWhileSending || !isSending),
                    readOnly = keepFocusWhileSending && isSending,
                    minLines = 1,
                    maxLines = 4,
                )
                IconButton(
                    onClick = onSend,
                    enabled = enabled && !isSending && sendEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = sendContentDescription,
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
