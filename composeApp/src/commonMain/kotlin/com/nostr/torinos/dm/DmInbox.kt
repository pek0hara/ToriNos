package com.nostr.torinos.dm

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.SignerException
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.ui.chat.decodeLastRead
import com.nostr.torinos.ui.chat.encodeLastRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// DM の受信箱。docs/dm-feature-design.md §5・§8.4.4。開封した内容は端末に保存しない（メモリーだけ）。

/** 指定したリレーから有限取得する。[onEvent] は届いたそばから呼ぶ（全リレーの答えを待たない）。 */
fun interface DmEventFetcher {
    suspend fun fetch(relays: Set<String>, filters: List<NostrFilter>, onEvent: suspend (NostrEvent) -> Unit): List<NostrEvent>
}

/** 署名済みの gift wrap を指定したリレーへ送り、受理したリレーを返す。 */
fun interface DmPublisher {
    suspend fun publish(event: NostrEvent, relays: Set<String>): Set<String>
}

/** 送ったメッセージの状態（§7.1-5）。 */
sealed interface DmSendStatus {
    data object Sending : DmSendStatus
    data object Sent : DmSendStatus
    data class Failed(val reason: String) : DmSendStatus
}

sealed interface DmSendResult {
    data class Sent(val rumorId: String) : DmSendResult
    /** 相手の DM リレーが受理しなかった。同じ gift wrap で再送できる（[DmInbox.retry]）。 */
    data class Failed(val rumorId: String, val reason: String) : DmSendResult
    /** 相手が kind 10050 を公開していない。送らない（§7.1-1）。 */
    data class NoRelayList(val peers: List<String>) : DmSendResult
    /** 署名器が暗号化・署名を断った。何も送っていない。 */
    data class Refused(val message: String) : DmSendResult
    data object Empty : DmSendResult
}

/** 相手の kind 10050 を覚えておく時間（§7.1-1）。 */
const val PEER_RELAY_LIST_CACHE_SECONDS = 600L

/** 既読の時刻などの小さな設定の保存先。 */
interface DmSettings {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String?)
}

sealed interface DmInboxStatus {
    data object NotStarted : DmInboxStatus
    data object Loading : DmInboxStatus
    /** 開封中。外部 signer では時間がかかるので、件数を出す。 */
    data class Opening(val done: Int, val total: Int) : DmInboxStatus
    data object Ready : DmInboxStatus
    /** 署名器が開封を断った・応答しなかった。開封できた分は出したまま。 */
    data class Failed(val message: String) : DmInboxStatus
}

data class DmInboxState(
    val lists: DmConversationLists = DmConversationLists(),
    val status: DmInboxStatus = DmInboxStatus.NotStarted,
    /** 自分の kind 10050 がある。まだ分からなければ null。 */
    val hasRelayList: Boolean? = null,
)

/** 1回の取得で読む gift wrap の上限（§5.2）。 */
const val GIFT_WRAP_FETCH_LIMIT = 200

/** 同時に開封する件数（外部 signer の同時要求の上限、§5.4）。 */
const val DM_UNWRAP_CONCURRENCY = 4

