package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random

/** Buzz の在席（保存されない、コミュニティ全体）。docs/buzz-presence-design.md */
const val BUZZ_PRESENCE_KIND = 20001

/** 届いた在席を有効とみなす長さ。相手は60〜65秒ごとに送り直す。 */
internal const val PRESENCE_LIFETIME_MILLIS = 75_000L

/** 自分の在席を送り直す間隔（+揺らぎ）。一括取得の間隔にも使う。 */
internal const val PRESENCE_RENEW_MILLIS = 60_000L
internal const val PRESENCE_RENEW_JITTER_MILLIS = 5_000L

/** 送れなかったときに送り直すまでの時間。 */
internal const val PRESENCE_RETRY_MILLIS = 5_000L

/** 一度に在席を見る人数の上限（Buzz と同じ）。 */
internal const val PRESENCE_MAX_SUBJECTS = 256

enum class PresenceStatus(val wire: String) {
    Online("online"),
    Away("away"),
    Offline("offline"),
}

private val presenceJson = Json { ignoreUnknownKeys = true }

/** 自分の在席。タグは付けない（チャンネルではなくコミュニティ全体のもの）。 */
fun buildBuzzPresence(status: PresenceStatus): GroupEventDraft = GroupEventDraft(BUZZ_PRESENCE_KIND, emptyList(), status.wire)

