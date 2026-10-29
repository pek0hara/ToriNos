package com.nostr.torinos.ui.post

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.isFullEventId

/** 投稿シートの「返信先」の欄に出す本文。 */
internal sealed interface ReplyPreview {
    data object Loading : ReplyPreview
    data class Loaded(val text: String) : ReplyPreview
    data object Unavailable : ReplyPreview
}

/** 返信先の欄の本文をどこから得るか。 */
internal sealed interface ReplyPreviewSource {
    /** 返信を開いた画面から渡された本文。 */
    data class Given(val text: String) : ReplyPreviewSource
    /** 下書きから戻した返信先。本文は保存していないので取得する。 */
    data class Fetch(val parent: ReplyEventReference) : ReplyPreviewSource
    data object None : ReplyPreviewSource
}

/**
 * 下書きを選ぶと返信先もその下書きのものになるので、呼び出し元の本文（別の投稿のもの）は使わない。
 */
internal fun replyPreviewSource(
    selectedDraft: PostMemoData?,
    replyToPreview: String?,
    replyTarget: ReplyTarget?,
): ReplyPreviewSource {
    val given = replyToPreview?.takeIf { selectedDraft == null && it.isNotBlank() }
    return when {
        given != null -> ReplyPreviewSource.Given(given)
        replyTarget != null -> ReplyPreviewSource.Fetch(replyTarget.parent)
        else -> ReplyPreviewSource.None
    }
}

/**
 * 返信先の投稿を取得して本文を返す。有効なリレーで見つからなければ、返信先に付いたリレーにも問い合わせる。
 * [fetch] は署名と ID を検証した投稿だけを渡すこと（[com.nostr.torinos.network.EventByIdFetcher]）。
 */
internal suspend fun loadReplyPreview(
    parent: ReplyEventReference,
    fetch: suspend (ids: Set<String>, target: RelayTarget, onEvent: (NostrEvent) -> Unit) -> Unit,
): ReplyPreview {
    if (!isFullEventId(parent.id)) return ReplyPreview.Unavailable
    var found: NostrEvent? = null
    val onEvent: (NostrEvent) -> Unit = { event -> if (event.id == parent.id) found = event }
    fetch(setOf(parent.id), RelayTarget.AllEnabled, onEvent)
    val hint = parent.relayUrl?.takeIf { it.startsWith("wss://") }
    if (found == null && hint != null) fetch(setOf(parent.id), RelayTarget.Explicit(setOf(hint)), onEvent)
    val event = found ?: return ReplyPreview.Unavailable
    return ReplyPreview.Loaded(event.content.ifBlank { "投稿 ${event.id.take(8)}" })
}