class DmInbox internal constructor(
    private val scope: CoroutineScope,
    private val signer: AccountSigner,
    private val readRelays: () -> Set<String>,
    private val follows: StateFlow<Set<String>>,
    private val muted: StateFlow<Set<String>>,
    private val fetcher: DmEventFetcher,
    private val settings: DmSettings,
    private val publisher: DmPublisher,
    private val now: () -> Long = { kotlin.time.Clock.System.now().epochSeconds },
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    /** 相手の kind 10050 を探すときに足すリレー（相手の NIP-65 の書き込みリレーなど）。 */
    private val peerHintRelays: suspend (String) -> Set<String> = { emptySet() },
) {
    val me: String = signer.pubkey
    private val lastReadKey = "dm.lastRead.$me"
    private val baselineKey = "dm.readBaseline.$me"

    private val _state = MutableStateFlow(DmInboxState())
    val state: StateFlow<DmInboxState> = _state.asStateFlow()
    private val _messages = MutableStateFlow<List<DmMessage>>(emptyList())
    /** 開封したメッセージ（会話画面で使う）。 */
    val messages: StateFlow<List<DmMessage>> = _messages.asStateFlow()
    private val _dmRelays = MutableStateFlow<Set<String>>(emptySet())
    /** DM を読むリレー（kind 10050、無ければ読み込みリレー）。NIP-42 で認証してよいリレー。 */
    val dmRelays: StateFlow<Set<String>> = _dmRelays.asStateFlow()

    private val refreshMutex = Mutex()
    private val dataLock = Mutex()
    private var started = false
    private val opened = mutableMapOf<String, DmMessage>()
    /** 処理済み（開封できた・検証で弾いた）の gift wrap。同じものは復号しない。 */
    private val processedWraps = mutableSetOf<String>()
    private var lastWrapAt: Long? = null
    private var lastRead: Map<String, Long> = emptyMap()
    private var baseline: Long? = null
    private var ownRelaysLoaded = false
    private val peerRelayCache = mutableMapOf<String, Pair<Set<String>, Long>>()
    /** 送ったメッセージの、宛先ごとの gift wrap と送り先。再送で同じものを送る。 */
    private val deliveries = mutableMapOf<String, List<Delivery>>()
    private val _sendStatus = MutableStateFlow<Map<String, DmSendStatus>>(emptyMap())
    /** 送ったメッセージ（rumor の ID）の状態。 */
    val sendStatus: StateFlow<Map<String, DmSendStatus>> = _sendStatus.asStateFlow()

    private class Delivery(val recipient: String, val wrap: NostrEvent, val relays: Set<String>)

    /** 初回だけ読み込みを始め、フォロー・ミュートの変化で一覧を作り直す。 */
    fun start() {
        if (started) return
        started = true
        scope.launch { refresh() }
        scope.launch { combine(follows, muted) { _, _ -> }.collect { rebuild() } }
    }

    /** 始めていれば読み直す（アプリが前面に戻ったとき、チャットタブを開いたとき）。 */
    fun refreshIfStarted() {
        if (started) scope.launch { refresh() }
    }

    /** kind 10050 を読み直し、新しい gift wrap を取って開封する。 */
    suspend fun refresh() = refreshMutex.withLock {
        _state.update { it.copy(status = if (opened.isEmpty()) DmInboxStatus.Loading else it.status) }
        loadReadState()
        val targets = loadOwnRelays()
        if (targets.isEmpty()) {
            _state.update { it.copy(status = DmInboxStatus.Ready) }
            return@withLock
        }

        val since = giftWrapSince(lastWrapAt)
        // 届いたそばから開封する。認証待ちや遅いリレーの答えを待って、ほかのリレーの DM まで遅らせない。
        val arrived = Channel<NostrEvent>(Channel.UNLIMITED)
        coroutineScope {
            val opener = launch { openAsArrived(arrived) }
            try {
                val all = fetcher.fetch(
                    targets,
                    listOf(NostrFilter(kinds = listOf(GIFT_WRAP_KIND), pTags = listOf(me), since = since, limit = GIFT_WRAP_FETCH_LIMIT)),
                ) { event -> arrived.send(event) }
                // 途中で渡されなかった分（取得の作りによる）も開封する。同じものは開封側で除く。
                all.forEach { arrived.send(it) }
            } finally {
                arrived.close()
            }
            opener.join()
        }
    }

    /** 自分の kind 10050 を読み、DM を読むリレー（無ければ読み込みリレー）を決める。 */
    private suspend fun loadOwnRelays(): Set<String> {
        val read = readRelays()
        val listed = fetchRelayList(me, read)
        val targets = listed.orEmpty().ifEmpty { read }
        _dmRelays.value = targets
        ownRelaysLoaded = true
        _state.update { it.copy(hasRelayList = !listed.isNullOrEmpty()) }
        return targets
    }

    /** [author] の最新の kind 10050 の `relay`。見つからなければ null。 */
    private suspend fun fetchRelayList(author: String, relays: Set<String>): Set<String>? {
        if (relays.isEmpty()) return null
        val latest = fetcher.fetch(relays, listOf(NostrFilter(kinds = listOf(DM_RELAY_LIST_KIND), authors = listOf(author), limit = 1))) {}
            .filter { it.kind == DM_RELAY_LIST_KIND && it.pubkey == author }
            .maxByOrNull { it.createdAt }
            ?: return null
        return latest.tags.filter { it.firstOrNull() == "relay" }.mapNotNull { it.getOrNull(1)?.let(::normalizeRelayUrl) }.toSet()
    }

    private suspend fun peerRelays(peer: String): Set<String> {
        if (peer == me) {
            if (!ownRelaysLoaded) loadOwnRelays()
            return _dmRelays.value
        }
        peerRelayCache[peer]?.let { (relays, at) -> if (now() - at < PEER_RELAY_LIST_CACHE_SECONDS) return relays }
        val relays = fetchRelayList(peer, readRelays() + peerHintRelays(peer)).orEmpty()
        peerRelayCache[peer] = relays to now()
        return relays
    }

    /**
     * [peers] に [content] を送る（§7.1）。相手宛てと自分宛ての写しを包み、それぞれの DM リレーへ送る。
     * 送ったメッセージは受理を待たずに会話へ出し、状態を [sendStatus] で示す。
     */
    suspend fun send(peers: List<String>, content: String): DmSendResult {
        if (content.isBlank()) return DmSendResult.Empty
        val recipients = peers.distinct()
        val relaysByPeer = recipients.filter { it != me }.associateWith { peerRelays(it) }
        val missing = relaysByPeer.filterValues { it.isEmpty() }.keys.toList()
        if (missing.isNotEmpty()) return DmSendResult.NoRelayList(missing)
        val selfRelays = peerRelays(me)

        val rumor = buildDmRumor(me, recipients, content, now())
        val list = try {
            relaysByPeer.map { (peer, relays) -> Delivery(peer, wrapRumor(rumor, peer, signer, now(), random), relays) } +
                listOfNotNull(selfRelays.takeIf { it.isNotEmpty() }?.let { Delivery(me, wrapRumor(rumor, me, signer, now(), random), it) })
        } catch (e: SignerException) {
            return DmSendResult.Refused(e.message ?: "署名器が断りました")
        }
        dataLock.withLock {
            opened[rumor.id] = rumor.toDmMessage()
            // 自分宛ての写しを後で取得しても、開封し直さない。
            list.filter { it.recipient == me }.forEach { processedWraps += it.wrap.id }
            deliveries[rumor.id] = list
        }
        rebuild()
        return deliver(rumor.id)
    }

    /** 失敗したメッセージを、同じ gift wrap で送り直す（作り直すと相手に2通届く）。 */
    fun retry(rumorId: String) {
        scope.launch { deliver(rumorId) }
    }

    private suspend fun deliver(rumorId: String): DmSendResult {
        val list = dataLock.withLock { deliveries[rumorId] } ?: return DmSendResult.Failed(rumorId, "送信の控えがありません")
        _sendStatus.update { it + (rumorId to DmSendStatus.Sending) }
        val accepted = coroutineScope {
            list.map { delivery -> async { delivery to runCatching { publisher.publish(delivery.wrap, delivery.relays) }.getOrDefault(emptySet()) } }.awaitAll()
        }
        // 相手全員の DM リレーのどれかが受理したら送信済み。相手がいない（自分へのメモ）なら写しの受理で決める。
        val needed = accepted.filter { (delivery, _) -> delivery.recipient != me }.ifEmpty { accepted }
        val ok = needed.isNotEmpty() && needed.all { (_, relays) -> relays.isNotEmpty() }
        val status = if (ok) DmSendStatus.Sent else DmSendStatus.Failed("相手の DM リレーが受理しませんでした")
        _sendStatus.update { it + (rumorId to status) }
        return if (ok) DmSendResult.Sent(rumorId) else DmSendResult.Failed(rumorId, (status as DmSendStatus.Failed).reason)
    }

    /**
     * 届いた gift wrap を、[DM_UNWRAP_CONCURRENCY] 件ずつ開封して一覧に出す。同じ gift wrap（複数のリレーから
     * 届いたもの、処理済みのもの）は開封しない。署名器が断ったら以降は開封せず、次の読み直しに回す。
     */
    private suspend fun openAsArrived(arrived: Channel<NostrEvent>) {
        val seen = mutableSetOf<String>()
        var done = 0
        var total = 0
        var failure: String? = null
        while (true) {
            val batch = mutableListOf(arrived.receiveCatching().getOrNull() ?: break)
            while (batch.size < DM_UNWRAP_CONCURRENCY) batch += arrived.tryReceive().getOrNull() ?: break
            if (failure != null) continue
            val fresh = dataLock.withLock {
                batch.filter { it.kind == GIFT_WRAP_KIND && it.id !in processedWraps && seen.add(it.id) }
            }.sortedByDescending { it.createdAt }
            if (fresh.isEmpty()) continue
            total += fresh.size
            _state.update { it.copy(status = DmInboxStatus.Opening(done, total)) }
            val results = try {
                coroutineScope { fresh.map { wrap -> async { wrap to unwrapGiftWrap(wrap, signer) } }.awaitAll() }
            } catch (e: SignerException) {
                failure = e.message ?: "DM を開封できませんでした"
                _state.update { it.copy(status = DmInboxStatus.Failed(failure!!)) }
                continue
            }
            dataLock.withLock {
                results.forEach { (wrap, result) ->
                    processedWraps += wrap.id
                    lastWrapAt = maxOf(lastWrapAt ?: wrap.createdAt, wrap.createdAt)
                    if (result is UnwrapResult.Opened) opened[result.rumor.id] = result.rumor.toDmMessage()
                }
            }
            done += fresh.size
            rebuild()
            _state.update { it.copy(status = DmInboxStatus.Opening(done, total)) }
        }
        rebuild()
        if (failure == null) _state.update { it.copy(status = DmInboxStatus.Ready) }
    }

    /** 既読の時刻と、初めて受信した時刻を読む。初めてなら今を基準として保存する。 */
    private suspend fun loadReadState() {
        val storedRead = decodeLastRead(settings.get(lastReadKey))
        dataLock.withLock { lastRead = mergeLastRead(lastRead, storedRead) }
        if (baseline == null) {
            val stored = settings.get(baselineKey)?.toLongOrNull()
            baseline = stored ?: now().also { settings.put(baselineKey, it.toString()) }
        }
    }

    /** 会話を開いた。いまの最新のメッセージまでを既読にする。 */
    fun markRead(key: String) {
        scope.launch {
            val until = dataLock.withLock {
                val latest = opened.values.filter { dmConversationKey(it.peers(me)) == key }.maxOfOrNull { it.createdAt } ?: 0L
                maxOf(latest, now()).also { lastRead = lastRead + (key to it) }
            }
            rebuild()
            // 保存済みの既読と合わせてから書く（ほかの会話の分を消さない）。
            val merged = mergeLastRead(decodeLastRead(settings.get(lastReadKey)), mapOf(key to until))
            dataLock.withLock { lastRead = mergeLastRead(lastRead, merged) }
            settings.put(lastReadKey, encodeLastRead(dataLock.withLock { lastRead }))
        }
    }

    private suspend fun rebuild() {
        val (lists, all) = dataLock.withLock {
            buildDmConversations(opened.values, me, follows.value, muted.value, lastRead, baseline ?: Long.MAX_VALUE) to opened.values.toList()
        }
        _messages.value = all
        _state.update { it.copy(lists = lists) }
    }
}

/** 既読の時刻などを、アプリの設定（[com.nostr.torinos.network.LocalSettingsStorage]）に置く。 */
internal object LocalDmSettings : DmSettings {
    override suspend fun get(key: String): String? = com.nostr.torinos.network.LocalSettingsStorage.getString(key)
    override suspend fun put(key: String, value: String?) = com.nostr.torinos.network.LocalSettingsStorage.putString(key, value)
}

private fun mergeLastRead(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
    (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0L, b[it] ?: 0L) }
