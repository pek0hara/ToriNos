package com.nostr.torinos.chat

import com.nostr.torinos.emoji.CustomEmojiCodeRegex
import com.nostr.torinos.emoji.EMOJI_TAG
import com.nostr.torinos.emoji.customEmojiMap
import com.nostr.torinos.emoji.emojiTags
import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 画面に出すリアクションの集計（本文ごと）。 */
data class GroupReactionSummary(
    val content: String,
    val count: Int,
    val reactedByMe: Boolean,
    /** 自分のリアクションを取り消すときに消すイベント。 */
    val ownReactionId: String?,
    /** カスタム絵文字のリアクション（`:shortcode:` と `emoji` タグ）の画像。 */
    val emojiUrl: String? = null,
    /** リアクションした人（1人1回、最初に付けた順）。 */
    val reactors: List<String> = emptyList(),
)

data class GroupMessage(
    val event: NostrEvent,
    /** 表示する作者。Buzz でリレーが署名したものは `actor` タグの人。 */
    val author: String,
    val body: String,
    /** 標準の NIP-29 の `q` 返信の返信先。 */
    val replyToId: String?,
    val replyTo: GroupMessage?,
    val reactions: List<GroupReactionSummary>,
    /** Buzz で編集（kind 40003）が反映されている。 */
    val edited: Boolean = false,
    /** スレッドの返信の数（Buzz のスレッド、フォーラムのコメント、kind 11 のコメント）。 */
    val threadReplyCount: Int = 0,
    /** Buzz で「スレッドから送信」したコピーの見出し。 */
    val sentFromThreadLabel: String? = null,
    /** 本文の `:shortcode:` を画像にするカスタム絵文字（NIP-30 の `emoji` タグ）。shortcode → URL。 */
    val emojis: Map<String, String> = emptyMap(),
)

data class GroupTimelineSnapshot(
    val messages: List<GroupMessage>,
    val oldestCreatedAt: Long?,
)

data class GroupThread(val root: GroupMessage?, val replies: List<GroupMessage>)

/** タイムラインの1行。トップレベルの投稿（[root]）と、そのスレッドの最新の返信。 */
data class GroupConversation(
    val rootId: String,
    /** まだ読めていなければ null。 */
    val root: GroupMessage?,
    val latestReply: GroupMessage?,
    /** 読めている返信の数。 */
    val replyCount: Int,
    /** ルートと返信の `created_at` の最大。 */
    val lastActivity: Long,
)

private val HEX64 = Regex("^[0-9a-f]{64}$")

/** 対象のメッセージを `e` で指すだけのイベント（リアクション、削除、Buzz の編集）。 */
private val TARGETED_KINDS = setOf(7, 5, GROUP_DELETE_EVENT_KIND, BUZZ_EDIT_KIND)
private val timelineJson = Json { ignoreUnknownKeys = true }

/**
 * 1つのグループのメッセージ、リアクション、削除、編集を集める。古い順に並べる。
 * 削除は、作者本人の kind 5 と、管理者・リレーの kind 9005 だけを反映する。削除が先に届いても効く。
 * 呼び出し側の1つのスレッド（画面のスコープ）で使う。
 *
 * - 標準の NIP-29: kind 9 を時系列に並べ、`q` の返信は返信先を引用して並べる。kind 11 はスレッド。
 * - Buzz: kind 9 / 40002 のうちトップレベルだけを並べ、`e` の返信はルートの「返信 n件」に数える。
 *   40003 の編集を反映し、リレーが署名したものは `actor` を作者にする。45001 はフォーラム投稿。
 */
