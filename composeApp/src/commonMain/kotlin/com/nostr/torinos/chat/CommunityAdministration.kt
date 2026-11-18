package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Buzz のコミュニティ（NIP-43）の owner / admin の操作（docs/buzz-admin-design.md 4.2）。
// 役割は表示のためだけで、実際の可否はリレーが決める。

/** コミュニティのメンバー一覧（リレー署名、`["member", pubkey, role]`）。 */
const val COMMUNITY_ROSTER_KIND = 13534
/** NIP-43: メンバーを外す・役割を変える。`h` を持たないコミュニティ全体の命令。 */
const val COMMUNITY_REMOVE_MEMBER_KIND = 9031
const val COMMUNITY_CHANGE_ROLE_KIND = 9032

enum class CommunityRole(val wire: String) { Owner("owner"), Admin("admin"), Member("member") }

data class CommunityMember(val pubkey: String, val role: CommunityRole)

private val HEX64 = Regex("^[0-9a-f]{64}$")

/** リレーの鍵で署名された 13534 だけを読む。署名者か kind が違えば null。 */
fun parseCommunityRoster(event: NostrEvent, relayPubkey: String): List<CommunityMember>? {
    if (event.kind != COMMUNITY_ROSTER_KIND || event.pubkey != relayPubkey) return null
    val members = LinkedHashMap<String, CommunityMember>()
    event.tags.forEach { tag ->
        if (tag.firstOrNull() != "member") return@forEach
        val key = tag.getOrNull(1)?.takeIf(HEX64::matches) ?: return@forEach
        if (key in members) return@forEach
        members[key] = CommunityMember(key, CommunityRole.entries.firstOrNull { it.wire == tag.getOrNull(2) } ?: CommunityRole.Member)
    }
    return members.values.toList()
}

/** 最新の 13534。リレーの鍵で署名されたものが無ければ null（メンバー一覧を出さないコミュニティ）。 */
fun latestCommunityRoster(events: Iterable<NostrEvent>, relayPubkey: String): List<CommunityMember>? =
    events
        .filter { it.kind == COMMUNITY_ROSTER_KIND && it.pubkey == relayPubkey }
        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        ?.let { parseCommunityRoster(it, relayPubkey) }

/** オーナー、管理者、そのほか（それぞれ名前順）、自分は最後。 */
fun orderCommunityMembers(members: List<CommunityMember>, me: String?, nameOf: (String) -> String?): List<CommunityMember> =
    members.sortedWith(
        compareBy<CommunityMember> { it.pubkey == me }
            .thenBy { it.role.ordinal }
            .thenBy { (nameOf(it.pubkey) ?: it.pubkey).lowercase() },
    )

fun managesCommunity(role: CommunityRole?): Boolean = role == CommunityRole.Owner || role == CommunityRole.Admin

enum class CommunityAction { Promote, Demote, Remove }

/** リレーの権限の表に沿って、[actor] が [target] にできること（buzz-app の `allowedActions`）。 */
fun allowedCommunityActions(actor: CommunityRole?, target: CommunityMember, self: Boolean): List<CommunityAction> {
    if (self || target.role == CommunityRole.Owner) return emptyList()
    return when (actor) {
        CommunityRole.Admin -> if (target.role == CommunityRole.Member) listOf(CommunityAction.Remove) else emptyList()
        CommunityRole.Owner -> listOf(if (target.role == CommunityRole.Admin) CommunityAction.Demote else CommunityAction.Promote, CommunityAction.Remove)
        else -> emptyList()
    }
}

private fun normalizedKey(pubkey: String): String {
    val key = pubkey.trim().lowercase()
    require(HEX64.matches(key)) { "pubkey must be 32-byte hex" }
    return key
}

fun buildCommunityRemove(pubkey: String): GroupEventDraft =
    GroupEventDraft(COMMUNITY_REMOVE_MEMBER_KIND, listOf(listOf("p", normalizedKey(pubkey))), "")

/** 役割の変更。owner にはできない。 */
fun buildCommunityRole(pubkey: String, role: CommunityRole): GroupEventDraft {
    require(role != CommunityRole.Owner) { "cannot set role to owner" }
    return GroupEventDraft(COMMUNITY_CHANGE_ROLE_KIND, listOf(listOf("p", normalizedKey(pubkey)), listOf("role", role.wire)), "")
}

