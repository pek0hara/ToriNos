package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

internal fun ReactionOption.toRecentReaction(): RecentReaction = when (this) {
    is ReactionOption.Unicode -> RecentReaction.Unicode(value)
    is ReactionOption.Custom -> RecentReaction.Custom(CustomEmoji(shortcode, imageUrl))
}

internal fun RecentReaction.toReactionOption(): ReactionOption = when (this) {
    is RecentReaction.Unicode -> ReactionOption.Unicode(value)
    is RecentReaction.Custom -> ReactionOption.Custom(emoji.shortcode, emoji.imageUrl)
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