class GroupTimeline(
    private val groupId: String,
    private val ownPubkey: String?,
    private val flavor: GroupFlavor = GroupFlavor.Standard,
    private val relayPubkey: String? = null,
) {
    private val messages = mutableMapOf<String, NostrEvent>()
    private val threadItems = mutableMapOf<String, NostrEvent>()
    private val reactions = mutableMapOf<String, NostrEvent>()
    private val deletions = mutableMapOf<String, NostrEvent>()
    private val edits = mutableMapOf<String, NostrEvent>()
    private var moderators: Set<String> = emptySet()

    private val chatKinds = if (flavor == GroupFlavor.Buzz) setOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND) else setOf(GROUP_CHAT_KIND)
    private val postKind = if (flavor == GroupFlavor.Buzz) BUZZ_FORUM_POST_KIND else GROUP_THREAD_KIND
    private val commentKind = if (flavor == GroupFlavor.Buzz) BUZZ_FORUM_COMMENT_KIND else GROUP_COMMENT_KIND

    fun setModerators(pubkeys: Set<String>) {
        moderators = pubkeys
    }

    fun add(events: Iterable<NostrEvent>) {
        events.forEach { event ->
            if (!belongsToGroup(event)) return@forEach
            when (event.kind) {
                in chatKinds -> messages[event.id] = event
                postKind, commentKind -> threadItems[event.id] = event
                7 -> reactions[event.id] = event
                5, GROUP_DELETE_EVENT_KIND -> deletions[event.id] = event
                BUZZ_EDIT_KIND -> if (flavor == GroupFlavor.Buzz) edits[event.id] = event
            }
        }
    }

    /**
     * このグループのイベントか。発言は `h` タグが要る。
     * リアクション・削除・編集は `h` タグが無くても受け入れる（Buzz のリレーは `h` の無いリアクションも
     * チャンネルのものとして `#h` の読み取りに返す。対象のメッセージにしか効かないので、ほかのグループには混ざらない）。
     * ほかのグループの `h` が付いているものは捨てる。
     */
    private fun belongsToGroup(event: NostrEvent): Boolean {
        val groups = event.tags.filter { it.firstOrNull() == "h" }.mapNotNull { it.getOrNull(1) }
        if (groupId in groups) return true
        return groups.isEmpty() && event.kind in TARGETED_KINDS
    }

    /** [ids] のうち、[before] 秒より前に作られたもの。 */
    fun eventsCreatedBefore(ids: Set<String>, before: Long): Set<String> =
        listOf(messages, threadItems, reactions, deletions, edits)
            .flatMap { it.values }
            .filter { it.id in ids && it.createdAt < before }
            .mapTo(mutableSetOf()) { it.id }

    /** 前回の控えから流し込んだイベントのうち、今のリレーの答えとつながらないものを外す。 */
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        listOf(messages, threadItems, reactions, deletions, edits).forEach { it.keys.removeAll(ids) }
    }

    fun snapshot(): GroupTimelineSnapshot {
        val deleted = deletedTargets()
        val visible = messages.values.filter { it.id !in deleted }
        val topLevel = if (flavor == GroupFlavor.Buzz) visible.filter { buzzThreadRootId(it) == null } else visible
        val replyCounts = threadReplyCounts(deleted)
        val reactionsByTarget = reactionsByTarget(deleted)
        val base = topLevel.sortedWith(eventOrder).associate { event ->
            event.id to toMessage(event, deleted, reactionsByTarget, replyCounts)
        }
        val withReplies = base.values.map { message -> message.copy(replyTo = message.replyToId?.let(base::get)) }
        return GroupTimelineSnapshot(withReplies, messages.values.minOfOrNull { it.createdAt })
    }

    /** スレッド（Buzz のスレッド、フォーラムの投稿、kind 11）のルートと返信を古い順に。 */
    fun thread(rootId: String): GroupThread {
        val deleted = deletedTargets()
        val reactionsByTarget = reactionsByTarget(deleted)
        val all = (messages.values + threadItems.values).filter { it.id !in deleted }
        val root = all.firstOrNull { it.id == rootId }?.let { toMessage(it, deleted, reactionsByTarget, emptyMap()) }
        val replies = all
            .filter { it.id != rootId && threadRootOf(it) == rootId }
            .sortedWith(eventOrder)
            .map { toMessage(it, deleted, reactionsByTarget, emptyMap()) }
        return GroupThread(root, replies)
    }

    /** スレッドのルートと返信の ID（削除されたものも含む。リアクションなどを発言を指定して読むため）。 */
    fun threadEventIds(rootId: String): List<String> =
        (messages.values + threadItems.values)
            .filter { it.id == rootId || threadRootOf(it) == rootId }
            .map { it.id }
            .distinct()

    /** フォーラム投稿（Buzz の 45001、標準の kind 11）を最終活動の新しい順に。 */
    fun forumPosts(): List<GroupMessage> {
        val deleted = deletedTargets()
        val reactionsByTarget = reactionsByTarget(deleted)
        val replyCounts = threadReplyCounts(deleted)
        val visibleItems = threadItems.values.filter { it.id !in deleted }
        val lastActivity = visibleItems.groupBy { threadRootOf(it) ?: it.id }.mapValues { (_, items) -> items.maxOf { it.createdAt } }
        return visibleItems
            .filter { it.kind == postKind }
            .sortedWith(compareByDescending<NostrEvent> { lastActivity[it.id] ?: it.createdAt }.thenBy { it.id })
            .map { toMessage(it, deleted, reactionsByTarget, replyCounts) }
    }

    /**
     * 会話（トップレベルの投稿とそのスレッド）を、最後の動きの新しい順に（docs/chat-joined-timeline-design.md 2章）。
     * ルートが削除された会話、ルートが [muted] の人の会話は出さない。[muted] の人の返信は数えない。
     */
    fun conversations(muted: Set<String> = emptySet()): List<GroupConversation> {
        val deleted = deletedTargets()
        val reactionsByTarget = reactionsByTarget(deleted)
        val visible = (messages.values + threadItems.values).filter { it.id !in deleted }
        val repliesByRoot = mutableMapOf<String, MutableList<NostrEvent>>()
        val roots = mutableListOf<NostrEvent>()
        visible.forEach { event ->
            val rootId = threadRootOf(event)
            when {
                rootId != null -> repliesByRoot.getOrPut(rootId) { mutableListOf() }.add(event)
                // 標準の kind 1111 でルートの分からないものは会話にしない。
                event.kind == commentKind -> Unit
                else -> roots.add(event)
            }
        }
        val rootsById = roots.associateBy { it.id }
        fun message(event: NostrEvent) = toMessage(event, deleted, reactionsByTarget, emptyMap())
        val withRoot = roots.mapNotNull { root ->
            val base = message(root)
            if (base.author in muted) return@mapNotNull null
            // 標準の `q` 返信は、返信先が読めていれば引用として付ける。
            val rootMessage = base.replyToId?.let(rootsById::get)?.let { base.copy(replyTo = message(it)) } ?: base
            conversation(root.id, rootMessage, repliesByRoot[root.id].orEmpty(), muted, ::message)
        }
        val orphans = repliesByRoot
            .filterKeys { it !in rootsById && it !in deleted }
            .mapNotNull { (rootId, replies) -> conversation(rootId, null, replies, muted, ::message) }
        return (withRoot + orphans).sortedWith(compareByDescending<GroupConversation> { it.lastActivity }.thenBy { it.rootId })
    }

    private fun conversation(
        rootId: String,
        root: GroupMessage?,
        replyEvents: List<NostrEvent>,
        muted: Set<String>,
        message: (NostrEvent) -> GroupMessage,
    ): GroupConversation? {
        val replies = replyEvents.sortedWith(eventOrder).map(message).filter { it.author !in muted }
        if (root == null && replies.isEmpty()) return null
        val latest = replies.lastOrNull()
        val activity = maxOf(root?.event?.createdAt ?: Long.MIN_VALUE, latest?.event?.createdAt ?: Long.MIN_VALUE)
        return GroupConversation(rootId, root, latest, replies.size, activity)
    }

    private val eventOrder = compareBy<NostrEvent> { it.createdAt }.thenBy { it.id }

    private fun threadRootOf(event: NostrEvent): String? = groupThreadRootId(event, flavor)

    private fun threadReplyCounts(deleted: Set<String>): Map<String, Int> =
        (messages.values + threadItems.values)
            .filter { it.id !in deleted }
            .mapNotNull { threadRootOf(it) }
            .groupingBy { it }
            .eachCount()

    private fun reactionsByTarget(deleted: Set<String>): Map<String?, List<NostrEvent>> =
        reactions.values
            .filter { it.id !in deleted }
            .groupBy { reaction -> reaction.tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1) }

    private fun toMessage(
        event: NostrEvent,
        deleted: Set<String>,
        reactionsByTarget: Map<String?, List<NostrEvent>>,
        replyCounts: Map<String, Int>,
    ): GroupMessage {
        val edit = if (flavor == GroupFlavor.Buzz) latestEdit(event, deleted) else null
        val rawBody = edit?.content ?: event.content
        val body = when {
            flavor == GroupFlavor.Standard && event.kind == GROUP_CHAT_KIND -> groupMessageBody(event)
            event.kind == BUZZ_AGENT_MESSAGE_KIND -> agentBody(rawBody)
            else -> rawBody
        }
        return GroupMessage(
            event = event,
            author = authorOf(event),
            body = body,
            replyToId = if (flavor == GroupFlavor.Standard) groupReplyTargetId(event) else null,
            replyTo = null,
            reactions = summarize(reactionsByTarget[event.id].orEmpty()),
            edited = edit != null,
            threadReplyCount = replyCounts[event.id] ?: 0,
            sentFromThreadLabel = event.tags.firstOrNull { it.firstOrNull() == "buzz:sent-from-thread" }?.let { it.getOrNull(2) ?: "" },
            emojis = messageEmojis(event, edit),
        )
    }

    /**
     * 本文のカスタム絵文字。Buzz は編集に `emoji` タグがあればそちらを使い（`fold.ts`）、
     * shortcode は大文字小文字を区別しないので小文字にそろえる。
     */
    private fun messageEmojis(event: NostrEvent, edit: NostrEvent?): Map<String, String> {
        if (flavor == GroupFlavor.Standard) return event.tags.customEmojiMap()
        val source = edit?.takeIf { e -> e.tags.any { it.firstOrNull() == EMOJI_TAG } } ?: event
        val result = linkedMapOf<String, String>()
        source.tags.emojiTags().forEach { result.getOrPut(it.shortcode.lowercase()) { it.imageUrl } }
        return result
    }

    /** 作者本人の、削除されていない最新の編集（`created_at` の新しい順、同時刻は ID の小さい順）。 */
    private fun latestEdit(event: NostrEvent, deleted: Set<String>): NostrEvent? =
        edits.values
            .filter { it.id !in deleted && it.pubkey == event.pubkey }
            .filter { edit -> edit.tags.any { it.firstOrNull() == "e" && it.getOrNull(1) == event.id } }
            .minWithOrNull(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })

    private fun authorOf(event: NostrEvent): String = groupMessageAuthor(event, flavor, relayPubkey)

    private fun agentBody(content: String): String = runCatching {
        ((timelineJson.parseToJsonElement(content) as JsonObject)["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull() ?: content

    private fun deletedTargets(): Set<String> {
        val authorOf = (messages.values + threadItems.values + reactions.values + edits.values).associate { it.id to it.pubkey }
        return deletions.values.flatMap { deletion ->
            val targets = deletion.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }
            when (deletion.kind) {
                GROUP_DELETE_EVENT_KIND -> if (deletion.pubkey in moderators) targets else emptyList()
                // 作者本人の削除だけ。対象をまだ受け取っていないときは、届いたときに判定する（作者が分かるまで保留）。
                else -> targets.filter { authorOf[it] == deletion.pubkey }
            }
        }.toSet()
    }

    /** 本文と画像の組（[groupReactionKey]）ごとに集計する。同じ shortcode でも画像が違えば別に数える。 */
    private fun summarize(events: List<NostrEvent>): List<GroupReactionSummary> =
        events
            .map { event -> event.content.ifEmpty { "+" }.let { content -> Triple(content, reactionEmojiUrl(content, event), event) } }
            .groupBy { (content, url, _) -> groupReactionKey(content, url) }
            .map { (_, group) ->
                val (content, url, _) = group.first()
                val reactions = group.map { it.third }
                val own = reactions.firstOrNull { it.pubkey == ownPubkey }
                val reactors = reactions.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id }).map { it.pubkey }.distinct()
                GroupReactionSummary(
                    content = content,
                    count = reactors.size,
                    reactedByMe = own != null,
                    ownReactionId = own?.id,
                    emojiUrl = url,
                    reactors = reactors,
                )
            }
            .sortedWith(compareByDescending<GroupReactionSummary> { it.count }.thenBy { it.content }.thenBy { it.emojiUrl.orEmpty() })
}

