package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiPreferences
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.RecentReaction
import com.nostr.torinos.emoji.normalizeShortcode
import com.nostr.torinos.model.ReactionOption

/** 現在のアカウントのカスタム絵文字設定。未ログインなら空。 */
@Composable
internal fun rememberEmojiPreferences(): EmojiPreferences {
    val repository = LocalAccountSession.current?.customEmojis
    return repository?.preferences?.collectAsState()?.value ?: remember { EmojiPreferences() }
}

/**
 * 現在のアカウントのデフォルトのリアクション。`null` はいいね。
 * 投稿ごとに読むため、絵文字設定のほかの変更（最近使った等）では読み手を再描画しない。
 */
@Composable
internal fun rememberDefaultReaction(): State<ReactionOption?> {
    val preferences = rememberEmojiPreferencesState()
    return remember(preferences) { derivedStateOf { preferences.value.defaultReactionOption() } }
}

@Composable
private fun rememberEmojiPreferencesState(): State<EmojiPreferences> {
    val repository = LocalAccountSession.current?.customEmojis
    return repository?.preferences?.collectAsState() ?: remember { mutableStateOf(EmojiPreferences()) }
}

/** デフォルトのリアクションを変える。`null` はいいね。 */
internal fun AccountSession?.setDefaultReaction(option: ReactionOption?) {
    this?.customEmojis?.setDefaultReaction(option?.toRecentReaction())
}

internal fun ReactionOption.toRecentReaction(): RecentReaction = when (this) {
    is ReactionOption.Unicode -> RecentReaction.Unicode(value)
    is ReactionOption.Custom -> RecentReaction.Custom(CustomEmoji(shortcode, imageUrl))
}

internal fun RecentReaction.toReactionOption(
    setAddressOf: (CustomEmoji) -> EmojiSetAddress? = { null },
): ReactionOption = when (this) {
    is RecentReaction.Unicode -> ReactionOption.Unicode(value)
    is RecentReaction.Custom -> ReactionOption.Custom(emoji.shortcode, emoji.imageUrl, setAddressOf(emoji))
}

/** リアクションや絵文字の入力に使った項目を「最近使った」に記録する。 */
internal fun AccountSession?.recordReactionUse(option: ReactionOption) {
    this?.customEmojis?.recordUse(option.toRecentReaction())
}

/** カスタム絵文字をタップしたときに開く対象。 */
data class CustomEmojiOpenRequest(
    val shortcode: String,
    val imageUrl: String = "",
    val setAddress: EmojiSetAddress? = null,
) {
    companion object {
        fun of(shortcode: String, imageUrl: String = "", setAddress: EmojiSetAddress? = null) =
            CustomEmojiOpenRequest(normalizeShortcode(shortcode), imageUrl.trim(), setAddress)
    }
}

/** カスタム絵文字の設定画面を開く。アプリのナビゲーションが提供する。 */
val LocalCustomEmojiNavigator = compositionLocalOf<(CustomEmojiOpenRequest) -> Unit> { {} }

/**
 * カスタム絵文字の画像を読むときのヘッダー（URL → ヘッダー）。null を返す URL は読まずに `:shortcode:` の文字を出す
 * （Buzz の `/media/` で、署名の許可待ち・失敗のもの）。既定はそのまま読む。グループの画面が差し替える。
 */
val LocalEmojiImageHeaders = compositionLocalOf<(String) -> Map<String, String>?> { { emptyMap() } }

/** リアクションやピッカーのカスタム絵文字の画像。[LocalEmojiImageHeaders] に従って認証を付ける。 */
@Composable
internal fun CustomEmojiImage(
    url: String,
    shortcode: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxDecodeSizePx: Int? = null,
) {
    val headers = LocalEmojiImageHeaders.current(url)
    if (headers == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = ":${normalizeShortcode(shortcode)}:",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        return
    }
    NetworkImage(
        url = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        maxDecodeSizePx = maxDecodeSizePx,
        headers = headers,
        modifier = modifier,
    )
}
