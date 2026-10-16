package com.nostr.torinos.account.nip46

import com.nostr.torinos.account.SignerException
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.generatePrivateKey
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.model.buildCloseMessage
import com.nostr.torinos.model.buildEventMessage
import com.nostr.torinos.model.buildReqMessage
import com.nostr.torinos.network.NostrRelay
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import com.nostr.torinos.util.signerTraceLog
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** NIP-46 の要求・応答に使う kind。 */
internal const val NIP46_KIND = 24133

/** 署名器とのやり取りに使うリレー接続。テストでは偽物に差し替える。 */
internal interface Nip46Transport {
    /** 通信用の公開鍵宛ての kind 24133（署名は受信時に検証済み）。 */
    val incoming: Flow<NostrEvent>
    fun start(scope: CoroutineScope)
    suspend fun publish(event: NostrEvent)
    fun close()
}

/**
 * NIP-46 用に、利用者のリレー設定とは別に接続する。
 * 接続のたびに `#p=[通信用の公開鍵]` の購読を張り直し、同じイベントは1回だけ流す。
 */
internal class RelayNip46Transport(
    relayUrls: List<String>,
    private val clientPubkey: String,
    httpClient: HttpClient,
) : Nip46Transport {
    private val relays = relayUrls.map { NostrRelay(it, httpClient) }
    private val subscriptionId = "nip46-${clientPubkey.take(8)}-${generatePrivateKey().toHex().take(8)}"
    private val since = Clock.System.now().epochSeconds - 60
    private val _incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
    override val incoming: Flow<NostrEvent> = _incoming.asSharedFlow()
    private val jobs = mutableListOf<Job>()
    private val seenLock = SynchronousLock()
    private val seen = LinkedHashSet<String>()

    override fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        signerTraceLog { "nip46 transport start relays=${relays.map { it.url }}" }
        val filter = NostrFilter(kinds = listOf(NIP46_KIND), pTags = listOf(clientPubkey), since = since)
        relays.forEach { relay ->
            jobs += scope.launch {
                relay.messages.collect { message ->
                    if (message !is RelayMessage.Event || message.subscriptionId != subscriptionId) return@collect
                    val event = message.event
                    if (event.kind != NIP46_KIND) return@collect
                    val isNew = seenLock.withLock {
                        seen.add(event.id).also { if (seen.size > 500) seen.remove(seen.first()) }
                    }
                    if (isNew) {
                        signerTraceLog { "nip46 event from=${event.pubkey.take(8)} relay=${relay.url}" }
                        _incoming.emit(event)
                    }
                }
            }
            jobs += scope.launch {
                relay.connected.collect { generation -> relay.sendControl(buildReqMessage(subscriptionId, filter), generation) }
            }
            relay.connect(scope)
        }
    }

    override suspend fun publish(event: NostrEvent) {
        val message = buildEventMessage(event)
        relays.forEach { it.send(message) }
    }

    override fun close() {
        relays.forEach { relay ->
            relay.connectedGeneration()?.let { relay.sendControl(buildCloseMessage(subscriptionId), it) }
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        relays.forEach { it.disconnect() }
    }
}

internal data class Nip46Response(val id: String, val result: String?, val error: String?)

private val nip46Json = Json { ignoreUnknownKeys = true }

/** 応答の JSON を読む。`result` に JSON オブジェクトを返す署名器もあるので、そのときは文字列に戻す。 */
internal fun parseNip46Response(plaintext: String): Nip46Response? = runCatching {
    val obj = nip46Json.parseToJsonElement(plaintext).jsonObject
    fun text(name: String): String? = when (val value = obj[name]) {
        null -> null
        is JsonPrimitive -> value.contentOrNull
        else -> value.toString()
    }
    Nip46Response(id = text("id") ?: return@runCatching null, result = text("result"), error = text("error"))
}.getOrNull()

internal fun buildNip46Request(id: String, method: String, params: List<String>): String = buildJsonObject {
    put("id", id)
    put("method", method)
    put("params", JsonArray(params.map(::JsonPrimitive)))
}.toString()

/** 署名器のエラー文を [SignerException] にする。 */
internal fun nip46Failure(error: String): SignerException {
    val lower = error.lowercase()
    return if (listOf("reject", "denied", "not allowed", "unauthorized", "forbidden").any { it in lower }) {
        SignerException.Rejected()
    } else {
        SignerException.Unavailable("署名器のエラー: $error")
    }
}

/**
 * NIP-46 のクライアント。要求を通信用の鍵で NIP-44 暗号化・署名して送り、同じ ID の応答を待つ。
 *
 * - 応答がなければ [requestTimeoutMillis] で [SignerException.Timeout]。自動で再送しない。
 * - `auth_url` が返ったら [onAuthUrl] で知らせ、承認を [approvalTimeoutMillis] まで待つ。
 * - 同時に送る要求は4件まで。
 */
