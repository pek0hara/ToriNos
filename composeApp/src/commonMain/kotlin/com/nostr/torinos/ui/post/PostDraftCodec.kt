package com.nostr.torinos.ui.post

import com.nostr.torinos.emoji.emojiTags
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** NIP-37内側の未署名イベント。編集情報はJSONのtorinos拡張に分離する。 */
@Serializable
internal data class PostDraftEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

internal fun postDraftEvent(memo: PostMemoPayload, pubkey: String): PostDraftEvent {
    require(memo.noteKind in POST_DRAFT_KINDS)
    val context = memo.channelId?.let(NoteContext::Channel) ?: NoteContext.Timeline
    val reply = memo.toPostMemoData().restoreReplyTarget(context)
    val images = memo.imageUrls.mapIndexed { index, url ->
        ImageAttachment(index, null, url, false, memo.imageMetadata.firstOrNull { it.url == url })
    }
    val composed = composeNoteContent(memo.text, images, memo.customEmojis, quotes = memo.quotes)
    val tags = buildList {
        addAll(reply?.tags() ?: context.replyTags(null, null))
        addAll(composed?.tags.orEmpty())
        addAll(contentWarningTags(memo.hasContentWarning))
        add(listOf("client", "ToriNos"))
    }
    return PostDraftEvent(pubkey, memo.updatedAt, memo.noteKind, tags, composed?.content.orEmpty())
}

internal fun encodePostDraft(memo: PostMemoPayload, pubkey: String): String {
    val event = memoJson.encodeToJsonElement(postDraftEvent(memo, pubkey)).jsonObject
    return JsonObject(event + ("torinos" to memoJson.encodeToJsonElement(memo))).toString()
}

/** 復号済みの内容。標準形式の検証失敗時に旧形式へフォールバックしない。 */
internal fun decodePostDraft(plaintext: String, wrapper: NostrEvent): PostMemoPayload? = runCatching {
    val objectValue = memoJson.parseToJsonElement(plaintext).jsonObject
    if (objectValue.keys.none { it in setOf("kind", "pubkey", "created_at", "tags", "content") }) {
        // 旧ToriNos形式。必須updatedAtを持つもののみ許容。
        return@runCatching memoJson.decodeFromJsonElement<PostMemoPayload>(objectValue)
            .takeIf { it.noteKind in POST_DRAFT_KINDS && wrapper.tags.none { tag -> tag.firstOrNull() == "k" } }
    }
    val event = memoJson.decodeFromJsonElement<PostDraftEvent>(objectValue)
    if (event.pubkey != wrapper.pubkey || event.kind !in POST_DRAFT_KINDS ||
        wrapper.tags.firstOrNull { it.firstOrNull() == "k" }?.getOrNull(1) != event.kind.toString()) return@runCatching null
    val extension = objectValue["torinos"]?.let { value ->
        runCatching { memoJson.decodeFromJsonElement<PostMemoPayload>(value) }.getOrNull()
    }?.takeIf { memo ->
        runCatching { postDraftEvent(memo, event.pubkey) == event }.getOrDefault(false)
    }
    extension ?: event.toMemo()
}.getOrNull()

private fun PostDraftEvent.toMemo(): PostMemoPayload {
    fun tag(name: String) = tags.firstOrNull { it.firstOrNull() == name }
    val eTags = tags.filter { it.firstOrNull() == "e" }
    val root = if (kind == COMMENT_EVENT_KIND) tag("E") else eTags.firstOrNull { it.getOrNull(3) == "root" } ?: eTags.firstOrNull()
    val parent = if (kind == COMMENT_EVENT_KIND) tag("e") else eTags.firstOrNull { it.getOrNull(3) == "reply" }
        ?: if (kind == 1) eTags.lastOrNull() else null
    val channel = if (kind == 42) root?.getOrNull(1) else null
    return PostMemoPayload(
        text = content,
        customEmojis = tags.emojiTags(),
        hasContentWarning = tag("content-warning") != null,
        noteKind = kind,
        channelId = channel,
        replyToId = parent?.getOrNull(1),
        replyToPubkey = if (kind == COMMENT_EVENT_KIND) tag("p")?.getOrNull(1) ?: parent?.getOrNull(3)
            else parent?.getOrNull(4) ?: tag("p")?.getOrNull(1),
        replyParentKind = if (kind == COMMENT_EVENT_KIND) tag("k")?.getOrNull(1)?.toIntOrNull() else if (parent != null) kind else null,
        replyRootId = if (kind == COMMENT_EVENT_KIND) root?.getOrNull(1) ?: tag("A")?.let { "" }
            else if (kind == 1) root?.getOrNull(1) else null,
        replyRootKind = if (kind == COMMENT_EVENT_KIND) tag("K")?.getOrNull(1)?.toIntOrNull() else if (root != null && kind == 1) 1 else null,
        replyRootPubkey = if (kind == COMMENT_EVENT_KIND) tag("P")?.getOrNull(1) ?: root?.getOrNull(3) else root?.getOrNull(4),
        replyRootRelayUrl = (root ?: tag("A"))?.getOrNull(2)?.takeIf { it.isNotBlank() },
        replyParentRelayUrl = parent?.getOrNull(2)?.takeIf { it.isNotBlank() },
        replyRootPubkeyRelayUrl = tag("P")?.getOrNull(2),
        replyParentPubkeyRelayUrl = tag("p")?.getOrNull(2),
        replyRootAddress = tag("A")?.getOrNull(1),
        replyParentAddress = tag("a")?.getOrNull(1),
        updatedAt = createdAt,
    )
}
private val POST_DRAFT_KINDS = setOf(1, 42, COMMENT_EVENT_KIND)
