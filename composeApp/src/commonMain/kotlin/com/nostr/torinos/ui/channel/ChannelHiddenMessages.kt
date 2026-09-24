package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * kind 43 Hide Message(第7.1節、第16.15節)。自分が発行した kind 43 の対象メッセージを非表示にし、
 * その kind 43 を自分が kind 5 で削除していれば表示に戻す。他人の kind 43 は初期実装では反映しない。
 */
internal object ChannelHiddenMessages {
    const val HIDE_KIND = 43
    const val DELETION_KIND = 5

    /** 非表示にするメッセージ ID → それを隠した kind 43 の ID。 */
    fun hiddenTargets(
        ownPubkey: String,
        hideEvents: Collection<NostrEvent>,
        deletions: Collection<NostrEvent>,
    ): Map<String, String> {
        val deletedIds = deletions.asSequence()
            .filter { it.kind == DELETION_KIND && it.pubkey == ownPubkey }
            .flatMap { deletion -> deletion.tags.asSequence().filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
            .toSet()
        return hideEvents.asSequence()
            .filter { it.kind == HIDE_KIND && it.pubkey == ownPubkey && it.id !in deletedIds }
            .sortedByDescending { it.createdAt }
            .mapNotNull { hide -> targetOf(hide)?.let { it to hide.id } }
            .distinctBy { it.first }
            .toMap()
    }

    fun targetOf(hide: NostrEvent): String? =
        hide.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)?.takeIf { it.isNotBlank() }

    fun hideTags(messageId: String, relayHint: String?): List<List<String>> =
        listOf(buildList { add("e"); add(messageId); relayHint?.takeIf(String::isNotBlank)?.let(::add) })

    /** 非表示の取り消し。対象 kind を `k` タグで示す(kind 5 の既存方針)。 */
    fun unhideTags(hideEventId: String): List<List<String>> = unhideTags(listOf(hideEventId))

    /** まとめて取り消す。1件の kind 5 に複数の `e` タグを並べる(NIP-09)。 */
    fun unhideTags(hideEventIds: Collection<String>): List<List<String>> =
        hideEventIds.distinct().map { listOf("e", it) } + listOf(listOf("k", HIDE_KIND.toString()))

    const val HIDE_CONTENT = "{\"reason\":\"\"}"
}

/**
 * アカウントごとの非表示メッセージ ID(メモリのみ)。チャンネル画面が更新し、そのチャンネルから開いた
 * スレッド画面の返信一覧でも同じ非表示を効かせる(Loop 9)。
 */
internal object ChannelHiddenMessageStore {
    private val lock = SynchronousLock()
    private val flows = mutableMapOf<String, MutableStateFlow<Set<String>>>()

    fun observe(ownerPubkey: String): StateFlow<Set<String>> = flowFor(ownerPubkey).asStateFlow()

    fun update(ownerPubkey: String, hiddenMessageIds: Set<String>) {
        flowFor(ownerPubkey).value = hiddenMessageIds
    }

    private fun flowFor(ownerPubkey: String): MutableStateFlow<Set<String>> =
        lock.withLock { flows.getOrPut(ownerPubkey) { MutableStateFlow(emptySet()) } }
}