/** `:shortcode:` のリアクションの、同じ shortcode の `emoji` タグの画像。 */
private fun reactionEmojiUrl(content: String, event: NostrEvent): String? {
    val shortcode = CustomEmojiCodeRegex.matchEntire(content)?.groupValues?.get(1) ?: return null
    return event.tags.emojiTags().firstOrNull { it.shortcode.equals(shortcode, ignoreCase = true) }?.imageUrl?.trim()
}

/**
 * 過去のページを遡る位置。画面に出るかどうかに関係なく、受け取ったイベントの最古を起点にする
 * （1ページ分がスレッドの返信やミュートした人の発言だけでも、先へ進めるため）。
 */
class OlderPager(private val pageSize: Int) {
    private val ids = mutableSetOf<String>()
    private var oldest: Long? = null
    /** 同じ秒のイベントが1ページを超えたとき、その秒を飛ばして次を読む。 */
    private var skipOldestSecond = false
    var canLoadOlder: Boolean = true
        private set

    /** 配信や最初の取得で受け取った、ページの対象のイベント。 */
    fun observe(events: Iterable<NostrEvent>) {
        events.forEach { event ->
            if (ids.add(event.id)) oldest = minOf(oldest ?: event.createdAt, event.createdAt)
        }
    }

    /** 次のページの `until`。同じ秒を取りこぼさないよう、最古の秒を含める。まだ何も無ければ null。 */
    fun until(): Long? = oldest?.let { if (skipOldestSecond) it - 1 else it }

