package com.nostr.torinos.ui.post

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.AppMessageComposer

/**
 * フィードと自分のジャーナルで共用する簡易投稿欄。[AppMessageComposer] に、投稿状態と
 * 先頭の閉じるボタン（▼）を接続するだけの薄いバインディング。独自の入力欄や送信ボタンは持たない。
 * 投稿シートへの展開（△）はFABが受け持つ。
 *
 * @param onClose 閉じるボタンを押したとき。null のときは閉じるボタンを表示しない。
 */
@Composable
internal fun FeedInlinePostComposer(
    state: PostState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: (() -> Unit)?,
    autoFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    AppMessageComposer(
        text = state.text,
        onTextChange = onTextChange,
        onSend = onSend,
        placeholder = "今何してる？",
        modifier = modifier,
        isSending = state.isPosting,
        error = state.error,
        leadingContent = onClose?.let { close ->
            {
                IconButton(
                    onClick = {
                        // フォーカス喪失の処理を通してキーボードを閉じてから、フッターメニューへ戻す。
                        focusManager.clearFocus(force = true)
                        close()
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "投稿欄を閉じる",
                    )
                }
            }
        },
        sendContentDescription = "ポスト",
        dismissKeyboardOnFocusLoss = true,
        canSend = state.canPost,
        maxLength = MAX_POST_CHARS,
        autoFocus = autoFocus,
    )
}

/** 簡易投稿欄からの送信。シートの新規投稿と同じ `post()` を、返信・引用なしのkind 1として呼ぶ。 */
internal fun PostViewModel.postFromFeedInline() {
    val relayUrls = RelayStore.writableRelayUrlsSnapshot()
    if (relayUrls.isEmpty()) {
        // 簡易欄にはリレー選択がないので、シートの「1つ以上選択してください」ではなく設定を促す。
        showError(FEED_INLINE_NO_WRITABLE_RELAY_MESSAGE)
        return
    }
    post(
        replyTarget = null,
        noteContext = NoteContext.Timeline,
        quoteReference = null,
        relayUrls = relayUrls,
    )
}

internal const val FEED_INLINE_NO_WRITABLE_RELAY_MESSAGE =
    "書き込み可能なリレーがありません。リレー設定を確認してください"
