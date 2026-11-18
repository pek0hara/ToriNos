package com.nostr.torinos.dm

import com.nostr.torinos.account.AccountSessionEndedException
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.SignerException
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.calculateEventId
import com.nostr.torinos.crypto.generateKeyPair
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// NIP-17（Private Direct Messages）と NIP-59（Gift Wrap）。docs/dm-feature-design.md §2・§5.3・§7.1。

const val DM_RUMOR_KIND = 14
const val DM_SEAL_KIND = 13
const val GIFT_WRAP_KIND = 1059
const val DM_RELAY_LIST_KIND = 10050

/** seal と gift wrap の `created_at` をずらす幅（過去2日以内）。 */
const val WRAP_TIME_JITTER_SECONDS = 2L * 24 * 3600

/** 2回目以降の取得で、最後に受け取った gift wrap の時刻からさかのぼる幅（ずらしの2日＋1時間）。 */
const val GIFT_WRAP_SINCE_MARGIN_SECONDS = WRAP_TIME_JITTER_SECONDS + 3600

/** 署名しないイベント（rumor）。漏れても本人が書いた証明にならない。 */
data class Rumor(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    /** 宛先（`p` タグ）。 */
    val recipients: List<String> get() = tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }
}

private fun rumorIdOf(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
    calculateEventId(NostrEvent("", pubkey, createdAt, kind, tags, content, ""))

/**
 * rumor を作る。宛先は重複と自分を除く（自分だけに宛てたメモは自分を残す）。
 * 宛先の `p` の後ろに [extraTags]（`emoji` など）を付ける。
 */
fun buildDmRumor(
    sender: String,
    recipients: List<String>,
    content: String,
    createdAt: Long,
    extraTags: List<List<String>> = emptyList(),
    kind: Int = DM_RUMOR_KIND,
): Rumor {
    val distinct = recipients.distinct()
    val others = distinct.filter { it != sender }.ifEmpty { distinct }
    val tags = others.map { listOf("p", it) } + extraTags
    return Rumor(rumorIdOf(sender, createdAt, kind, tags, content), sender, createdAt, kind, tags, content)
}

private val dmJson = Json { ignoreUnknownKeys = true }

private fun tagsJson(tags: List<List<String>>) = JsonArray(tags.map { tag -> JsonArray(tag.map(::JsonPrimitive)) })

/** rumor の JSON（`sig` を持たない）。 */
fun Rumor.toJson(): String = buildJsonObject {
    put("id", id)
    put("pubkey", pubkey)
    put("created_at", createdAt)
    put("kind", kind)
    put("tags", tagsJson(tags))
    put("content", content)
}.toString()

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.tags(): List<List<String>>? = runCatching {
    get("tags")?.jsonArray?.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
}.getOrNull()

/** rumor の JSON を読む。形が違えば null。ID の検査は呼び出し側で行う。 */
fun parseRumorJson(json: String): Rumor? = runCatching {
    val obj = dmJson.parseToJsonElement(json).jsonObject
    Rumor(
        id = obj.string("id") ?: return null,
        pubkey = obj.string("pubkey") ?: return null,
        createdAt = (obj["created_at"] as? JsonPrimitive)?.longOrNull ?: return null,
        kind = (obj["kind"] as? JsonPrimitive)?.intOrNull ?: return null,
        tags = obj.tags() ?: return null,
        content = obj.string("content") ?: return null,
    )
}.getOrNull()

/** 署名済みイベント（seal）の JSON。 */
fun sealJson(event: NostrEvent): String = dmJson.encodeToString(NostrEvent.serializer(), event)

/** 署名済みイベントの JSON を読む。読めなければ例外。 */
fun parseSignedEvent(json: String): NostrEvent = dmJson.decodeFromString(NostrEvent.serializer(), json)

private fun shiftedTime(now: Long, random: Random): Long = now - random.nextLong(0, WRAP_TIME_JITTER_SECONDS + 1)

/**
 * rumor を [recipient] 宛てに seal で封をし、使い捨ての鍵で gift wrap に包む（§7.1-3）。
 * 自分宛ての写しは [recipient] に自分を渡す。seal の署名と暗号化は [signer] に頼む。
 */
suspend fun wrapRumor(
    rumor: Rumor,
    recipient: String,
    signer: AccountSigner,
    now: Long,
    random: Random = Random.Default,
    ephemeralPrivateKey: String = generateKeyPair().privateKeyHex,
): NostrEvent {
    val seal = signer.sign(
        content = signer.nip44Encrypt(rumor.toJson(), recipient),
        kind = DM_SEAL_KIND,
        tags = emptyList(),
        createdAt = shiftedTime(now, random),
    )
    return signEvent(
        privateKeyHex = ephemeralPrivateKey,
        content = Nip44.encrypt(sealJson(seal), ephemeralPrivateKey, recipient),
        kind = GIFT_WRAP_KIND,
        tags = listOf(listOf("p", recipient)),
        createdAt = shiftedTime(now, random),
    )
}

enum class UnwrapFailure {
    /** `p` が自分でない。 */
    NotForMe,
    NotGiftWrap,
    /** gift wrap の中身を復号できない、seal として読めない。 */
    BrokenWrap,
    /** seal の kind・署名・タグが正しくない。 */
    BadSeal,
    /** rumor を復号できない、読めない、kind か ID が違う。 */
    BadRumor,
    /** seal の署名者と rumor の作者が違う（なりすまし）。 */
    SenderMismatch,
}

sealed interface UnwrapResult {
    data class Opened(val rumor: Rumor) : UnwrapResult
    data class Rejected(val reason: UnwrapFailure) : UnwrapResult
}

/** 署名器の失敗とキャンセルは呼び出し元へ伝え、壊れた内容だけを null にする。 */
private inline fun <T> decryptOrNull(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SignerException) {
        throw e
    } catch (e: AccountSessionEndedException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * gift wrap を開封して検証する（§5.3）。gift wrap 自体の署名は受信時に検証済みとする。
 * 署名器が失敗したとき（拒否・タイムアウトなど）は [SignerException] を投げる。
 */
suspend fun unwrapGiftWrap(wrap: NostrEvent, signer: AccountSigner): UnwrapResult {
    fun reject(reason: UnwrapFailure) = UnwrapResult.Rejected(reason)
    if (wrap.kind != GIFT_WRAP_KIND) return reject(UnwrapFailure.NotGiftWrap)
    if (wrap.tags.none { it.firstOrNull() == "p" && it.getOrNull(1) == signer.pubkey }) return reject(UnwrapFailure.NotForMe)

    val seal = decryptOrNull { parseSignedEvent(signer.nip44Decrypt(wrap.content, wrap.pubkey)) }
        ?: return reject(UnwrapFailure.BrokenWrap)
    if (seal.kind != DM_SEAL_KIND || seal.tags.isNotEmpty() || !isValidEvent(seal)) return reject(UnwrapFailure.BadSeal)

    val rumor = decryptOrNull { parseRumorJson(signer.nip44Decrypt(seal.content, seal.pubkey)) }
        ?: return reject(UnwrapFailure.BadRumor)
    if (rumor.kind != DM_RUMOR_KIND) return reject(UnwrapFailure.BadRumor)
    if (rumor.id != rumorIdOf(rumor.pubkey, rumor.createdAt, rumor.kind, rumor.tags, rumor.content)) return reject(UnwrapFailure.BadRumor)
    if (rumor.pubkey != seal.pubkey) return reject(UnwrapFailure.SenderMismatch)
    return UnwrapResult.Opened(rumor)
}

/**
 * 会話の相手。rumor の作者と宛先から自分を除いたもの。
 * 自分だけに宛てたもの（メモ）は、自分との会話にする。
 */
fun conversationPeers(rumor: Rumor, me: String): Set<String> {
    val everyone = (rumor.recipients + rumor.pubkey).toSet()
    return (everyone - me).ifEmpty { setOf(me) }
}

/** 2回目以降の取得の `since`（§5.2）。初回は null。 */
fun giftWrapSince(lastWrapCreatedAt: Long?): Long? = lastWrapCreatedAt?.let { it - GIFT_WRAP_SINCE_MARGIN_SECONDS }
