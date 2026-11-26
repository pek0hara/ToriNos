package com.nostr.torinos.ui.notification

import com.nostr.torinos.network.TargetLoadState

/**
 * 返信先・引用先の作者を確かめるまで一覧に出さない通知。キーは通知のイベントID。
 * 呼び出し側（ViewModel）のディスパッチャーだけで触る。
 */
internal class PendingNotifications {
    private class Entry(val item: NotificationItem, val eventIds: Set<String>)

    private val entries = linkedMapOf<String, Entry>()

    val items: List<NotificationItem> get() = entries.values.map { it.item }

    /** 作者を確かめるために取ってくる投稿。 */
    val targetIds: Set<String> get() = entries.values.flatMapTo(linkedSetOf()) { it.eventIds }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun hold(item: NotificationItem, eventIds: Set<String>) {
        entries[item.id] = Entry(item, eventIds)
    }

    fun removeMutedActors(mutedPubkeys: Set<String>) {
        if (mutedPubkeys.isEmpty()) return
        val normalized = mutedPubkeys.mapTo(hashSetOf()) { it.trim().lowercase() }
        entries.values.removeAll { it.item.actorPubkey.trim().lowercase() in normalized }
    }

    /**
     * 決まったものを取り除き、出すものを返す。[decided] が false なら何も決まらなかった。
     * 自分の投稿と分かっている [ownPostIds] を指すものは、取ってくるのを待たずに出す。
     */
    fun settle(
        states: Map<String, TargetLoadState>,
        ownPubkey: String,
        ownPostIds: Set<String>,
    ): Settled {
        if (entries.isEmpty()) return Settled(emptyList(), decided = false)
        val shown = mutableListOf<NotificationItem>()
        var decided = false
        entries.values.toList().forEach { entry ->
            val show = entry.eventIds.any { it in ownPostIds } ||
                (decideByTargetAuthors(entry.eventIds, states, ownPubkey) ?: return@forEach)
            entries.remove(entry.item.id)
            decided = true
            if (show) shown += entry.item
        }
        return Settled(shown, decided)
    }

    data class Settled(val shown: List<NotificationItem>, val decided: Boolean)
}

/** 保存していた通知を今の基準で振り分けた結果。 */
internal data class StoredNotificationTriage(
    val kept: List<NotificationItem>,
    val held: List<Pair<NotificationItem, Set<String>>>,
)

/** 以前の版で保存した、自分に関係のない返信も同じ基準で振り分け直す。関係ないものは捨てる。 */
internal fun triageStoredNotifications(
    items: List<NotificationItem>,
    ownPubkey: String,
    ownPostIds: Set<String>,
): StoredNotificationTriage {
    val kept = mutableListOf<NotificationItem>()
    val held = mutableListOf<Pair<NotificationItem, Set<String>>>()
    items.forEach { item ->
        when (val relevance = item.event?.let { notificationRelevance(it, ownPubkey, ownPostIds) } ?: NotificationRelevance.Relevant) {
            NotificationRelevance.Relevant -> kept += item
            NotificationRelevance.Unrelated -> Unit
            is NotificationRelevance.NeedsTargetAuthor -> held += item to relevance.eventIds
        }
    }
    return StoredNotificationTriage(kept, held)
}
