package com.nostr.torinos.ui.notification

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

sealed interface NotificationListEntry {
    val key: String

    data class DateHeader(val date: LocalDate) : NotificationListEntry {
        override val key: String get() = "date:$date"
    }

    /** [items] は新しい順。いいね・リポストは同じ投稿・同じ日ごと、フォローは同じ日ごとにまとめる。 */
    data class Group(
        override val key: String,
        val type: NotificationType,
        val items: List<NotificationItem>,
    ) : NotificationListEntry {
        val latest: NotificationItem get() = items.first()
        val targetEventId: String? get() = latest.targetEventId
    }
}

/** [items] は receivedAt の新しい順で渡す。グループは最新の通知の位置に並ぶ。 */
fun groupNotifications(
    items: List<NotificationItem>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<NotificationListEntry> {
    val groups = LinkedHashMap<String, Pair<LocalDate, MutableList<NotificationItem>>>()
    items.forEach { item ->
        val date = Instant.fromEpochSeconds(item.receivedAt).toLocalDateTime(timeZone).date
        val key = when (item.type) {
            NotificationType.Like,
            NotificationType.Repost -> item.targetEventId?.let { "${item.type}:$it:$date" }
            NotificationType.Follow -> "${item.type}:$date"
            NotificationType.Reply -> null
        } ?: "item:${item.id}"
        groups.getOrPut(key) { date to mutableListOf() }.second += item
    }
    val entries = mutableListOf<NotificationListEntry>()
    var currentDate: LocalDate? = null
    groups.forEach { (key, value) ->
        val (date, groupItems) = value
        if (date != currentDate) {
            entries += NotificationListEntry.DateHeader(date)
            currentDate = date
        }
        entries += NotificationListEntry.Group(key, groupItems.first().type, groupItems)
    }
    return entries
}