    /** 取得したページを取り込み、初めて見たイベントを返す。 */
    fun onPage(events: List<NostrEvent>, allAnswered: Boolean): List<NostrEvent> {
        val fresh = events.filter { it.id !in ids }
        observe(events)
        skipOldestSecond = fresh.isEmpty() && events.size >= pageSize
        // 時間切れなど答えが揃わなかったときは、次にまた試せるようにする。
        canLoadOlder = !(allAnswered && events.size < pageSize)
        return fresh
    }
}

/** スレッドの返信なら、そのルートの ID（Buzz のスレッド・フォーラムのコメント、kind 1111）。トップレベルなら null。 */
fun groupThreadRootId(event: NostrEvent, flavor: GroupFlavor): String? = when {
    event.kind == GROUP_COMMENT_KIND ->
        event.tags.firstOrNull { it.firstOrNull() == "E" }?.getOrNull(1) ?: buzzThreadRootId(event)
    flavor == GroupFlavor.Buzz -> buzzThreadRootId(event)
    else -> null
}

/** 発言を表示する作者。Buzz の `messageAuthor`: リレーが署名したものは `actor`、無ければ先頭タグが `p` ならその人。 */
fun groupMessageAuthor(event: NostrEvent, flavor: GroupFlavor, relayPubkey: String?): String {
    if (flavor != GroupFlavor.Buzz || relayPubkey == null || event.pubkey != relayPubkey) return event.pubkey
    event.tags.firstOrNull { it.firstOrNull() == "actor" && it.getOrNull(1)?.let(HEX64::matches) == true }
        ?.let { return it[1] }
    val first = event.tags.firstOrNull()
    val hasGroupTag = event.tags.any { it.firstOrNull() == "h" }
    return if (first?.firstOrNull() == "p" && first.getOrNull(1)?.let(HEX64::matches) == true && hasGroupTag) first[1] else event.pubkey
}