/** content の在席。古い形の `{"status":"…"}` も読む。分からなければ null。 */
fun parsePresenceContent(content: String): PresenceStatus? {
    val raw = if (content.startsWith("{")) {
        runCatching {
            ((presenceJson.parseToJsonElement(content) as JsonObject)["status"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull
        }.getOrNull() ?: return null
    } else {
        content
    }
    return PresenceStatus.entries.firstOrNull { it.wire == raw }
}

/** `POST /query` の本文。 */
fun buzzPresenceQueryBody(authors: List<String>): String =
    buildJsonArray {
        add(
            buildJsonObject {
                putJsonArray("kinds") { add(JsonPrimitive(BUZZ_PRESENCE_KIND)) }
                putJsonArray("authors") { authors.forEach { add(JsonPrimitive(it)) } }
                put("limit", authors.size)
            },
        )
    }.toString()

/**
 * `POST /query` の応答。リレーの鍵で署名した kind 20001（`["p", 対象]` が1つ）が1人1件で、載っていない人はオフライン。
 * 在席の文字列が未知ならその人は null（不明）。信用できない応答（署名者が違う、`p` が1つでない、頼んでいない人、
 * 同じ人が2件、kind が違う、形が違う）は全体を null にする（Buzz の `parsePresence` と同じ）。
 */
fun parseBuzzPresenceSnapshot(body: String, authors: List<String>, relayPubkey: String): Map<String, PresenceStatus?>? {
    val array = runCatching { presenceJson.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
    if (array.size > authors.size) return null
    val events = runCatching { array.map { presenceJson.decodeFromJsonElement(NostrEvent.serializer(), it) } }.getOrNull() ?: return null
    val values = LinkedHashMap<String, PresenceStatus?>()
    for (event in events) {
        val subjects = event.tags.filter { it.firstOrNull() == "p" }
        val subject = subjects.singleOrNull()?.takeIf { it.size == 2 }?.get(1) ?: return null
        if (event.kind != BUZZ_PRESENCE_KIND || event.pubkey != relayPubkey || subject !in authors || subject in values) return null
        values[subject] = parsePresenceContent(event.content)
    }
    return authors.associateWith { if (it in values) values[it] else PresenceStatus.Offline }
}

/** 在席を見る人。優先する人（DM の参加者）を先に、残りは新しい発言者から、重複を除いて [limit] 人まで。 */
fun presenceSubjects(authorsOldestFirst: List<String>, priority: List<String>, limit: Int = PRESENCE_MAX_SUBJECTS): List<String> {
    val picked = LinkedHashSet<String>()
    for (key in priority) if (picked.size < limit) picked += key
    for (key in authorsOldestFirst.asReversed()) if (picked.size < limit) picked += key
    return picked.toList()
}

/**
 * 画面の在席を見る人。DM の参加者、メンバー一覧を開いている間は一覧の人（表示順）、残りは新しい発言者の順で、上限まで。
 */
fun presenceMembersSubjects(
    authorsOldestFirst: List<String>,
    participants: List<String>,
    members: List<String>,
    memberListOpen: Boolean,
    limit: Int = PRESENCE_MAX_SUBJECTS,
): List<String> = presenceSubjects(authorsOldestFirst, priority = participants + if (memberListOpen) members else emptyList(), limit = limit)

/**
 * ほかの人の在席の記録。配信と一括取得の両方から更新し、届いてから [lifetimeMillis] で不明に戻す。
 * 一括取得を始めた後に配信で更新された人は、その応答で上書きしない（応答の方が古いため）。
 */
class PresenceBook(private val lifetimeMillis: Long = PRESENCE_LIFETIME_MILLIS) {
    private class Entry(val status: PresenceStatus?, val updatedAt: Long, val expiresAt: Long)

    private val entries = HashMap<String, Entry>()

    /** 配信で届いた在席。表示が変わったら true。 */
    fun receiveLive(event: NostrEvent, nowMillis: Long): Boolean {
        if (event.kind != BUZZ_PRESENCE_KIND) return false
        return put(event.pubkey, parsePresenceContent(event.content), nowMillis)
    }

    /** 一括取得の応答（[requestedAtMillis] に頼んだもの）。表示が変わったら true。 */
    fun applySnapshot(values: Map<String, PresenceStatus?>, requestedAtMillis: Long, nowMillis: Long): Boolean {
        var changed = false
        for ((key, status) in values) {
            val current = entries[key]
            if (current != null && current.updatedAt > requestedAtMillis) continue
            changed = put(key, status, nowMillis) || changed
        }
        return changed
    }

    /** 今の在席。不明（届いていない・期限切れ・未知の値）は null。 */
    fun status(pubkey: String, nowMillis: Long): PresenceStatus? =
        entries[pubkey]?.takeIf { it.expiresAt > nowMillis }?.status

    /** 表示中のすべての在席（不明は含めない）。 */
    fun snapshot(nowMillis: Long): Map<String, PresenceStatus> =
        entries.mapNotNull { (key, entry) -> entry.status?.takeIf { entry.expiresAt > nowMillis }?.let { key to it } }.toMap()

    /** 次に誰かの在席が不明に戻る時刻。 */
    fun nextExpiryAt(nowMillis: Long): Long? =
        entries.values.filter { it.status != null && it.expiresAt > nowMillis }.minOfOrNull { it.expiresAt }

    private fun put(key: String, status: PresenceStatus?, nowMillis: Long): Boolean {
        val before = status(key, nowMillis)
        entries[key] = Entry(status, nowMillis, nowMillis + lifetimeMillis)
        return before != status
    }
}

/**
 * 自分の在席（`online`）をコミュニティごとに送り直す。[hold] の数を数え、1つ目で始めて最後に手放したら止める。
 * 送れたら60〜65秒後、送れなかったら5秒後に次を送る。`offline` は送らない（リレーの期限で消える）。
 */
class PresencePublisher(
    private val scope: CoroutineScope,
    private val publish: suspend (relayUrl: String) -> Boolean,
    private val random: Random = Random.Default,
) {
    private class Slot(var holds: Int, val job: Job)

    private val slots = HashMap<String, Slot>()

    /** [relayUrl] で在席を送り続ける。戻り値を呼ぶと手放す（2回目以降は何もしない）。 */
    fun hold(relayUrl: String): () -> Unit {
        val slot = slots[relayUrl]
        if (slot != null) {
            slot.holds++
        } else {
            slots[relayUrl] = Slot(1, scope.launch { renew(relayUrl) })
        }
        var released = false
        return {
            if (!released) {
                released = true
                release(relayUrl)
            }
        }
    }

    private fun release(relayUrl: String) {
        val slot = slots[relayUrl] ?: return
        slot.holds--
        if (slot.holds <= 0) {
            slots.remove(relayUrl)
            slot.job.cancel()
        }
    }

    private suspend fun CoroutineScope.renew(relayUrl: String) {
        while (isActive) {
            val sent = runCatching { publish(relayUrl) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(false)
            delay(if (sent) PRESENCE_RENEW_MILLIS + random.nextLong(PRESENCE_RENEW_JITTER_MILLIS + 1) else PRESENCE_RETRY_MILLIS)
        }
    }
}