internal class Nip46Client(
    private val clientSecretKey: String,
    remoteSignerPubkey: String?,
    private val transport: Nip46Transport,
    private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long = 30_000L,
    private val approvalTimeoutMillis: Long = 300_000L,
    private val onAuthUrl: (String?) -> Unit = {},
    private val nextId: () -> String = { generatePrivateKey().toHex().take(16) },
) {
    val clientPubkey: String = derivePublicKey(clientSecretKey.fromHex()).toHex()

    private val lock = SynchronousLock()
    private var remotePubkey: String? = remoteSignerPubkey
    val remoteSignerPubkey: String? get() = lock.withLock { remotePubkey }

    private val waiters = mutableMapOf<String, Channel<Nip46Response>>()
    // 接続を待ち始める前に届いた `connect` の応答も受け取れるよう、少し残す。
    private val unmatched = MutableSharedFlow<Pair<String, Nip46Response>>(replay = 8, extraBufferCapacity = 16)
    private val limiter = Semaphore(4)
    private var collector: Job? = null

    fun start() {
        if (collector != null) return
        collector = scope.launch { transport.incoming.collect(::handle) }
        transport.start(scope)
    }

    fun close() {
        collector?.cancel()
        collector = null
        transport.close()
        lock.withLock { waiters.values.toList().also { waiters.clear() } }.forEach { it.close() }
    }

    private suspend fun handle(event: NostrEvent) {
        val remote = remoteSignerPubkey
        if (remote != null && event.pubkey != remote) return
        val plaintext = runCatching { Nip44.decrypt(event.content, clientSecretKey, event.pubkey) }.getOrNull() ?: run {
            signerTraceLog { "nip46 drop: cannot decrypt from=${event.pubkey.take(8)}" }
            return
        }
        val response = parseNip46Response(plaintext) ?: run {
            signerTraceLog { "nip46 drop: not a response from=${event.pubkey.take(8)}" }
            return
        }
        val waiter = lock.withLock { waiters[response.id] }
        signerTraceLog {
            val result = when {
                response.result == null -> "null"
                response.result == "ack" -> "ack"
                response.result == "auth_url" -> "auth_url"
                else -> "len=${response.result.length}"
            }
            "nip46 response id=${response.id} matched=${waiter != null} result=$result error=${response.error != null}"
        }
        if (waiter != null) waiter.trySend(response) else unmatched.emit(event.pubkey to response)
    }

    /**
     * `nostrconnect://` で、署名器が `connect` の応答として [secret] を返してくるのを待つ。
     * 返してきた公開鍵を署名器として以後の通信に使う。
     */
    suspend fun awaitConnection(secret: String, timeoutMillis: Long): String {
        val (pubkey, _) = withTimeoutOrNull(timeoutMillis) {
            unmatched.first { (_, response) -> response.result == secret }
        } ?: throw SignerException.Timeout()
        lock.withLock { remotePubkey = pubkey }
        signerTraceLog { "nip46 nostrconnect accepted signer=${pubkey.take(8)}" }
        return pubkey
    }

    private suspend fun awaitResponse(channel: Channel<Nip46Response>): String {
        var timeout = requestTimeoutMillis
        while (true) {
            val response = withTimeoutOrNull(timeout) { channel.receive() } ?: throw SignerException.Timeout()
            if (response.result == "auth_url" && !response.error.isNullOrBlank()) {
                // 署名器での承認を待つ。承認されると同じ ID でもう一度応答が来る。
                signerTraceLog { "nip46 auth_url id=${response.id}" }
                onAuthUrl(response.error)
                timeout = approvalTimeoutMillis
                continue
            }
            onAuthUrl(null)
            response.error?.takeIf { it.isNotBlank() }?.let { throw nip46Failure(it) }
            return response.result ?: throw SignerException.InvalidResponse("結果がありません")
        }
    }

    suspend fun request(method: String, params: List<String>): String = limiter.withPermit {
        val remote = remoteSignerPubkey ?: throw SignerException.Unavailable("署名器に接続していません")
        val id = nextId()
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        signerTraceLog { "nip46 request id=$id method=$method signer=${remote.take(8)}" }
        val channel = Channel<Nip46Response>(capacity = 4)
        lock.withLock { waiters[id] = channel }
        try {
            val content = Nip44.encrypt(buildNip46Request(id, method, params), clientSecretKey, remote)
            val event = signEvent(
                privateKeyHex = clientSecretKey,
                content = content,
                kind = NIP46_KIND,
                tags = listOf(listOf("p", remote)),
            )
            transport.publish(event)
            awaitResponse(channel).also {
                signerTraceLog { "nip46 response id=$id method=$method ok ${started.elapsedNow().inWholeMilliseconds}ms" }
            }
        } catch (e: SignerException) {
            signerTraceLog { "nip46 response id=$id method=$method ${e::class.simpleName} ${started.elapsedNow().inWholeMilliseconds}ms" }
            throw e
        } finally {
            lock.withLock { waiters.remove(id) }
            channel.close()
        }
    }
}

/** 要求の本文を読むテスト用の補助。 */
internal fun decodeNip46Request(plaintext: String): Triple<String, String, List<String>>? = runCatching {
    val obj: JsonObject = nip46Json.parseToJsonElement(plaintext).jsonObject
    val params = (obj["params"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    Triple(obj["id"]!!.jsonPrimitive.content, obj["method"]!!.jsonPrimitive.content, params)
}.getOrNull()
