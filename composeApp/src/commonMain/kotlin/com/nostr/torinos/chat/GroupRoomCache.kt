package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock

/**
 * グループの画面を開き直したとき、リレーの答えを待たずに前回の内容を出すためのメモリ上の控え。
 *
 * 控えるのは、その画面の購読で受け取った(＝検証済みの)イベントだけ。開いたらまず控えを流し込み、
 * そのあと届くリレーの答えで上書きする。アカウントごとに持ち（[ChatAccount.roomCache]）、
 * 別のアカウントが読んだ内容や参加状況を見せない。セッションを終えると消える。
 *
 * [joinedHint] はチャット一覧などが知っている「参加しているか」の見込み。リレーの答えが出るまでの表示だけに使う。
 */
class GroupRoomCache(
    private val maxRooms: Int = 24,
    private val maxEventsPerRoom: Int = 400,
) {
    private val lock = SynchronousLock()
    // accessOrder の LinkedHashMap は共通コードに無いので、取り出しのたびに入れ直して順番を保つ。
    private val rooms = LinkedHashMap<String, Map<String, NostrEvent>>()
    private val joined = HashMap<GroupRef, Boolean>()

    fun events(ref: GroupRef, roomKey: String): List<NostrEvent> = lock.withLock {
        val key = key(ref, roomKey)
        val room = rooms.remove(key) ?: return@withLock emptyList()
        rooms[key] = room
        room.values.toList()
    }

    fun addEvents(ref: GroupRef, roomKey: String, events: Collection<NostrEvent>) {
        if (events.isEmpty()) return
        lock.withLock {
            val key = key(ref, roomKey)
            val merged = LinkedHashMap(rooms.remove(key).orEmpty())
            events.forEach { merged[it.id] = it }
            rooms[key] = trim(merged)
            while (rooms.size > maxRooms) rooms.remove(rooms.keys.first())
        }
    }

    fun joinedHint(ref: GroupRef): Boolean? = lock.withLock { joined[ref] }

    fun setJoinedHint(ref: GroupRef, isJoined: Boolean) {
        lock.withLock { joined[ref] = isJoined }
    }

    /** グループの状態(情報・管理者・メンバー・参加と退出)は残し、それ以外は新しい順に [maxEventsPerRoom] 件まで。 */
    private fun trim(events: Map<String, NostrEvent>): Map<String, NostrEvent> {
        val (state, others) = events.values.partition { it.kind in STATE_KINDS }
        if (others.size <= maxEventsPerRoom) return events
        val kept = others.sortedByDescending { it.createdAt }.take(maxEventsPerRoom)
        return (state + kept).associateBy { it.id }
    }

    private fun key(ref: GroupRef, roomKey: String) = "${ref.relayUrl}\u0000${ref.groupId}\u0000$roomKey"

    private companion object {
        val STATE_KINDS = setOf(
            GROUP_METADATA_KIND, GROUP_ADMINS_KIND, GROUP_MEMBERS_KIND, GROUP_PUT_USER_KIND, GROUP_REMOVE_USER_KIND,
        )
    }
}