fun communityRoleLabel(role: CommunityRole): String? = when (role) {
    CommunityRole.Owner -> "オーナー"
    CommunityRole.Admin -> "管理者"
    CommunityRole.Member -> null
}

// ---- 招待 ----

private const val MIN_INVITE_TTL = 60L
private const val MAX_INVITE_TTL = 30L * 24 * 60 * 60
private const val MAX_INVITE_USES = 10_000

/** `POST /api/invites` の内容。範囲はリレーと同じ（期限 60秒〜30日、回数 1〜10,000 か無制限）。 */
data class InviteRequest(val ttlSeconds: Long, val maxUses: Int?) {
    init {
        require(ttlSeconds in MIN_INVITE_TTL..MAX_INVITE_TTL) { "invalid invite expiry" }
        require(maxUses == null || maxUses in 1..MAX_INVITE_USES) { "invalid invite use limit" }
    }
}

fun inviteRequestBody(request: InviteRequest): String = buildJsonObject {
    put("ttl_secs", request.ttlSeconds)
    put("max_uses", request.maxUses)
}.toString()

data class MintedInvite(
    val code: String,
    val url: String,
    /** Unix 秒。 */
    val expiresAt: Long,
    val maxUses: Int?,
    val usesRemaining: Int?,
)

private val inviteJson = Json { ignoreUnknownKeys = true }

/** 応答を読む。`url` がそのコミュニティの `/invite/` でなければ null（別のホストのリンクを配らない）。 */
fun parseMintedInvite(body: String, origin: String): MintedInvite? = runCatching {
    val obj = inviteJson.parseToJsonElement(body) as? JsonObject ?: return null
    fun text(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun count(name: String): Int? = when (val value = obj[name]) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.longOrNull?.toInt()
        else -> null
    }
    val code = text("code")?.takeIf { it.isNotBlank() } ?: return null
    val url = text("url") ?: return null
    if (!url.startsWith("$origin/invite/")) return null
    val expiresAt = (obj["expires_at"] as? JsonPrimitive)?.longOrNull ?: return null
    MintedInvite(code, url, expiresAt, count("max_uses"), count("uses_remaining"))
}.getOrNull()

/** 招待の発行の拒否を表示用の文にする。 */
fun inviteRefusalMessage(status: Int, body: String): String {
    val error = runCatching {
        ((inviteJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
    return when {
        error == null -> "招待を作れませんでした（$status）"
        "only relay owners and admins" in error -> "招待を作れるのはオーナーと管理者だけです"
        error.startsWith("ttl_secs must be") || error.startsWith("max_uses must be") || error.startsWith("invalid invite JSON") ->
            "期限・回数の指定が正しくありません"
        else -> adminRejectionMessage(error).let { if (it.startsWith("受け付けられませんでした")) "招待を作れませんでした（$error）" else it }
    }
}

sealed interface InviteMintResult {
    data class Minted(val invite: MintedInvite) : InviteMintResult
    data class Refused(val message: String) : InviteMintResult
}

/** 招待の発行（`POST /api/invites`、NIP-98 で本文の SHA-256 を `payload` に入れる）。招待のコードはログに出さない。 */
class BuzzInviteService(private val http: BuzzHttp, private val signer: BuzzRequestSigner) {
    suspend fun mint(relayUrl: String, request: InviteRequest): InviteMintResult {
        val origin = buzzHttpOrigin(relayUrl)
        val url = "$origin/api/invites"
        val body = inviteRequestBody(request)
        val auth = signer.sign(HTTP_AUTH_KIND, nip98Tags(url, "POST", body), "")
        val response = http.postJson(url, body, nostrAuthorizationHeader(auth))
        if (!response.isSuccess) return InviteMintResult.Refused(inviteRefusalMessage(response.status, response.body))
        val invite = parseMintedInvite(response.body, origin) ?: return InviteMintResult.Refused("招待の応答を読めませんでした")
        return InviteMintResult.Minted(invite)
    }
}

/** コミュニティのメンバー一覧の読み取りの結果。 */
sealed interface CommunityRosterLoad {
    data class Loaded(val members: List<CommunityMember>) : CommunityRosterLoad
    /** メンバー一覧を出さないコミュニティ。 */
    data object None : CommunityRosterLoad
    data class Failed(val reason: String) : CommunityRosterLoad
}
