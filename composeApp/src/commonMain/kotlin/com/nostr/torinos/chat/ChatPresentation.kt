package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// チャットの見た目に関わる計算（未読の件数、メンバー一覧、時刻と日付区切り、連投のまとめ）。

/** 一覧のバッジで数える未読の上限。これを超えたら「99+」と出す。 */
const val UNREAD_BADGE_MAX = 99

/** 既読位置 [readUntil] より新しい、自分以外の発言の数。Buzz でリレーが署名したものは `actor` タグの人で判断する。 */
fun countUnreadMessages(events: Iterable<NostrEvent>, me: String?, readUntil: Long): Int =
    events
        .filter { it.createdAt > readUntil }
        .distinctBy { it.id }
        .count { event -> (event.tags.firstOrNull { it.firstOrNull() == "actor" }?.getOrNull(1) ?: event.pubkey) != me }

fun unreadBadgeText(count: Int): String = if (count > UNREAD_BADGE_MAX) "$UNREAD_BADGE_MAX+" else count.toString()

/**
 * kind 39002 のメンバー一覧（最新のもの、重複を除いた順）。リレーの鍵が分かればその署名だけを使う。
 * 一覧が無ければ null（リレーが出さない、または非公開）。
 */
fun groupMemberList(events: Iterable<NostrEvent>, groupId: String, relayPubkey: String?): List<String>? =
    events
        .filter { it.kind == GROUP_MEMBERS_KIND && (relayPubkey == null || it.pubkey == relayPubkey) }
        .filter { event -> event.tags.any { it.firstOrNull() == "d" && it.getOrNull(1) == groupId } }
        .maxByOrNull { it.createdAt }
        ?.tags
        ?.filter { it.firstOrNull() == "p" }
        ?.mapNotNull { it.getOrNull(1) }
        ?.distinct()

/** 発言の時刻（`22:05`）。 */
fun chatClockTime(epochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val time = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

fun chatLocalDate(epochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone).date

/** `DayOfWeek` の宣言順（月曜始まり）に合わせた曜日名。 */
private val WEEKDAY_LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

/** 日付区切りの見出し。今日・昨日、今年なら月日と曜日、それより前なら年も付ける。 */
fun chatDayLabel(date: LocalDate, today: LocalDate): String {
    val monthDay = "${date.month.ordinal + 1}月${date.day}日（${WEEKDAY_LABELS[date.dayOfWeek.ordinal]}）"
    return when {
        date == today -> "今日"
        date == today.minus(1, DateTimeUnit.DAY) -> "昨日"
        date.year == today.year -> monthDay
        else -> "${date.year}年$monthDay"
    }
}

/** 一覧の時刻。今日なら `22:05`、昨日なら「昨日」、今年なら「11月15日」、それより前なら「2025年10月」。 */
fun chatListTime(epochSeconds: Long, nowEpochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val date = chatLocalDate(epochSeconds, zone)
    val today = chatLocalDate(nowEpochSeconds, zone)
    return when {
        date >= today -> chatClockTime(epochSeconds, zone)
        date == today.minus(1, DateTimeUnit.DAY) -> "昨日"
        date.year == today.year -> "${date.month.ordinal + 1}月${date.day}日"
        else -> "${date.year}年${date.month.ordinal + 1}月"
    }
}

/** 前の発言とまとめて表示する間隔（秒）。 */
const val CHAT_GROUPING_SECONDS = 300L

sealed interface ChatTimelineItem<out T> {
    data class Day(val date: LocalDate) : ChatTimelineItem<Nothing>
    /** [grouped] は直前の発言と同じ人の続きで、アイコンと名前を省く。 */
    data class Message<T>(val message: T, val grouped: Boolean) : ChatTimelineItem<T>
}

/**
 * 古い順の発言に日付区切りを入れ、同じ人が5分以内に続けた発言をまとめる。
 * 日付が変わったとき、[startsNewGroup]（返信など）のときはまとめない。
 */
fun <T> chatTimeline(
    messages: List<T>,
    zone: TimeZone,
    author: (T) -> String,
    createdAt: (T) -> Long,
    startsNewGroup: (T) -> Boolean,
): List<ChatTimelineItem<T>> {
    val items = ArrayList<ChatTimelineItem<T>>(messages.size + 4)
    var previous: T? = null
    var previousDate: LocalDate? = null
    messages.forEach { message ->
        val date = chatLocalDate(createdAt(message), zone)
        val newDay = date != previousDate
        if (newDay) items += ChatTimelineItem.Day(date)
        val prev = previous
        val grouped = !newDay && prev != null &&
            author(prev) == author(message) &&
            createdAt(message) - createdAt(prev) < CHAT_GROUPING_SECONDS &&
            !startsNewGroup(message)
        items += ChatTimelineItem.Message(message, grouped)
        previous = message
        previousDate = date
    }
    return items
}

/**
 * 一覧の1行目のプレビューに送信者を付ける（「しゅん: こんにちは」）。自分なら「自分: 」。
 * 1対1の DM では相手の名前を付けない。名前がまだ分からなければ付けない（鍵の断片は出さない）。
 */
fun chatPreviewWithSender(preview: String, sender: String?, senderName: String?, me: String?, oneOnOne: Boolean): String = when {
    sender == null -> preview
    sender == me -> "自分: $preview"
    oneOnOne -> preview
    senderName.isNullOrBlank() -> preview
    else -> "$senderName: $preview"
}

/** 招待の期限と回数（`11月25日 18:00 まで · 1回`）。 */
fun inviteSummary(invite: MintedInvite, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val time = Instant.fromEpochSeconds(invite.expiresAt).toLocalDateTime(zone)
    val clock = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    val uses = when {
        invite.maxUses == null -> "回数の制限なし"
        invite.usesRemaining != null && invite.usesRemaining != invite.maxUses -> "残り${invite.usesRemaining}回"
        else -> "${invite.maxUses}回"
    }
    return "${time.month.ordinal + 1}月${time.day}日 $clock まで · $uses"
}
