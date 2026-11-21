package com.nostr.torinos.chat

import com.nostr.torinos.network.normalizeRelayUrl
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Buzz コミュニティの同期（kind 10009 の暗号化項目の `r`）。docs/buzz-community-sync-design.md

/**
 * 暗号化項目にある Buzz の候補。同じリレーの `group` タグ（公開・暗号化のどちらでも）があるものは、
 * 標準の NIP-29 のグループの `r` なので除く。公開タグの `r` は候補にしない。
 */
internal fun buzzRelayCandidates(publicTags: List<List<String>>, privateTags: List<List<String>>): Set<String> {
    val groupRelays = parseJoinedGroups(publicTags + privateTags).map { it.ref.relayUrl }.toSet()
    return privateTags.asSequence()
        .filter { it.firstOrNull() == "r" }
        .mapNotNull { tag -> tag.getOrNull(1)?.let(::normalizeRelayUrl) }
        .filter { it !in groupRelays }
        .toSet()
}

/** チャットタブを開いたときに行うこと。 */
internal data class BuzzSyncPlan(
    /** 10009 にだけある。NIP-11 とメンバーかを確かめてから端末に入れる。 */
    val toVerify: Set<String>,
    /** 端末にだけある。10009 の暗号化項目へ書き足す。 */
    val toPublish: Set<String>,
)

/**
 * 端末の一覧 [local] と、10009 の候補 [remote] を合わせる（和集合）。
 * [notMember] は「メンバーでない」と分かったリレーと、そのときの 10009 の ID。同じ版のうちは確かめ直さない。
 * [listId] は今の 10009 の ID（一覧が無いときは null）。
 */
internal fun planBuzzSync(
    local: Set<String>,
    remote: Set<String>,
    notMember: Map<String, String>,
    listId: String?,
): BuzzSyncPlan = BuzzSyncPlan(
    toVerify = (remote - local).filterTo(mutableSetOf()) { listId == null || notMember[it] != listId },
    toPublish = local - remote,
)

/** 一覧を確かに読めたとき（`listConfirmed`）だけ合わせる。答えがそろわないうちに「無い」と決めつけない。 */
internal fun buzzSyncAllowed(listConfirmed: Boolean): Boolean = listConfirmed

/** 最後に処理した 10009 の版と、そこにあった候補。同じ版なら復号せずにこれで合わせる。 */
internal data class BuzzSyncState(val listId: String, val remote: Set<String>)

/** 復号が要るか。一覧が無い、暗号化項目が無い、控えと同じ版なら要らない。 */
internal fun needsBuzzDecrypt(listId: String?, contentBlank: Boolean, stored: BuzzSyncState?): Boolean =
    listId != null && !contentBlank && stored?.listId != listId

internal fun encodeBuzzSyncState(state: BuzzSyncState): String =
    JsonObject(
        mapOf(
            "id" to JsonPrimitive(state.listId),
            "remote" to JsonArray(state.remote.sorted().map(::JsonPrimitive)),
        ),
    ).toString()

internal fun decodeBuzzSyncState(stored: String?): BuzzSyncState? {
    if (stored.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(stored).jsonObject
        BuzzSyncState(
            listId = obj.getValue("id").jsonPrimitive.content,
            remote = obj.getValue("remote").jsonArray.mapNotNull { normalizeRelayUrl(it.jsonPrimitive.content) }.toSet(),
        )
    }.getOrNull()
}

internal fun encodeBuzzNotMember(map: Map<String, String>): String =
    map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" }

internal fun decodeBuzzNotMember(stored: String?): Map<String, String> =
    stored.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 2) return@mapNotNull null
        val url = normalizeRelayUrl(parts[0]) ?: return@mapNotNull null
        url to parts[1]
    }.toMap()

/** 暗号化項目に、無いリレーの `r` を足す（URL 順）。未知のタグは保つ。 */
internal fun withPrivateRelays(privateTags: List<List<String>>, relays: Set<String>): List<List<String>> {
    val listed = privateTags.asSequence()
        .filter { it.firstOrNull() == "r" }
        .mapNotNull { tag -> tag.getOrNull(1)?.let(::normalizeRelayUrl) }
        .toSet()
    val missing = relays.mapNotNull(::normalizeRelayUrl).filter { it !in listed }.distinct().sorted()
    return if (missing.isEmpty()) privateTags else privateTags + missing.map { listOf("r", it) }
}

/**
 * 暗号化項目を書き換えるために読む。`content` が空なら空から始める。
 * 復号や JSON の読み取りに失敗したら null（空として扱うと、ほかのクライアントが入れた項目を消してしまう）。
 */
internal suspend fun readPrivateTagsStrict(content: String, decrypt: suspend (String) -> String): List<List<String>>? {
    if (content.isBlank()) return emptyList()
    return try {
        Json.parseToJsonElement(decrypt(content)).jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
