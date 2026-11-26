package com.nostr.torinos.chat

/**
 * スレッドの返信の件数の表示。[count] は最新の返信を含む数。
 * 最新の返信をプレビューで出しているなら、残りの件数だけを「他 n件」と出し、残りがなければ出さない
 * （プレビューそのものからスレッドを開く）。
 */
fun threadReplyCountLabel(count: Int, previewShown: Boolean): String? {
    val rest = if (previewShown) count - 1 else count
    if (rest <= 0) return null
    return if (previewShown) "💬 他 ${rest}件の返信 ▸" else "💬 返信 ${rest}件 ▸"
}
