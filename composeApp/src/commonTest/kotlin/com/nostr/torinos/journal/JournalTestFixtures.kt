package com.nostr.torinos.journal

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

internal const val OWNER = "owner-pubkey"
internal const val OTHER = "other-pubkey"

internal val SelfOwner = JournalOwner(OWNER, isSelf = true)
internal val UserOwner = JournalOwner(OWNER, isSelf = false)

/** 2026-09-15 12:00 UTC に固定した時計。 */
internal fun utcClock(now: String = "2026-09-15T12:00:00Z"): JournalClock =
    JournalClock(TimeZone.UTC) { Instant.parse(now) }

/** 64桁の16進ID。参照先の取得はこの形式だけを受け付ける。 */
internal fun hexId(n: Int): String = n.toString(16).padStart(64, '0')

internal fun epochOf(date: LocalDate, hour: Int = 12): Long =
    utcClock().startOfDay(date) + hour * 3_600L

internal fun event(
    id: String,
    kind: Int,
    pubkey: String = OWNER,
    createdAt: Long = epochOf(LocalDate(2026, 9, 15)),
    tags: List<List<String>> = emptyList(),
    content: String = "",
) = NostrEvent(
    id = id,
    pubkey = pubkey,
    createdAt = createdAt,
    kind = kind,
    tags = tags,
    content = content,
    sig = "sig",
)

internal fun post(id: String, createdAt: Long = epochOf(LocalDate(2026, 9, 15)), pubkey: String = OWNER) =
    event(id, kind = 1, pubkey = pubkey, createdAt = createdAt)

internal fun reply(id: String, parentId: String, createdAt: Long = epochOf(LocalDate(2026, 9, 15)), pubkey: String = OWNER) =
    event(id, kind = 1, pubkey = pubkey, createdAt = createdAt, tags = listOf(listOf("e", parentId, "", "reply")))

internal fun reaction(
    id: String,
    targetId: String,
    pubkey: String = OTHER,
    targetAuthor: String = OWNER,
    content: String = "+",
    createdAt: Long = epochOf(LocalDate(2026, 9, 15)),
) = event(
    id,
    kind = 7,
    pubkey = pubkey,
    createdAt = createdAt,
    tags = listOf(listOf("e", targetId), listOf("p", targetAuthor)),
    content = content,
)

internal fun supportedComment(id: String, rootId: String, pubkey: String = OWNER) = event(
    id,
    kind = COMMENT_EVENT_KIND,
    pubkey = pubkey,
    tags = listOf(
        listOf("E", rootId, "", OTHER),
        listOf("K", "1"),
        listOf("P", OTHER),
        listOf("e", rootId, "", OTHER),
        listOf("k", "1"),
        listOf("p", OTHER),
    ),
)

/** `ReactionEventStore`を使わず`p`タグだけで宛先を判定する。 */
internal val byPTag: (NostrEvent, String) -> Boolean = { event, pubkey ->
    event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkey }
}

internal fun activity(event: NostrEvent, owner: JournalOwner = SelfOwner, clock: JournalClock = utcClock()) =
    JournalActivity(
        event = event,
        date = clock.dateOf(event.createdAt),
        kinds = JournalActivityClassifier.classify(event, owner, byPTag),
    )
