package com.nostr.torinos.ui.components

import com.nostr.torinos.emoji.EmojiPreferences
import com.nostr.torinos.model.ReactionOption

/** ハートボタンをタップしたときの操作。 */
internal sealed interface HeartTapAction {
    /** いいね（`+`）を送る。 */
    data object Like : HeartTapAction

    /** デフォルトの絵文字リアクションを送る。 */
    data class React(val option: ReactionOption) : HeartTapAction

    /** 自分のいいねを取り消す。 */
    data object Unlike : HeartTapAction

    /** 自分の絵文字リアクションを取り消す。 */
    data class Unreact(val option: ReactionOption) : HeartTapAction

    data object None : HeartTapAction
}

/**
 * 自分のリアクションがなければデフォルト（[defaultReaction]、`null` はいいね）を送り、あれば取り消す。
 * 絵文字リアクション済みでどの絵文字か分からない（[ownEmojiReaction] が `null`）ときは何もしない。
 */
internal fun heartTapAction(
    isLiked: Boolean,
    hasOwnEmojiReaction: Boolean,
    ownEmojiReaction: ReactionOption?,
    defaultReaction: ReactionOption?,
): HeartTapAction = when {
    isLiked -> HeartTapAction.Unlike
    ownEmojiReaction != null -> HeartTapAction.Unreact(ownEmojiReaction)
    hasOwnEmojiReaction -> HeartTapAction.None
    defaultReaction != null -> HeartTapAction.React(defaultReaction)
    else -> HeartTapAction.Like
}

/** デフォルトのリアクション。カスタム絵文字なら、今登録されているセットのアドレスを付ける。`null` はいいね。 */
internal fun EmojiPreferences.defaultReactionOption(): ReactionOption? =
    defaultReaction?.toReactionOption(::setAddressOf)

/** 読み上げ用の説明。デフォルトが絵文字のときだけ、何を送るか分かるようにする。 */
internal fun ReactionOption?.heartContentDescription(): String = when (this) {
    null -> "いいね"
    is ReactionOption.Unicode -> "${value}でリアクション"
    is ReactionOption.Custom -> ":${shortcode}: でリアクション"
}
