package com.nostr.torinos.chat

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.crypto.sha256
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.network.ImageUploader
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.account.SignerMethod
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayProfileCheck
import com.nostr.torinos.network.profileEventToResend
import kotlinx.coroutines.coroutineScope
import com.nostr.torinos.network.RELAY_AUTH_KIND
import com.nostr.torinos.network.RelayAuthSigner
import com.nostr.torinos.network.RelayAuthState
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.authEventTags
import com.nostr.torinos.network.fetchFiniteEvents
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

internal fun decodeAuthRelays(stored: String?): Set<String> =
    stored.orEmpty().lineSequence().mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let(::normalizeRelayUrl) }.toSet()

internal fun encodeAuthRelays(relays: Set<String>): String = relays.sorted().joinToString("\n")

/**
 * NIP-42 で認証してよいリレー。ユーザーが許可したリレーと、参加中のグループのリレー。
 * [restoring] は、自分の kind 10009 の暗号化項目から復元を確かめている Buzz のリレー。
 */
internal fun authAllowedRelays(
    consented: Set<String>,
    joined: List<JoinedGroup>,
    buzzCommunities: Set<String> = emptySet(),
    restoring: Set<String> = emptySet(),
): Set<String> = consented + joined.map { it.ref.relayUrl } + buzzCommunities + restoring

/** Buzz コミュニティに参加済みかを確かめた結果。 */
sealed interface BuzzMembershipCheck {
    data object Member : BuzzMembershipCheck
    data class NotMember(val reason: String) : BuzzMembershipCheck
    data class Unknown(val reason: String) : BuzzMembershipCheck
}

/**
 * 購読が閉じられた理由から、Buzz コミュニティのメンバーかを決める。メンバーでないと分かるのは
 * `not a relay member`（AUTH の拒否）だけ。AUTH の拒否が REQ の拒否より先に届くと、閉じた理由は
 * `auth-required: not authenticated` になるので、その接続の認証の失敗理由（[authFailure]）も見る。
 * 署名の拒否や時間切れは分からないとする。
 */
internal fun buzzMembershipFromClosed(closedReason: String, authFailure: String?): BuzzMembershipCheck {
    val notMember = listOfNotNull(closedReason, authFailure).firstOrNull { "not a relay member" in it }
    return if (notMember != null) BuzzMembershipCheck.NotMember(notMember) else BuzzMembershipCheck.Unknown(closedReason)
}

/**
 * kind 10009 を書き換えてよいか。一部のリレーしか答えていないまま書き換えると、
 * 遅いリレーにある（他のクライアントで保存した）一覧を、空の一覧で上書きしてしまう。
 */
internal fun groupsListRewriteAllowed(allRelaysAnswered: Boolean, foundList: Boolean): Boolean =
    allRelaysAnswered || foundList

/** kind 10009 を読んだ結果、参加中のグループがあるかが分かったか。 */
internal sealed interface GroupsLoad {
    data class Known(val hasGroups: Boolean) : GroupsLoad
    /** 答えがそろわず見つからなかった。前回の控え（[previous]）のまま、読み直す。 */
    data class Retry(val previous: Boolean) : GroupsLoad
}

/**
 * 参加中のグループがあるかを決める。一覧（[found]）が読めたらその中身、全リレーが無いと答えたら無し、
 * どちらでもなければ前回の控えのまま読み直す（起動直後の時間切れで、グループのある人を DM が主役の画面にしないため）。
 */
internal fun groupsLoadOutcome(previous: Boolean, allRelaysAnswered: Boolean, found: List<JoinedGroup>?): GroupsLoad = when {
    found != null -> GroupsLoad.Known(found.isNotEmpty())
    allRelaysAnswered -> GroupsLoad.Known(false)
    else -> GroupsLoad.Retry(previous)
}

internal fun encodeKnownGroups(hasGroups: Boolean): String = if (hasGroups) "1" else "0"

internal fun decodeKnownGroups(stored: String?): Boolean = stored == "1"

/** kind 10009 の読み直しの間隔（時間切れのとき）。 */
private val GROUPS_RETRY_DELAYS_MILLIS = listOf(5_000L, 15_000L, 45_000L)

internal sealed interface GroupsListChange {
    data class Add(val group: JoinedGroup) : GroupsListChange
    /** [keepRelays] は参加中の Buzz コミュニティ。そのリレーの `r` は残す。 */
    data class Remove(val ref: GroupRef, val keepRelays: Set<String> = emptySet()) : GroupsListChange
    /** Buzz コミュニティの `r` を暗号化項目へ足す（docs/buzz-community-sync-design.md）。 */
    data class AddPrivateRelays(val relays: Set<String>) : GroupsListChange
}

internal data class GroupsListUpdate(
    val publicTags: List<List<String>>,
    val privateTags: List<List<String>>,
    val privateChanged: Boolean,
)

/**
 * kind 10009 の公開タグと暗号化された項目に変更を当てる。足すときは公開タグへ（既にどちらかにあれば変えない）、
 * 外すときは両方から外す。
 */
internal fun applyGroupsListChange(
    publicTags: List<List<String>>,
    privateTags: List<List<String>>,
    change: GroupsListChange,
): GroupsListUpdate = when (change) {
    is GroupsListChange.Add -> {
        val listed = parseJoinedGroups(publicTags + privateTags).any { it.ref == change.group.ref }
        GroupsListUpdate(
            publicTags = if (listed) publicTags else withJoinedGroup(publicTags, change.group),
            privateTags = privateTags,
            privateChanged = false,
        )
    }
    is GroupsListChange.Remove -> {
        val nextPrivate = withoutJoinedGroup(privateTags, change.ref, change.keepRelays)
        GroupsListUpdate(
            publicTags = withoutJoinedGroup(publicTags, change.ref, change.keepRelays),
            privateTags = nextPrivate,
            privateChanged = nextPrivate != privateTags,
        )
    }
    is GroupsListChange.AddPrivateRelays -> {
        val nextPrivate = withPrivateRelays(privateTags, change.relays)
        GroupsListUpdate(publicTags = publicTags, privateTags = nextPrivate, privateChanged = nextPrivate != privateTags)
    }
}

/** グループから退出した結果。 */
sealed interface GroupLeaveOutcome {
    data object Left : GroupLeaveOutcome
    /** リレーは退出を受け付けたが、参加中の一覧（kind 10009）を更新できなかった。 */
    data class LeftButListNotUpdated(val reason: String) : GroupLeaveOutcome
    data class NotLeft(val reason: String) : GroupLeaveOutcome
}

/** グループのイベントを送った結果。 */
sealed interface GroupPublishOutcome {
    /** [message] は受理したリレーが OK に付けたメッセージ（Buzz の命令の結果や `duplicate:`）。 */
    data class Published(val event: NostrEvent, val message: String = "") : GroupPublishOutcome
    data class Rejected(val reason: String) : GroupPublishOutcome
    data class Failed(val reason: String) : GroupPublishOutcome
}

sealed interface BuzzDmOutcome {
    data class Opened(val ref: GroupRef) : BuzzDmOutcome
    data class Failed(val reason: String) : BuzzDmOutcome
}

/**
 * アカウントごとのチャット（NIP-29）の状態。参加中のグループ（kind 10009）、NIP-42 の許可リスト、
 * グループへの送信を持つ。セッションの終了で [close] する。
 */
