package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// チャンネルのタブの「関わっているスレッド」の行の控え（docs/chat-joined-timeline-design.md 3.5）。
// 起動し直したときに、タイムラインを読み終えるのを待たずにスレッドの行を出すために使う。

/** 控える件数（新しい順）。 */
internal const val THREAD_CACHE_MAX = 100

/** 一覧の行に出すだけなので、本文は短く切って保存する。 */
internal const val THREAD_CACHE_BODY_MAX = 600

@Serializable
internal data class CachedThreadPost(val event: NostrEvent, val author: String, val body: String) {
    fun toMessage(): GroupMessage = GroupMessage(event, author, body, null, null, emptyList())

    fun trimmed(): CachedThreadPost =
        copy(event = event.copy(content = event.content.take(THREAD_CACHE_BODY_MAX)), body = body.take(THREAD_CACHE_BODY_MAX))

    companion object {
        fun of(message: GroupMessage) = CachedThreadPost(message.event, message.author, message.body)
    }
}

@Serializable
internal data class CachedThread(
    val relayUrl: String,
    val groupId: String,
    val flavor: GroupFlavor = GroupFlavor.Standard,
    val rootId: String,
    val lastActivity: Long,
    val replyCount: Int = 0,
    val root: CachedThreadPost? = null,
    val latestReply: CachedThreadPost? = null,
) {
    /** 控えに入れるのは関わっているスレッドだけなので、自分が関わっているものとして戻す。 */
    fun toEntry(): JoinedTimelineEntry = JoinedTimelineEntry(
        GroupRef(relayUrl, groupId),
        GroupConversation(
            rootId = rootId,
            root = root?.toMessage(),
            latestReply = latestReply?.toMessage(),
            replyCount = replyCount,
            lastActivity = lastActivity,
            involvesMe = true,
        ),
    )

    companion object {
        fun of(entry: JoinedTimelineEntry, flavor: GroupFlavor) = CachedThread(
            relayUrl = entry.ref.relayUrl,
            groupId = entry.ref.groupId,
            flavor = flavor,
            rootId = entry.conversation.rootId,
            lastActivity = entry.conversation.lastActivity,
            replyCount = entry.conversation.replyCount,
            root = entry.conversation.root?.let(CachedThreadPost::of),
            latestReply = entry.conversation.latestReply?.let(CachedThreadPost::of),
        )
    }
}

@Serializable
private data class ChatThreadSnapshot(val threads: List<CachedThread> = emptyList())

internal object ChatThreadCache {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(pubkey: String): List<CachedThread> = decode(LocalSettingsStorage.getString(key(pubkey)))

    suspend fun save(pubkey: String, threads: List<CachedThread>) {
        LocalSettingsStorage.putString(key(pubkey), encode(threads))
    }

    fun encode(threads: List<CachedThread>): String {
        val trimmed = threads
            .sortedByDescending { it.lastActivity }
            .take(THREAD_CACHE_MAX)
            .map { it.copy(root = it.root?.trimmed(), latestReply = it.latestReply?.trimmed()) }
        return json.encodeToString(ChatThreadSnapshot.serializer(), ChatThreadSnapshot(trimmed))
    }

    fun decode(stored: String?): List<CachedThread> =
        stored?.let { runCatching { json.decodeFromString(ChatThreadSnapshot.serializer(), it).threads }.getOrNull() }.orEmpty()

    private fun key(pubkey: String) = "chat.threadSnapshot.$pubkey"
}

/**
 * 今回読めたスレッド [fresh] に、前の結果 [previous] のうち [keep] のリレーのものを足す（同じスレッドは [fresh] を使う）。
 * 新しい順。答えなかったリレー・今回読まなかったリレーの分を残すのに使う。
 */
internal fun mergeThreadEntries(
    fresh: List<JoinedTimelineEntry>,
    previous: List<JoinedTimelineEntry>,
    keep: (relayUrl: String) -> Boolean,
): List<JoinedTimelineEntry> = mergeByRelay(fresh, previous, keep, { it.ref.relayUrl }, { it.key }, { it.conversation.lastActivity })

/** 保存するときの [mergeThreadEntries]。[keep] は今回答えなかった・読まなかったリレー。 */
internal fun mergeCachedThreads(
    fresh: List<CachedThread>,
    previous: List<CachedThread>,
    keep: (relayUrl: String) -> Boolean,
): List<CachedThread> = mergeByRelay(fresh, previous, keep, { it.relayUrl }, { it.toEntry().key }, { it.lastActivity })

/**
 * 起動したときに出す控え。[relayFilter] があればそのリレーのものだけ。ルートか最新の返信の作者をミュートしていれば
 * 出さない。新しい順。
 */
internal fun restorableThreads(threads: List<CachedThread>, relayFilter: String?, muted: Set<String>): List<CachedThread> =
    threads
        .filter { relayFilter == null || it.relayUrl == relayFilter }
        .filter { it.root?.author !in muted && it.latestReply?.author !in muted }
        .sortedByDescending { it.lastActivity }

private fun <T> mergeByRelay(
    fresh: List<T>,
    previous: List<T>,
    keep: (String) -> Boolean,
    relayOf: (T) -> String,
    keyOf: (T) -> String,
    activityOf: (T) -> Long,
): List<T> {
    val freshKeys = fresh.mapTo(mutableSetOf(), keyOf)
    return (fresh + previous.filter { keep(relayOf(it)) && keyOf(it) !in freshKeys })
        .sortedWith(compareByDescending(activityOf).thenBy(keyOf))
}