class ChatAccount internal constructor(
    private val scope: CoroutineScope,
    val pubkey: String,
    private val signer: AccountSigner,
    /** チャット以外で NIP-42 の認証を許すリレー（自分の DM リレー）。 */
    private val extraAuthRelays: () -> Set<String> = { emptySet() },
) {
    private val authKey = "chat.authRelays.$pubkey"
    private val pendingKey = "chat.pendingGroups.$pubkey"
    private val buzzKey = "chat.buzzCommunities.$pubkey"
    private val _buzz = MutableStateFlow<Set<String>>(emptySet())
    /**
     * 参加中の Buzz コミュニティ（リレーの URL）。端末に保存し、kind 10009 の暗号化項目の `r` と合わせる
     * （docs/buzz-community-sync-design.md）。
     */
    val buzzCommunities: StateFlow<Set<String>> = _buzz.asStateFlow()
    private val buzzSyncStateKey = "chat.buzzSync.state.$pubkey"
    private val buzzNotMemberKey = "chat.buzzSync.notMember.$pubkey"
    private val _restoringBuzz = MutableStateFlow<Set<String>>(emptySet())
    /** kind 10009 から復元を確かめている Buzz のリレー（確認中）。メモリだけに持つ。 */
    val restoringBuzz: StateFlow<Set<String>> = _restoringBuzz.asStateFlow()
    private val _removedBuzz = MutableStateFlow<Set<String>>(emptySet())
    /** 退会させられたので一覧から外したコミュニティ（知らせるため。メモリだけに持つ）。 */
    val removedBuzz: StateFlow<Set<String>> = _removedBuzz.asStateFlow()
    /** [checkBuzzMembership] で参加を確かめている最中のリレー。その拒否は退会とみなさない。 */
    private val checkingBuzz = MutableStateFlow<Set<String>>(emptySet())
    private val buzzSyncMutex = Mutex()
    /** タブを開いたときに、一覧が確かに読めていなかった。読めたら合わせる。 */
    @kotlin.concurrent.Volatile
    private var buzzSyncPending = false
    private val blossomTokens = BlossomTokenCache()
    /** グループの画面を開き直したときに先に出す控え。アカウントをまたいで使わない。 */
    val roomCache = GroupRoomCache()
    private val blossomMutex = Mutex()

    /** Buzz への参加（REST）。 */
    val buzzJoin: BuzzJoinService by lazy {
        BuzzJoinService(KtorBuzzHttp(), BuzzRequestSigner { kind, tags, content -> signer.sign(content = content, kind = kind, tags = tags) })
    }

    /** 確認なしで署名できる（秘密鍵ログイン）。外部の署名器では、画像の読み込みなどを押したときだけ署名する。 */
    val signsSilently: Boolean get() = signer.method == SignerMethod.PrivateKey || signer.method == SignerMethod.Passkey
    private val mutex = Mutex()
    private var started = false

    private val consented = MutableStateFlow<Set<String>>(emptySet())
    private val _joined = MutableStateFlow<List<JoinedGroup>>(emptyList())
    /** kind 10009 の参加中のグループ（公開タグのもの）。 */
    val joinedGroups: StateFlow<List<JoinedGroup>> = _joined.asStateFlow()
    private val _pending = MutableStateFlow<Set<GroupRef>>(emptySet())
    /** 参加要求を送ったが、まだメンバーになっていないグループ。端末にだけ保存する。 */
    val pendingGroups: StateFlow<Set<GroupRef>> = _pending.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val knownGroupsKey = "chat.knownGroups.$pubkey"
    private val _groupsExpected = MutableStateFlow(false)
    /**
     * 参加中のグループがあるはず（前回の控え、または読めた一覧）。kind 10009 が読めないうちも、
     * グループのある人の画面の形を保つのに使う（docs/dm-feature-design.md §8.4.6）。
     */
    val groupsExpected: StateFlow<Boolean> = _groupsExpected.asStateFlow()
    private var groupsRetryAttempt = 0

    /** 最後に読んだ kind 10009。書き換えるときに未知のタグと暗号化された content を保つ。 */
    private var latestList: NostrEvent? = null
    /** kind 10009 を確かに読めた（書き換えてよい）。 */
    private var listConfirmed = false

    private val ready = CompletableDeferred<Unit>()
    /** セッションが終わった。終わった後に署名器を登録しない。 */
    @kotlin.concurrent.Volatile
    private var closed = false

    /** 初回だけ、保存した許可リストを読み、NIP-42 の署名器を登録し、kind 10009 を読む。 */
    fun start() {
        scope.launch {
            val first = mutex.withLock { if (started) false else true.also { started = true } }
            if (!first) return@launch
            try {
                consented.value = decodeAuthRelays(LocalSettingsStorage.getString(authKey))
                _pending.value = decodePendingGroups(LocalSettingsStorage.getString(pendingKey))
                _buzz.value = decodeAuthRelays(LocalSettingsStorage.getString(buzzKey))
                _groupsExpected.value = decodeKnownGroups(LocalSettingsStorage.getString(knownGroupsKey))
                if (closed) return@launch
                NostrRepository.installRelayAuth(
                    owner = this@ChatAccount,
                    pubkey = pubkey,
                    signer = RelayAuthSigner { url, challenge ->
                        signer.sign(content = "", kind = RELAY_AUTH_KIND, tags = authEventTags(url, challenge))
                    },
                    allowed = ::isAuthAllowed,
                )
                // 登録の途中でセッションが終わったら、登録を取り消す（close の取り消しが先に走っていても外す）。
                if (closed) NostrRepository.uninstallRelayAuth(this@ChatAccount)
            } finally {
                ready.complete(Unit)
            }
            // Buzz で作ったアカウントは kind 0 をコミュニティのリレーにしか置かないので、そのリレーを指定した取得で聞けるようにする。
            scope.launch {
                _buzz.collect { relays -> if (!closed) com.nostr.torinos.network.ProfileExtraRelays.set(this@ChatAccount, relays) }
            }
            // Buzz のリレーに自分の鍵で認証できたら、そこにある自分の kind 0 が古くないか確かめる（接続はすでにあるので増やさない）。
            scope.launch {
                var previous: Map<String, RelayAuthState> = emptyMap()
                NostrRepository.relayAuthStates.collect { states ->
                    states.forEach { (url, state) ->
                        if (state is RelayAuthState.Authenticated && state.pubkey == pubkey && url in _buzz.value) {
                            syncOwnProfileOnce(url)
                        }
                    }
                    buzzCommunitiesRemovedByRelay(previous, states, _buzz.value, pubkey, checkingBuzz.value)
                        .forEach { url -> onRemovedFromBuzz(url) }
                    previous = states
                }
            }
            refreshJoinedGroups()
        }
    }

    /**
     * NIP-42 の署名器の登録まで待つ。登録前に購読すると、認証を求めるリレーに拒否されたまま送り直されない。
     * 参加中のグループ（kind 10009）を読み終えるのは待たない。
     */
    suspend fun awaitReady() {
        start()
        ready.await()
    }

    fun close() {
        closed = true
        NostrRepository.uninstallRelayAuth(this)
        com.nostr.torinos.network.ProfileExtraRelays.clear(this)
    }

    fun isAuthAllowed(relayUrl: String): Boolean =
        relayUrl in authAllowedRelays(consented.value, _joined.value, _buzz.value, _restoringBuzz.value) ||
            relayUrl in extraAuthRelays()

    suspend fun addBuzzCommunity(relayUrl: String) {
        val url = normalizeRelayUrl(relayUrl) ?: return
        _buzz.update { it + url }
        LocalSettingsStorage.putString(buzzKey, encodeAuthRelays(_buzz.value))
        NostrRepository.onRelayAuthPolicyChanged(url)
    }

    /**
     * コミュニティから外された（AUTH が `not a relay member` で拒否された）。端末の一覧から外し、知らせる。
     * 10009 からは消さない（同期と同じく、1つのリレーの答えでほかの端末の保存を壊さない）。代わりに
     * 「メンバーでない」と控え、10009 の版が変わるまで同期で一覧に戻さない。招待で参加し直せば戻る。
     */
    private suspend fun onRemovedFromBuzz(url: String) {
        com.nostr.torinos.util.appLog("[BuzzSync] removed from $url")
        removeBuzzCommunity(url)
        val listId = mutex.withLock { latestList?.id }
        skipUntilListChanges(url, listId, "removed by the community")
        _removedBuzz.update { it + url }
    }

    /** 退会の知らせを閉じた。 */
    fun dismissRemovedBuzz(relayUrl: String) {
        _removedBuzz.update { it - relayUrl }
    }

    /** このアカウントから外す（ToriNos の保存から消すだけで、コミュニティからの退会ではない）。 */
    suspend fun removeBuzzCommunity(relayUrl: String) {
        _buzz.update { it - relayUrl }
        LocalSettingsStorage.putString(buzzKey, encodeAuthRelays(_buzz.value))
        // 外したコミュニティに、認証済みの接続を残さない。
        NostrRepository.dropRelayAuthIfDisallowed(relayUrl)
    }

    /**
     * Buzz コミュニティに参加済みかを、NIP-42 で認証して読めるかで確かめる
     * （Buzz アプリの `inspectProfile` と同じ考え方。本番の Buzz はメンバーでないと AUTH が通らない）。呼ぶ前に [allowAuth] か [addBuzzCommunity] をしておく。
     */
    suspend fun checkBuzzMembership(relayUrl: String): BuzzMembershipCheck {
        awaitReady()
        checkingBuzz.update { it + relayUrl }
        val result = try {
            fetchFiniteEvents(
            SubscriptionSpec(
                id = "buzz-check-${Random.nextLong().toULong().toString(16)}",
                // 読めるか（AUTH が通るか）だけを見る。プロフィールの取得は ProfileRepository に任せるので kind 0 は使わない。
                filters = listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), limit = 1)),
                target = RelayTarget.Explicit(setOf(relayUrl)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 20_000),
                replayCachedEvents = false,
            ),
            )
        } finally {
            checkingBuzz.update { it - relayUrl }
        }
        return when (val outcome = result.relays[relayUrl]?.outcome) {
            RelayOutcome.Eose -> BuzzMembershipCheck.Member
            is RelayOutcome.Closed -> buzzMembershipFromClosed(
                closedReason = outcome.reason,
                authFailure = (NostrRepository.relayAuthStates.value[relayUrl] as? RelayAuthState.Failed)?.reason,
            )
            else -> BuzzMembershipCheck.Unknown(outcome?.toString() ?: "応答がありません")
        }
    }

    /**
     * 添付をアップロードして、本文と `imeta` に足すものを返す。Buzz はコミュニティの `/upload`（Blossom）へ、
     * 標準の NIP-29 は ToriNos のいつもの画像アップローダーへ上げる（外部の URL は Buzz では添付として扱われないため）。
     */
    suspend fun uploadAttachment(
        relayUrl: String,
        flavor: GroupFlavor,
        bytes: ByteArray,
        mimeType: String,
        filename: String,
    ): MessageAttachment {
        if (flavor == GroupFlavor.Standard) {
            val meta = ImageUploader.uploadMedia(bytes, mimeType, signer).getOrThrow()
            return MessageAttachment(meta.url, meta.toImetaTag())
        }
        val origin = buzzHttpOrigin(relayUrl)
        val host = origin.substringAfter("://")
        // Buzz は EXIF や ICC プロファイルの入った JPEG を 422 で断るので外す(ハッシュは外した後の中身で取る)。
        val body = stripJpegMetadata(bytes) ?: bytes
        val now = kotlin.time.Clock.System.now().epochSeconds
        val hash = sha256(body).toHex()
        val auth = signer.sign(
            content = "Upload $filename",
            kind = BLOSSOM_AUTH_KIND,
            tags = blossomUploadTags(host, hash, now, 55),
            createdAt = now,
        )
        val response = withTimeout(60_000) {
            NostrRepository.httpClient.put("$origin/upload") {
                buzzUploadHeaders(nostrAuthorizationHeader(auth), hash).forEach { (name, value) -> header(name, value) }
                contentType(ContentType.parse(mimeType))
                setBody(body)
            }
        }
        val responseText = response.bodyAsText()
        check(response.status.value in 200..299) { "アップロードできませんでした（${response.status.value}）" }
        val blob = parseBuzzBlob(responseText, origin) ?: error("アップロードの応答を読めませんでした")
        return buzzAttachment(blob, filename)
    }

    /**
     * Buzz の画像（`/media/`）を読むための `Authorization` ヘッダー。ホストごとに1回署名し、期限まで使い回す。
     * NIP-FI が有効な Buzz は期限を60秒以内に制限するので、55秒にする。
     */
    suspend fun blossomAuthorization(host: String): String = blossomMutex.withLock {
        // 同時に読み込む画像が何枚あっても、署名は1回にする。
        val now = kotlin.time.Clock.System.now().epochSeconds
        blossomTokens.get(host, now)?.let { return@withLock it }
        val ttl = 55L
        val event = signer.sign(content = "Get", kind = BLOSSOM_AUTH_KIND, tags = blossomGetTags(host, now, ttl), createdAt = now)
        val header = nostrAuthorizationHeader(event)
        blossomTokens.put(host, header, now + ttl)
        header
    }

    /**
     * アイコンを読むときのヘッダー。参加中の Buzz コミュニティの `/media/` だけ認証を付け、それ以外は空。
     * 確認なしで署名できない（外部署名器）ときや署名に失敗したときは null（頭文字のアイコンにする）。
     */
    suspend fun avatarHeaders(url: String): Map<String, String>? {
        val host = buzzMediaHost(url, _buzz.value) ?: return emptyMap()
        if (!signsSilently) return null
        return try {
            mapOf("Authorization" to blossomAuthorization(host))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** ユーザーが確認ダイアログで NIP-42 の認証を許可した。 */
    suspend fun allowAuth(relayUrl: String) {
        val url = normalizeRelayUrl(relayUrl) ?: return
        consented.update { it + url }
        LocalSettingsStorage.putString(authKey, encodeAuthRelays(consented.value))
        NostrRepository.onRelayAuthPolicyChanged(url)
    }

    /** 自分の最新の kind 10009 を、自分の read リレーから読む。 */
    suspend fun refreshJoinedGroups() {
        try {
            val result = fetchFiniteEvents(
                SubscriptionSpec(
                    id = "chat-groups-${Random.nextLong().toULong().toString(16)}",
                    filters = listOf(NostrFilter(authors = listOf(pubkey), kinds = listOf(SIMPLE_GROUPS_LIST_KIND), limit = 5)),
                    target = RelayTarget.AllEnabled,
                    behavior = SubscriptionBehavior.Fetch(timeoutMillis = 8_000),
                ),
            )
            val latest = result.events
                .filter { it.pubkey == pubkey && it.kind == SIMPLE_GROUPS_LIST_KIND }
                .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            mutex.withLock {
                if (groupsListRewriteAllowed(result.allAnswered, latest != null)) listConfirmed = true
                if (latest != null && (latestList == null || latest.createdAt >= latestList!!.createdAt)) {
                    latestList = latest
                    _joined.value = parseJoinedGroups(latest.tags + privateTags(latest))
                }
            }
            applyGroupsLoad(groupsLoadOutcome(_groupsExpected.value, result.allAnswered, if (latestList != null) _joined.value else null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("ChatAccount", e, "failed to load joined groups")
            applyGroupsLoad(GroupsLoad.Retry(_groupsExpected.value))
        } finally {
            _loaded.value = true
        }
        _joined.value.forEach { NostrRepository.onRelayAuthPolicyChanged(it.ref.relayUrl) }
        if (buzzSyncPending && listConfirmed && !closed) syncBuzzCommunities()
    }

    private suspend fun applyGroupsLoad(outcome: GroupsLoad) {
        when (outcome) {
            is GroupsLoad.Known -> {
                groupsRetryAttempt = 0
                setGroupsExpected(outcome.hasGroups)
            }
            is GroupsLoad.Retry -> {
                val wait = GROUPS_RETRY_DELAYS_MILLIS.getOrNull(groupsRetryAttempt++) ?: return
                scope.launch {
                    delay(wait)
                    if (!closed) refreshJoinedGroups()
                }
            }
        }
    }

    private suspend fun setGroupsExpected(hasGroups: Boolean) {
        if (_groupsExpected.value == hasGroups) return
        _groupsExpected.value = hasGroups
        LocalSettingsStorage.putString(knownGroupsKey, encodeKnownGroups(hasGroups))
    }

    /**
     * 暗号化された項目。外部の署名器では、ユーザーの操作（[allowPrompt]）のときだけ読む
     * （起動のたびに確認を出さないため）。読まない・読めないときは空。
     */
    private suspend fun privateTags(event: NostrEvent, allowPrompt: Boolean = false): List<List<String>> {
        if (event.content.isBlank() || !(signsSilently || allowPrompt)) return emptyList()
        return try {
            val plain = signer.decrypt(event.content, pubkey)
            Json.parseToJsonElement(plain).jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun markPending(ref: GroupRef) {
        _pending.update { it + ref }
        LocalSettingsStorage.putString(pendingKey, encodePendingGroups(_pending.value))
    }

    suspend fun clearPending(ref: GroupRef) {
        if (ref !in _pending.value) return
        _pending.update { it - ref }
        LocalSettingsStorage.putString(pendingKey, encodePendingGroups(_pending.value))
    }

    /**
     * kind 10009 に [group] を足して公開する。既にあれば何もしない。
     * [userInitiated] はユーザーの操作で呼ばれた（外部の署名器に、非公開の項目を読む確認を出してよい）。
     */
    suspend fun recordJoined(group: JoinedGroup, userInitiated: Boolean = false) {
        clearPending(group.ref)
        updateList(GroupsListChange.Add(group), userInitiated)
        NostrRepository.onRelayAuthPolicyChanged(group.ref.relayUrl)
    }

    /**
     * グループから退出する（kind 9022 を送り、標準の NIP-29 なら参加中の一覧 kind 10009 からも外す）。
     * アカウントのスコープで最後まで行う。画面を閉じて呼び出し側が取り消されても、一覧の更新を途中でやめない。
     */
    suspend fun leaveGroup(ref: GroupRef, flavor: GroupFlavor): GroupLeaveOutcome = scope.async {
        when (val outcome = publishToGroup(ref, buildGroupLeaveRequest(ref.groupId))) {
            is GroupPublishOutcome.Published -> {
                roomCache.setJoinedHint(ref, false)
                if (flavor != GroupFlavor.Standard) return@async GroupLeaveOutcome.Left
                try {
                    recordLeft(ref)
                    GroupLeaveOutcome.Left
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    GroupLeaveOutcome.LeftButListNotUpdated(e.message ?: "不明なエラー")
                }
            }
            is GroupPublishOutcome.Rejected -> GroupLeaveOutcome.NotLeft(outcome.reason)
            is GroupPublishOutcome.Failed -> GroupLeaveOutcome.NotLeft(outcome.reason)
        }
    }.await()

    /** 退出はユーザーの操作。非公開の項目からも外す。 */
    suspend fun recordLeft(ref: GroupRef) {
        clearPending(ref)
        updateList(GroupsListChange.Remove(ref, keepRelays = _buzz.value), userInitiated = true)
    }

    private suspend fun updateList(change: GroupsListChange, userInitiated: Boolean) {
        // 書く直前に読み直す。起動時に読んだ版を土台にすると、その後に他のクライアントで変えた分を消してしまう。
        refreshJoinedGroups()
        check(listConfirmed) { "参加中のグループの一覧を読めなかったため、保存できませんでした" }
        mutex.withLock {
            val base = latestList
            // 暗号化項目は読めたときだけ書き換える。読めないのを空として扱うと、ほかのクライアントが入れた項目を消してしまう。
            val readPrivate: List<List<String>>? = when {
                base == null -> emptyList()
                !(signsSilently || userInitiated) && base.content.isNotBlank() -> null
                else -> readPrivateTagsStrict(base.content) { signer.decrypt(it, pubkey) }
            }
            check(readPrivate != null || change !is GroupsListChange.AddPrivateRelays) {
                "非公開の一覧を読めなかったため、保存できませんでした"
            }
            val currentPrivate = readPrivate.orEmpty()
            val update = applyGroupsListChange(base?.tags.orEmpty(), currentPrivate, change)
            if (base != null && update.publicTags == base.tags && !update.privateChanged) return
            val content = if (update.privateChanged) {
                signer.encryptToSelf(encodeTagsJson(update.privateTags))
            } else {
                base?.content.orEmpty()
            }
            val event = signer.sign(content = content, kind = SIMPLE_GROUPS_LIST_KIND, tags = update.publicTags)
            NostrRepository.publish(event)
            latestList = event
            _joined.value = parseJoinedGroups(event.tags + update.privateTags)
            // 自分で書いた版は中身が分かっているので、次にタブを開いたときに復号し直さない。
            if (readPrivate != null) {
                saveBuzzSyncState(BuzzSyncState(event.id, buzzRelayCandidates(event.tags, update.privateTags)))
            }
        }
        // 参加・退出で書き換えた一覧に合わせて、控えも直す（最後のグループを抜けたら DM が主役に戻る）。
        setGroupsExpected(_joined.value.isNotEmpty())
    }

    // ---- Buzz コミュニティの同期（docs/buzz-community-sync-design.md） ----

    /**
     * チャットタブを開いたときに呼ぶ。kind 10009 の暗号化項目の `r` と端末の一覧を合わせる。
     * アカウントのスコープで最後まで行い、同時には1回だけ走らせる。一覧が確かに読めていなければ、読めたときに行う。
     */
    fun syncBuzzCommunities() {
        buzzSyncPending = true
        scope.launch {
            if (!buzzSyncMutex.tryLock()) return@launch
            try {
                awaitReady()
                runBuzzSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("ChatAccount", e, "failed to sync buzz communities")
            } finally {
                buzzSyncMutex.unlock()
            }
        }
    }

    /** Buzz コミュニティに参加できた。10009 の暗号化項目へ書き足す（失敗しても参加は成功。次にタブを開いたときに書き直す）。 */
    fun recordBuzzJoined(relayUrl: String) {
        val url = normalizeRelayUrl(relayUrl) ?: return
        scope.launch {
            try {
                updateList(GroupsListChange.AddPrivateRelays(setOf(url)), userInitiated = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("ChatAccount", e, "failed to record buzz community")
            }
        }
    }

    private suspend fun runBuzzSync() {
        val (base, confirmed) = mutex.withLock { latestList to listConfirmed }
        if (!buzzSyncAllowed(confirmed)) return
        buzzSyncPending = false
        val stored = decodeBuzzSyncState(LocalSettingsStorage.getString(buzzSyncStateKey))
        val listId = base?.id
        val remote: Set<String> = when {
            base == null -> emptySet()
            !needsBuzzDecrypt(listId, base.content.isBlank(), stored) ->
                if (base.content.isBlank()) buzzRelayCandidates(base.tags, emptyList()) else stored!!.remote
            else -> {
                // タブを開いたのは利用者の操作なので、外部の署名器でも復号を頼んでよい（同じ版では頼まない）。
                val tags = readPrivateTagsStrict(base.content) { signer.decrypt(it, pubkey) } ?: return
                buzzRelayCandidates(base.tags, tags)
            }
        }
        if (listId != null) saveBuzzSyncState(BuzzSyncState(listId, remote))
        val notMember = decodeBuzzNotMember(LocalSettingsStorage.getString(buzzNotMemberKey))
        val plan = planBuzzSync(_buzz.value, remote, notMember, listId)
        if (plan.toPublish.isNotEmpty()) {
            try {
                updateList(GroupsListChange.AddPrivateRelays(plan.toPublish), userInitiated = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("ChatAccount", e, "failed to publish buzz communities")
            }
        }
        // 前回から確認中のまま残っているものも、今回の候補に入っていなければ外す（10009 から消えた・ほかで参加した）。
        _restoringBuzz.value = plan.toVerify
        if (plan.toVerify.isEmpty()) return
        plan.toVerify.forEach { NostrRepository.onRelayAuthPolicyChanged(it) }
        coroutineScope {
            plan.toVerify.forEach { url -> launch { verifyRestoredBuzz(url, listId) } }
        }
    }

    /**
     * 10009 にだけあった候補を確かめる。Buzz でメンバーなら端末の一覧に入れる。
     * 分からない（NIP-11 を読めない、時間切れ）ときは確認中のまま残し、次にタブを開いたときに確かめ直す。
     */
    private suspend fun verifyRestoredBuzz(url: String, listId: String?) {
        val decided = try {
            val info = RelayInformationRepository.fetch(url).getOrNull()
            when {
                info == null -> false
                groupFlavorOf(info.supportedExtensions) != GroupFlavor.Buzz -> {
                    skipUntilListChanges(url, listId, "not a buzz relay")
                    true
                }
                else -> when (val check = checkBuzzMembership(url)) {
                    BuzzMembershipCheck.Member -> { addBuzzCommunity(url); true }
                    is BuzzMembershipCheck.NotMember -> { skipUntilListChanges(url, listId, "not a member: ${check.reason}"); true }
                    is BuzzMembershipCheck.Unknown -> false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("ChatAccount", e, "failed to verify restored buzz community")
            false
        }
        if (!decided) return
        _restoringBuzz.update { it - url }
        // 確かめ終わったリレーは、参加中でなければ認証済みの接続を残さない。
        if (url !in _buzz.value) NostrRepository.dropRelayAuthIfDisallowed(url)
    }

    /** 端末の一覧に入れないと決めた候補。10009 の版が変わるまで確かめ直さない（10009 からは消さない）。 */
    private suspend fun skipUntilListChanges(url: String, listId: String?, reason: String) {
        com.nostr.torinos.util.appLog("[BuzzSync] skip $url: $reason")
        if (listId == null) return
        val current = decodeBuzzNotMember(LocalSettingsStorage.getString(buzzNotMemberKey))
        LocalSettingsStorage.putString(buzzNotMemberKey, encodeBuzzNotMember(current + (url to listId)))
    }

    private suspend fun saveBuzzSyncState(state: BuzzSyncState) {
        LocalSettingsStorage.putString(buzzSyncStateKey, encodeBuzzSyncState(state))
    }

    // ---- 管理（docs/buzz-admin-design.md） ----

    private val _deletedChannels = MutableStateFlow<Set<GroupRef>>(emptySet())
    /**
     * このセッションで削除したチャンネル（端末のメモリだけ）。チャットタブの一覧はこれを除く。
     * 控えや、答えがそろわないときに残す前回の一覧から、削除したものを出し直さないため。
     */
    val deletedChannels: StateFlow<Set<GroupRef>> = _deletedChannels.asStateFlow()

    fun markChannelDeleted(ref: GroupRef) {
        _deletedChannels.update { it + ref }
        roomCache.setJoinedHint(ref, false)
    }

    /** 招待の発行（REST、NIP-98）。 */
    private val buzzInvites: BuzzInviteService by lazy {
        BuzzInviteService(KtorBuzzHttp(), BuzzRequestSigner { kind, tags, content -> signer.sign(content = content, kind = kind, tags = tags) })
    }

    /**
     * コミュニティのメンバー一覧（kind 13534）を読む。リレーの鍵（NIP-11 の `self`）で署名された最新のものだけを使う。
     * 一覧を出さないコミュニティは [CommunityRosterLoad.None]、読めなかったときは [CommunityRosterLoad.Failed]。
     */
    suspend fun fetchCommunityRoster(relayUrl: String): CommunityRosterLoad {
        if (relayUrl !in _buzz.value) return CommunityRosterLoad.Failed("Buzz のコミュニティではありません")
        val relayKey = RelayInformationRepository.fetch(relayUrl).getOrNull()?.self
            ?: return CommunityRosterLoad.Failed("コミュニティの鍵が分かりません")
        val result = keepingRelay(relayUrl) {
            fetchFiniteEvents(
                SubscriptionSpec(
                    id = "buzz-roster-${Random.nextLong().toULong().toString(16)}",
                    filters = listOf(NostrFilter(kinds = listOf(COMMUNITY_ROSTER_KIND), authors = listOf(relayKey), limit = 1)),
                    target = RelayTarget.Explicit(setOf(relayUrl)),
                    behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                    replayCachedEvents = false,
                ),
            )
        }
        return when (val outcome = result.relays[relayUrl]?.outcome) {
            RelayOutcome.Eose -> latestCommunityRoster(result.events, relayKey)?.let(CommunityRosterLoad::Loaded) ?: CommunityRosterLoad.None
            is RelayOutcome.Closed -> CommunityRosterLoad.Failed(outcome.reason)
            // 途中で切れても、届いた一覧があれば使う。
            else -> latestCommunityRoster(result.events, relayKey)?.let(CommunityRosterLoad::Loaded)
                ?: CommunityRosterLoad.Failed("メンバーの一覧を読めませんでした")
        }
    }

    /** コミュニティ全体の命令（9031・9032）。そのコミュニティのリレーだけへ送る。 */
    suspend fun sendCommunityCommand(relayUrl: String, draft: GroupEventDraft): GroupPublishOutcome = scope.async {
        if (relayUrl !in _buzz.value) return@async GroupPublishOutcome.Failed("Buzz のコミュニティではありません")
        val event = try {
            signer.sign(content = draft.content, kind = draft.kind, tags = draft.tags)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@async GroupPublishOutcome.Failed(e.message ?: "署名できませんでした")
        }
        buzzCommandViolation(event, setOf(relayUrl), relayUrl, GroupFlavor.Buzz)?.let {
            return@async GroupPublishOutcome.Failed("送信先の検査に失敗しました（$it）")
        }
        publishKeepingRelay(relayUrl, event)
    }.await()

    /** 招待リンクを作る。招待のコードはログに出さない。 */
    suspend fun mintInvite(relayUrl: String, request: InviteRequest): InviteMintResult = scope.async {
        if (relayUrl !in _buzz.value) return@async InviteMintResult.Refused("Buzz のコミュニティではありません")
        try {
            buzzInvites.mint(relayUrl, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InviteMintResult.Refused("招待を作れませんでした（${e.message ?: "通信エラー"}）")
        }
    }.await()

    // ---- 作成（docs/chat-create-design.md） ----

    private val creationKey = "chat.pendingCreate.$pubkey"

    private val creator: GroupCreator by lazy {
        GroupCreator(
            gateway = object : GroupCreationGateway {
                override suspend fun sign(draft: GroupEventDraft): NostrEvent =
                    signer.sign(content = draft.content, kind = draft.kind, tags = draft.tags)

                override suspend fun publish(ref: GroupRef, event: NostrEvent): GroupPublishOutcome = publishSignedToGroup(ref, event)

                override suspend fun fetch(relayUrl: String, filters: List<NostrFilter>): List<NostrEvent> =
                    fetchFiniteEvents(
                        SubscriptionSpec(
                            id = "chat-create-${Random.nextLong().toULong().toString(16)}",
                            filters = filters,
                            target = RelayTarget.Explicit(setOf(relayUrl)),
                            behavior = SubscriptionBehavior.Fetch(timeoutMillis = 5_000),
                            replayCachedEvents = false,
                        ),
                    ).events

                override suspend fun relayPubkey(relayUrl: String): String? =
                    RelayInformationRepository.fetch(relayUrl).getOrNull()?.self

                override fun now(): Long = kotlin.time.Clock.System.now().epochSeconds

                override suspend fun pause(millis: Long) = delay(millis)
            },
            journal = object : CreationJournal {
                override suspend fun load(): List<PendingCreation> = decodePendingCreations(LocalSettingsStorage.getString(creationKey))
                override suspend fun save(pending: List<PendingCreation>) {
                    LocalSettingsStorage.putString(creationKey, encodePendingCreations(pending))
                    _pendingCreations.value = pending
                }
            },
            pubkey = pubkey,
        )
    }

    private val _pendingCreations = MutableStateFlow<List<PendingCreation>>(emptyList())
    /** 終わっていない作成。チャットタブの「再開」の帯に出す。 */
    val pendingCreations: StateFlow<List<PendingCreation>> = _pendingCreations.asStateFlow()

    /** 保存した「終わっていない作成」を読む。 */
    suspend fun loadPendingCreations() {
        _pendingCreations.value = creator.pending()
    }

    /**
     * グループ・チャンネルを作る。アカウントのスコープで最後まで行う（シートを閉じても続ける）。
     * 標準の NIP-29 では、作れたら参加中の一覧（kind 10009）に足す。
     */
    suspend fun createGroup(relayUrl: String, flavor: GroupFlavor, spec: NewGroupSpec): GroupCreateResult =
        runCreation(relayUrl, flavor) { creator.create(relayUrl, flavor, spec) }

    /** 終わっていない作成をやり直す。 */
    suspend fun resumeCreation(pending: PendingCreation): GroupCreateResult =
        runCreation(pending.relayUrl, pending.flavor) { creator.resume(pending) }

    /** 端末の記録だけを消す。リレーに作られたものは消さない。 */
    suspend fun discardCreation(pending: PendingCreation) = creator.discard(pending)

    private suspend fun runCreation(
        relayUrl: String,
        flavor: GroupFlavor,
        block: suspend () -> GroupCreateResult,
    ): GroupCreateResult = scope.async {
        val result = try {
            // 作成はそのリレーへの書き込みなので、求められれば NIP-42 で公開鍵を伝える（シートで案内済み）。
            if (flavor == GroupFlavor.Standard) allowAuth(relayUrl)
            keepingRelay(relayUrl) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("[Chat] create failed", e)
            return@async GroupCreateResult.Unfinished(e.message ?: "作成できませんでした")
        }
        if (result is GroupCreateResult.Created) {
            roomCache.setJoinedHint(result.ref, true)
            if (flavor == GroupFlavor.Standard) {
                try {
                    recordJoined(JoinedGroup(result.ref, result.metadata?.name), userInitiated = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 一覧に足せなくてもグループは出来ている。承認待ちとして端末に残し、次の読み込みで拾う。
                    logException("[Chat] created group not added to the list", e)
                    markPending(result.ref)
                }
            }
        }
        result
    }.await()

    /**
     * Buzz の DM を開く（kind 41010）。同じ相手の組み合わせなら既存の DM が返る。
     * 結果の ID を読めなかったときは、新しい命令で1回だけ開き直す（同じイベントの再送には ID が返らないため）。
     */
    suspend fun openBuzzDm(relayUrl: String, pubkeys: List<String>): BuzzDmOutcome = scope.async {
        if (relayUrl !in _buzz.value) return@async BuzzDmOutcome.Failed("Buzz のコミュニティではありません")
        repeat(2) {
            val event = try {
                val draft = buildBuzzOpenDm(pubkeys, pubkey)
                signer.sign(content = draft.content, kind = draft.kind, tags = draft.tags)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@async BuzzDmOutcome.Failed(e.message ?: "署名できませんでした")
            }
            buzzCommandViolation(event, setOf(relayUrl), relayUrl, GroupFlavor.Buzz)?.let {
                return@async BuzzDmOutcome.Failed("送信先の検査に失敗しました（$it）")
            }
            when (val outcome = publishKeepingRelay(relayUrl, event)) {
                is GroupPublishOutcome.Published -> buzzDmChannelId(outcome.message)?.let { id ->
                    val ref = GroupRef(relayUrl, id)
                    roomCache.setJoinedHint(ref, true)
                    return@async BuzzDmOutcome.Opened(ref)
                }
                is GroupPublishOutcome.Rejected -> return@async BuzzDmOutcome.Failed(outcome.reason)
                is GroupPublishOutcome.Failed -> return@async BuzzDmOutcome.Failed(outcome.reason)
            }
        }
        BuzzDmOutcome.Failed("リレーが DM の ID を返しませんでした")
    }.await()

    /**
     * グループのイベントに署名し、そのグループのリレーだけへ送る。`auth-required:` で拒否されたら、
     * 認証を待って1回だけ送り直す。
     */
    suspend fun publishToGroup(ref: GroupRef, draft: GroupEventDraft): GroupPublishOutcome {
        val event = try {
            signer.sign(content = draft.content, kind = draft.kind, tags = draft.tags)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return GroupPublishOutcome.Failed(e.message ?: "署名できませんでした")
        }
        return publishSignedToGroup(ref, event)
    }

    /**
     * Buzz の入力中（kind 20002、保存されない）を送る（docs/buzz-typing-indicator-design.md）。
     * 確認なしで署名できて、そのリレーに自分の鍵で認証済みのときだけ送る。入力中は数秒で古くなるので、
     * 接続を作ったり、認証を待ったり、送り直したりはしない（画面の購読が接続を保っている）。送れたら true。
     */
    suspend fun publishTyping(ref: GroupRef, threadRootId: String?): Boolean {
        if (!signsSilently) {
            com.nostr.torinos.util.typingTraceLog { "send skipped: signer ${signer.method} needs confirmation" }
            return false
        }
        val authState = NostrRepository.relayAuthStates.value[ref.relayUrl]
        val auth = authState as? RelayAuthState.Authenticated
        if (auth?.pubkey != pubkey) {
            com.nostr.torinos.util.typingTraceLog {
                "send skipped: not authenticated on ${ref.relayUrl} state=${authState?.let { it::class.simpleName }} " +
                    "known=${NostrRepository.relayAuthStates.value.keys.filter { "buzz" in it || it == ref.relayUrl }}"
            }
            return false
        }
        val draft = buildBuzzTyping(ref.groupId, threadRootId)
        val event = try {
            signer.sign(content = draft.content, kind = draft.kind, tags = draft.tags)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return false
        }
        groupPublishViolation(event, ref.groupId, setOf(ref.relayUrl), ref.relayUrl)?.let {
            com.nostr.torinos.util.typingTraceLog { "send skipped: violation $it" }
            return false
        }
        // 応答を待つ（拒否されたときに理由が分かるように）。待つのは呼び出し側の時間切れまで。
        val result = NostrRepository.publishToRelaysWithResult(
            event = event,
            relayUrls = listOf(ref.relayUrl),
            awaitAcceptance = true,
            respectRelayWritePolicy = false,
        )
        val sent = ref.relayUrl in result.succeededRelays
        com.nostr.torinos.util.typingTraceLog {
            "sent tags=${event.tags} accepted=$sent" + (result.failedRelays[ref.relayUrl]?.let { " reason=$it" } ?: "")
        }
        return sent
    }

    // ---- 在席（docs/buzz-presence-design.md） ----

    private val presencePublisher = PresencePublisher(scope, { url -> publishPresence(url) })

    /**
     * [relayUrl]（参加中の Buzz コミュニティ）で自分の在席（`online`）を送り続ける。画面が前面にある間だけ持ち、
     * 戻り値で手放す。メインスレッドから呼ぶ。確認なしで署名できないアカウントでは何もしない。
     */
    fun holdPresence(relayUrl: String): () -> Unit {
        if (!signsSilently || closed) return {}
        return presencePublisher.hold(relayUrl)
    }

    /**
     * 自分の在席を送る。参加中の Buzz コミュニティで、確認なしで署名できて、自分の鍵で認証済みのときだけ。
     * 接続を作ったり認証を待ったりはしない（[holdPresence] が5秒後に送り直す）。送れたら true。
     */
    private suspend fun publishPresence(relayUrl: String): Boolean {
        if (!signsSilently || closed || relayUrl !in _buzz.value) return false
        val auth = NostrRepository.relayAuthStates.value[relayUrl] as? RelayAuthState.Authenticated
        if (auth?.pubkey != pubkey) return false
        val draft = buildBuzzPresence(PresenceStatus.Online)
        val event = try {
            signer.sign(content = draft.content, kind = draft.kind, tags = draft.tags)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return false
        }
        val result = NostrRepository.publishToRelaysWithResult(
            event = event,
            relayUrls = listOf(relayUrl),
            awaitAcceptance = true,
            respectRelayWritePolicy = false,
        )
        return relayUrl in result.succeededRelays
    }

    /**
     * [authors] の今の在席を一括取得する（`POST /query`、NIP-98）。確認なしで署名できるときだけ。
     * 取れなかった・応答を信用できないときは null。
     */
    suspend fun fetchPresenceSnapshot(relayUrl: String, authors: List<String>, relayPubkey: String): Map<String, PresenceStatus?>? {
        if (!signsSilently || authors.isEmpty() || relayUrl !in _buzz.value) return null
        val url = "${buzzHttpOrigin(relayUrl)}/query"
        val body = buzzPresenceQueryBody(authors)
        return try {
            val auth = signer.sign(content = "", kind = HTTP_AUTH_KIND, tags = nip98Tags(url, "POST", body))
            val response = presenceHttp.postJson(url, body, nostrAuthorizationHeader(auth))
            if (!response.isSuccess) null else parseBuzzPresenceSnapshot(response.body, authors, relayPubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private val presenceHttp: BuzzHttp by lazy { KtorBuzzHttp() }

    private val emojiPaletteMutex = Mutex()
    /** Buzz のコミュニティの絵文字（リレー URL → 一覧と読んだ時刻）。このアカウントのセッションの間だけ持つ。 */
    private val emojiPalettes = mutableMapOf<String, Pair<BuzzEmojiPalette, Long>>()

    /** 前に読んだコミュニティの絵文字。読んでいなければ null。 */
    suspend fun cachedCommunityEmojis(relayUrl: String): BuzzEmojiPalette? =
        emojiPaletteMutex.withLock { emojiPalettes[relayUrl]?.first }

    /**
     * Buzz のコミュニティの絵文字を読む（docs/buzz-emoji-reaction-design.md 4.4）。
     * [maxAgeMillis] より新しく読んだものがあればそれを返す。リレーが答えなければ null（前の一覧は残す）。
     */
    suspend fun fetchCommunityEmojis(relayUrl: String, maxAgeMillis: Long): BuzzEmojiPalette? {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        emojiPaletteMutex.withLock {
            emojiPalettes[relayUrl]?.takeIf { now - it.second < maxAgeMillis }?.let { return it.first }
        }
        awaitReady()
        val result = fetchFiniteEvents(
            SubscriptionSpec(
                id = "chat-emoji-${Random.nextLong().toULong().toString(16)}",
                filters = listOf(buzzEmojiPaletteFilter()),
                target = RelayTarget.Explicit(setOf(relayUrl)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 8_000),
                replayCachedEvents = false,
            ),
        )
        if (!result.anyAnswered) return null
        val palette = unionBuzzEmojiSets(result.events)
        emojiPaletteMutex.withLock { emojiPalettes[relayUrl] = palette to now }
        return palette
    }

    // ---- 検索（docs/buzz-search-design.md） ----

    /**
     * 検索画面を開いている間、[relayUrl] を常時接続の側に置く（入力のたびにつなぎ直して認証し直さないため）。
     * 戻り値で手放す。
     */
    fun holdBuzzRelay(relayUrl: String): () -> Unit {
        if (closed || relayUrl !in _buzz.value) return {}
        val job = scope.launch { keepingRelay(relayUrl) { kotlinx.coroutines.awaitCancellation() } }
        return { job.cancel() }
    }

    private suspend fun searchFetch(relayUrl: String, filter: NostrFilter) = keepingRelay(relayUrl) {
        fetchFiniteEvents(
            SubscriptionSpec(
                id = "buzz-search-${Random.nextLong().toULong().toString(16)}",
                filters = listOf(filter),
                target = RelayTarget.Explicit(setOf(relayUrl)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                replayCachedEvents = false,
            ),
        )
    }

    /**
     * コミュニティのメッセージを検索する（NIP-50、関連度順）。リレーが答えなかった・断ったときは null。
     * 途中で切れても、届いた当たりがあればそれを返す。
     */
    suspend fun searchBuzzMessages(relayUrl: String, query: String): List<NostrEvent>? {
        if (relayUrl !in _buzz.value) return null
        // 確認なしで署名できるときは、デスクトップ版と同じ前方一致（`/query`、NIP-98）。
        // 最後の語が短いとき・外部の署名器・失敗したときは WebSocket の全文検索（公式モバイル版と同じ）。
        if (signsSilently && buzzPrefixSearchable(query)) {
            try {
                val url = "${buzzHttpOrigin(relayUrl)}/query"
                val body = buzzMessagePrefixQueryBody(query)
                val auth = signer.sign(content = "", kind = HTTP_AUTH_KIND, tags = nip98Tags(url, "POST", body))
                val response = profileSearchHttp.postJson(url, body, nostrAuthorizationHeader(auth))
                if (response.isSuccess) {
                    parseBuzzEventArray(response.body) { com.nostr.torinos.crypto.isValidEvent(it) }?.let { return it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 全文検索で探し直す。
            }
        }
        val result = searchFetch(relayUrl, buzzMessageSearchFilter(query))
        return when (result.relays[relayUrl]?.outcome) {
            RelayOutcome.Eose -> result.events
            is RelayOutcome.Closed -> null
            else -> result.events.takeIf { it.isNotEmpty() }
        }
    }

    /** `/profiles/search` が無いリレー（404 / 405）と、NIP-50 で探す期限。 */
    private val profileSearchFallback = mutableMapOf<String, Long>()
    private val profileSearchHttp: BuzzHttp by lazy { KtorBuzzHttp() }

    /**
     * コミュニティの人を名前で探す。確認なしで署名できるときは `/profiles/search`（NIP-98）、
     * それ以外とそのエンドポイントが無いリレーでは NIP-50 の kind 0。失敗したら空。
     */
    suspend fun searchBuzzPeople(relayUrl: String, query: String): List<NostrEvent> {
        if (relayUrl !in _buzz.value) return emptyList()
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val useEndpoint = signsSilently && text.length <= BUZZ_PROFILE_SEARCH_QUERY_MAX &&
            (profileSearchFallback[relayUrl]?.let { now >= it } ?: true)
        if (useEndpoint) {
            try {
                val url = "${buzzHttpOrigin(relayUrl)}/profiles/search"
                val body = buzzProfileSearchBody(text)
                val auth = signer.sign(content = "", kind = HTTP_AUTH_KIND, tags = nip98Tags(url, "POST", body))
                val response = profileSearchHttp.postJson(url, body, nostrAuthorizationHeader(auth))
                when {
                    response.isSuccess ->
                        return parseBuzzEventArray(response.body) { com.nostr.torinos.crypto.isValidEvent(it) }.orEmpty()
                    response.status == 404 || response.status == 405 ->
                        profileSearchFallback[relayUrl] = now + PROFILE_SEARCH_RETRY_MILLIS
                    else -> return emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return emptyList()
            }
        }
        return try {
            searchFetch(relayUrl, buzzPeopleSearchFilter(text)).events
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 検索の候補にするチャンネル（ChatTabViewModel.refreshBuzz と同じ読み方）。グループ情報（39000）は
     * リレーの鍵の署名だけ、参加中は自分が入っているメンバー一覧（39002）。答えがそろわなければ null。
     */
    suspend fun loadBuzzSearchChannels(relayUrl: String): List<BuzzSearchChannel>? {
        if (relayUrl !in _buzz.value) return null
        val relayKey = RelayInformationRepository.fetch(relayUrl).getOrNull()?.self ?: return null
        val result = keepingRelay(relayUrl) {
            fetchFiniteEvents(
                SubscriptionSpec(
                    id = "buzz-search-ch-${Random.nextLong().toULong().toString(16)}",
                    filters = listOf(
                        NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = listOf(relayKey), limit = 300),
                        NostrFilter(kinds = listOf(GROUP_MEMBERS_KIND), authors = listOf(relayKey), pTags = listOf(pubkey), limit = 300),
                    ),
                    target = RelayTarget.Explicit(setOf(relayUrl)),
                    behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                    replayCachedEvents = false,
                ),
            )
        }
        if (!result.allAnswered) return null
        val joined = result.events.filter { it.kind == GROUP_MEMBERS_KIND }
            .groupBy { e -> e.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) }
            .mapNotNull { (id, lists) -> lists.maxByOrNull { it.createdAt }?.takeIf { latest -> id != null && latest.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkey } }?.let { id } }
            .toSet()
        val deleted = deletedChannels.value
        return latestGroupMetadata(result.events, relayKey).values
            .filter { !it.isHidden && GroupRef(relayUrl, it.groupId) !in deleted }
            .map { BuzzSearchChannel(GroupRef(relayUrl, it.groupId), it, it.groupId in joined) }
    }

    /** 検索の当たりのチャンネルで、一覧に無かったもの（DM・一覧に載らないチャンネル）のグループ情報。 */
    suspend fun fetchBuzzChannelMetadata(relayUrl: String, ids: Collection<String>): Map<String, GroupMetadata> {
        if (ids.isEmpty() || relayUrl !in _buzz.value) return emptyMap()
        val relayKey = RelayInformationRepository.fetch(relayUrl).getOrNull()?.self ?: return emptyMap()
        val events = keepingRelay(relayUrl) {
            ids.chunked(50).flatMap { chunk ->
                fetchFiniteEvents(
                    SubscriptionSpec(
                        id = "buzz-search-md-${Random.nextLong().toULong().toString(16)}",
                        filters = listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = listOf(relayKey), dTags = chunk)),
                        target = RelayTarget.Explicit(setOf(relayUrl)),
                        behavior = SubscriptionBehavior.Fetch(timeoutMillis = 8_000),
                        replayCachedEvents = false,
                    ),
                ).events
            }
        }
        return latestGroupMetadata(events, relayKey)
    }

    /** そのコミュニティのリレーの鍵（NIP-11 の `self`）。リレーが署名した発言の作者を読むのに使う。 */
    suspend fun buzzRelayPubkey(relayUrl: String): String? = RelayInformationRepository.fetch(relayUrl).getOrNull()?.self

    /**
     * 署名済みのプロフィール（kind 0）を、参加中の Buzz コミュニティのリレーにも送る。
     * Buzz のアプリはコミュニティのリレーからしか kind 0 を読まないので、通常の書き込みリレーへの送信だけでは反映されない。
     * 保存の完了を待たせないよう、送信はセッションのスコープで続ける。
     */
    fun publishProfileToBuzzCommunities(event: NostrEvent) {
        if (event.kind != 0 || event.pubkey != pubkey) return
        scope.launch {
            // まだチャットを使っていなければ、保存済みの一覧だけを見る（Buzz がなければ start しない）。
            val relays = if (started) _buzz.value else decodeAuthRelays(LocalSettingsStorage.getString(buzzKey))
            if (relays.isEmpty() || closed) return@launch
            relays.map { url ->
                async {
                    val outcome = publishKeepingRelay(url, event)
                    if (outcome !is GroupPublishOutcome.Published) {
                        logException("Buzz profile publish failed: $url", IllegalStateException(outcome.toString()))
                    }
                }
            }.forEach { it.await() }
        }
    }

    /** このセッションで kind 0 を確かめ始めた Buzz のリレー。1つのリレーにつき1回だけ確かめる。 */
    private val profileSyncedRelays = MutableStateFlow<Set<String>>(emptySet())

    private fun syncOwnProfileOnce(relayUrl: String) {
        var first = false
        profileSyncedRelays.update { if (relayUrl in it) it else { first = true; it + relayUrl } }
        if (!first) return
        scope.launch {
            val ok = runCatching { syncOwnProfile(relayUrl) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
            // 確かめられなかったら、次に認証し直したときにもう一度試す。
            if (!ok) profileSyncedRelays.update { it - relayUrl }
        }
    }

    /**
     * [relayUrl]（Buzz のリレー）にある自分の kind 0 を読み、手元の最新版より古いか無ければ、手元の署名済みイベントをそのまま送る。
     * 署名し直さないので、外部の署名器でも確認は出ない。リレーの方が新しい（Buzz のアプリで編集した）ときはキャッシュに取り込むだけで、
     * 通常のリレーへは広げない（コミュニティの中だけのつもりのプロフィールを公開しないため）。
     * 確かめ終えたら true。
     */
    private suspend fun syncOwnProfile(relayUrl: String): Boolean = keepingRelay(relayUrl) {
        // 手元の版が古いまま送らないよう、通常のリレーの最新を先に確かめる。
        ProfileRepository.awaitFresh(pubkey, maxAgeMillis = OWN_PROFILE_MAX_AGE_MS)
        val check = ProfileRepository.fetchFromRelay(pubkey, relayUrl)
        if (check !is RelayProfileCheck.Answered) return@keepingRelay false
        // リレーの版を取り込んだ後の手元の最新と比べる。
        val resend = profileEventToResend(ProfileRepository.latestEvent(pubkey), check) ?: return@keepingRelay true
        val outcome = publishWithAuthRetry(GroupRef(relayUrl, ""), resend)
        if (outcome !is GroupPublishOutcome.Published) {
            logException("Buzz profile resend failed: $relayUrl", IllegalStateException(outcome.toString()))
            return@keepingRelay false
        }
        true
    }

    /**
     * 自分の kind 0 を、参加中の Buzz のリレーからも読んでキャッシュに入れる（プロフィール編集を開いたとき）。
     * Buzz のアプリで編集した新しい版に気づかず、古い内容で上書きしないため。
     * 認証で署名の確認が出る署名器では、すでに認証済みのリレーだけに聞く（編集画面を開くだけで確認を出さない）。
     */
    suspend fun refreshOwnProfileFromCommunities() {
        val relays = if (started) _buzz.value else decodeAuthRelays(LocalSettingsStorage.getString(buzzKey))
        if (relays.isEmpty() || closed) return
        val authStates = NostrRepository.relayAuthStates.value
        val targets = relays.filter { url ->
            signsSilently || (authStates[url] as? RelayAuthState.Authenticated)?.pubkey == pubkey
        }
        coroutineScope {
            targets.map { url ->
                async {
                    runCatching { keepingRelay(url) { ProfileRepository.fetchFromRelay(pubkey, url) } }
                        .onFailure { if (it is CancellationException) throw it }
                }
            }.forEach { it.await() }
        }
    }

    /** 署名済みのグループのイベントを、そのグループのリレーだけへ送る。作成のやり直しでは同じイベントを送り直す。 */
    private suspend fun publishSignedToGroup(ref: GroupRef, event: NostrEvent): GroupPublishOutcome {
        groupPublishViolation(event, ref.groupId, setOf(ref.relayUrl), ref.relayUrl)?.let {
            return GroupPublishOutcome.Failed("送信先の検査に失敗しました（$it）")
        }
        return publishKeepingRelay(ref.relayUrl, event)
    }

    /** リレーを常時接続の側に置いたまま送る。 */
    private suspend fun publishKeepingRelay(relayUrl: String, event: NostrEvent): GroupPublishOutcome =
        keepingRelay(relayUrl) { publishWithAuthRetry(GroupRef(relayUrl, ""), event) }

    /**
     * [block] の間、リレーを常時接続の側に置く。一時接続では NIP-42 の認証ができないため。
     * 送信や読み取りが続く処理（作成など）は全体をこれで囲み、手順ごとにつなぎ直して認証し直すのを避ける。
     */
    private suspend fun <T> keepingRelay(relayUrl: String, block: suspend () -> T): T {
        awaitReady()
        val keepAlive = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = "chat-keep-${Random.nextLong().toULong().toString(16)}",
                filters = listOf(NostrFilter(ids = listOf(KEEP_ALIVE_ID), limit = 1)),
                target = RelayTarget.Explicit(setOf(relayUrl)),
                behavior = SubscriptionBehavior.Live,
                replayCachedEvents = false,
            ),
        )
        try {
            return block()
        } finally {
            withContext(NonCancellable) { keepAlive.close() }
        }
    }

    private suspend fun publishWithAuthRetry(ref: GroupRef, event: NostrEvent): GroupPublishOutcome {
        repeat(2) { attempt ->
            val result = NostrRepository.publishToRelaysWithResult(
                event = event,
                relayUrls = listOf(ref.relayUrl),
                awaitAcceptance = true,
                respectRelayWritePolicy = false,
            )
            if (ref.relayUrl in result.succeededRelays) {
                return GroupPublishOutcome.Published(event, result.acceptedMessages[ref.relayUrl].orEmpty())
            }
            val reason = result.failedRelays[ref.relayUrl].orEmpty()
            val authRequired = reason.startsWith("auth-required")
            if (attempt == 0 && authRequired && isAuthAllowed(ref.relayUrl) &&
                NostrRepository.awaitRelayAuthenticated(ref.relayUrl, timeoutMillis = 15_000)
            ) {
                return@repeat
            }
            return if (ref.relayUrl in result.rejectedRelays) {
                GroupPublishOutcome.Rejected(reason)
            } else {
                GroupPublishOutcome.Failed(reason.ifBlank { "送信できませんでした" })
            }
        }
        return GroupPublishOutcome.Failed("送信できませんでした")
    }
}

/** Buzz へ送り直す前に、通常のリレーの自分の kind 0 を取り直す間隔。 */
private const val OWN_PROFILE_MAX_AGE_MS = 10 * 60 * 1000L

/** 何にも当たらない ID。接続を保つためだけの購読に使う。 */
private const val KEEP_ALIVE_ID = "0000000000000000000000000000000000000000000000000000000000000000"
/** `/profiles/search` が無いリレーで、NIP-50 を使い続ける間（公式の `profileSearchRetry`）。 */
private const val PROFILE_SEARCH_RETRY_MILLIS = 5 * 60 * 1000L

private fun encodeTagsJson(tags: List<List<String>>): String =
    kotlinx.serialization.json.JsonArray(
        tags.map { tag -> kotlinx.serialization.json.JsonArray(tag.map { kotlinx.serialization.json.JsonPrimitive(it) }) },
    ).toString()

internal fun encodePendingGroups(groups: Set<GroupRef>): String =
    groups.map { it.key }.sorted().joinToString("\n")

internal fun decodePendingGroups(stored: String?): Set<GroupRef> =
    stored.orEmpty().lineSequence().mapNotNull { line ->
        val input = parseGroupInput(line.trim()) as? GroupInput.Group ?: return@mapNotNull null
        input.ref
    }.toSet()
