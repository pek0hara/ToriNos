package com.nostr.torinos.account.nip46

import com.nostr.torinos.account.AccountSessionLease
import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.account.InteractiveSigner
import com.nostr.torinos.account.SessionBoundSigner
import com.nostr.torinos.account.SignerAttention
import com.nostr.torinos.account.SignerCapability
import com.nostr.torinos.account.SignerCredential
import com.nostr.torinos.account.SignerException
import com.nostr.torinos.account.SignerMethod
import com.nostr.torinos.account.UnsignedEvent
import com.nostr.torinos.account.checkSignedResponse
import com.nostr.torinos.account.currentEpochSeconds
import com.nostr.torinos.account.parseSignedEventJson
import com.nostr.torinos.account.toJson
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.generatePrivateKey
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.signerTraceLog
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ToriNos が署名・暗号化を頼む kind と操作。接続時に署名器へ伝え、署名器側で許可の範囲を決めてもらう。
 * 新しい kind に署名するときは、ここにも足す（テストで漏れを確かめる）。
 */
internal val NIP46_SIGNED_KINDS = listOf(
    0, 1, 3, 5, 6, 7, 8, 16, 40, 41, 42, 43, 1111, 1984,
    10000, 10002, 10005, 10008, 10030, 22242, 27235, 30009, 30023, 30315, 31234,
    // チャット（NIP-29 と Buzz）: メッセージ・スレッド・参加と退出・参加中の一覧・Blossom の認証・Buzz の編集とフォーラム。
    9, 11, 9021, 9022, 10009, 24242, 40003, 45001, 45003,
    // チャットの作成: グループの作成と情報の編集（NIP-29）、Buzz の DM を開く命令。
    9002, 9007, 41010,
    // Buzz の管理: チャンネルのメンバー・発言の削除・チャンネルの削除、コミュニティのメンバー（docs/buzz-admin-design.md）。
    9000, 9001, 9005, 9008, 9031, 9032,
)

internal val NIP46_PERMISSIONS: String =
    (NIP46_SIGNED_KINDS.map { "sign_event:$it" } + listOf("nip44_encrypt", "nip44_decrypt")).joinToString(",")

/** `nostrconnect://` で ToriNos が指定する接続先。 */
internal val NOSTR_CONNECT_DEFAULT_RELAYS = listOf("wss://relay.nsec.app", "wss://nos.lol")

internal fun clientPubkeyOf(clientSecretKey: String): String = derivePublicKey(clientSecretKey.fromHex()).toHex()

internal fun newNip46Transport(relays: List<String>, clientPubkey: String): Nip46Transport =
    RelayNip46Transport(relays, clientPubkey, NostrRepository.httpClient)

/**
 * リモート署名器にリレー経由で署名と暗号化を頼む。接続はセッションの終了で閉じる。
 *
 * バックグラウンド中や回線の切り替えで裏で切れた接続は、接続中に見えたまま何も届かず、自分では気づけない。
 * そこで、応答がなかったときと前面に戻ったときに接続を古いものとし、待っている要求がなくなったら作り直す。
 */
internal class Nip46AccountSigner(
    override val pubkey: String,
    credential: SignerCredential.Nip46,
    private val lease: AccountSessionLease,
    private val transportFactory: (List<String>, String) -> Nip46Transport = ::newNip46Transport,
    scope: CoroutineScope? = null,
) : AccountSigner, InteractiveSigner, SessionBoundSigner {
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope = scope == null
    private val _attention = MutableStateFlow<SignerAttention?>(null)
    override val attention: StateFlow<SignerAttention?> = _attention.asStateFlow()
    private val _attentionRequests = MutableStateFlow(0L)
    // 応答がないたびに増やし、「後で」で閉じた帯を出し直す。
    override val attentionRequests: StateFlow<Long> = _attentionRequests.asStateFlow()
    private val relays = credential.relays
    private val clientSecretKey = credential.clientSecretKey
    private val remoteSignerPubkey = credential.remoteSignerPubkey
    /** [client] と [stale] の付け替えを1つずつ行う。 */
    private val clientLock = SynchronousLock()
    private var client = newClient()
    /** 今の接続が切れているかもしれない。待っている要求がなくなったら作り直す。 */
    private var stale = false

    override val method: SignerMethod get() = SignerMethod.Nip46
    override val capabilities: Set<SignerCapability> = setOf(SignerCapability.Nip44, SignerCapability.Nip04)

    private fun newClient(): Nip46Client = Nip46Client(
        clientSecretKey = clientSecretKey,
        remoteSignerPubkey = remoteSignerPubkey,
        transport = transportFactory(relays, clientPubkeyOf(clientSecretKey)),
        scope = this.scope,
        onAuthUrl = { url ->
            if (url != null) _attention.value = SignerAttention.ApprovalRequired(url)
            else if (_attention.value is SignerAttention.ApprovalRequired) _attention.value = null
        },
    ).also { it.start() }

    /** 古いとした接続は、待っている要求がなければここで作り直す。 */
    private fun currentClient(): Nip46Client = clientLock.withLock {
        if (stale && !client.hasPendingRequests) replaceClientLocked("stale")
        client
    }

    private fun replaceClientLocked(reason: String) {
        signerTraceLog { "nip46 rebuild connection ($reason)" }
        client.close()
        client = newClient()
        stale = false
    }

    private suspend fun call(method: String, params: List<String>): String {
        lease.ensureActive()
        return try {
            currentClient().request(method, params).also {
                if (_attention.value is SignerAttention.Disconnected) _attention.value = null
            }
        } catch (e: SignerException.Timeout) {
            clientLock.withLock { stale = true }
            _attention.value = SignerAttention.Disconnected("署名器から応答がありません")
            _attentionRequests.update { it + 1 }
            throw e
        }.also { lease.ensureActive() }
    }

    override suspend fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent {
        val request = UnsignedEvent(pubkey, createdAt ?: currentEpochSeconds(), kind, tags, content)
        val signed = parseSignedEventJson(call("sign_event", listOf(request.toJson())))
        return checkSignedResponse(request, signed)
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkey: String): String =
        call("nip44_encrypt", listOf(peerPubkey, plaintext))

    override suspend fun nip44Decrypt(payload: String, peerPubkey: String): String =
        call("nip44_decrypt", listOf(peerPubkey, payload))

    override suspend fun nip04Encrypt(plaintext: String, peerPubkey: String): String =
        call("nip04_encrypt", listOf(peerPubkey, plaintext))

    override suspend fun nip04Decrypt(payload: String, peerPubkey: String): String =
        call("nip04_decrypt", listOf(peerPubkey, payload))

    /** 接続を作り直し、応答があるかを確かめる。 */
    override suspend fun reconnect() {
        clientLock.withLock { replaceClientLocked("reconnect") }
        call("ping", emptyList())
        _attention.value = null
    }

    /** 前面に戻った。バックグラウンド中に切れていても気づけないので、作り直す（要求を待っていれば、その後で）。 */
    override fun onAppForeground() {
        clientLock.withLock {
            stale = true
            if (!client.hasPendingRequests) replaceClientLocked("foreground")
        }
    }

    override fun close() {
        clientLock.withLock { client.close() }
        if (ownsScope) scope.cancel()
    }
}

/** NIP-46 でログインするときの接続。成功したら閉じて、アカウントの情報だけを返す。 */
internal object Nip46Login {
    data class Connected(val pubkey: String, val credential: SignerCredential.Nip46)

    /** 貼り付けた `bunker://` で接続し、利用者の公開鍵を受け取る。 */
    suspend fun connectBunker(
        uri: BunkerUri,
        onAuthUrl: (String?) -> Unit = {},
        transportFactory: (List<String>, String) -> Nip46Transport = ::newNip46Transport,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): Connected {
        val clientSecretKey = generatePrivateKey().toHex()
        val client = newLoginClient(clientSecretKey, uri.remoteSignerPubkey, uri.relays, onAuthUrl, transportFactory, scope)
        try {
            val result = client.request("connect", listOf(uri.remoteSignerPubkey, uri.secret.orEmpty(), NIP46_PERMISSIONS))
            if (result != "ack" && result != uri.secret) throw SignerException.InvalidResponse("接続の応答が正しくありません")
            return Connected(
                pubkey = requestUserPubkey(client),
                credential = SignerCredential.Nip46(clientSecretKey, uri.remoteSignerPubkey, uri.relays),
            )
        } finally {
            client.close()
            scope.cancel()
        }
    }

    /** `nostrconnect://` を作り、署名器が接続してくるのを待つ。 */
    class PendingNostrConnect internal constructor(
        val uri: String,
        private val relays: List<String>,
        private val clientSecretKey: String,
        private val secret: String,
        private val client: Nip46Client,
        private val scope: CoroutineScope,
    ) {
        suspend fun await(timeoutMillis: Long = 300_000L): Connected {
            try {
                val remote = client.awaitConnection(secret, timeoutMillis)
                return Connected(requestUserPubkey(client), SignerCredential.Nip46(clientSecretKey, remote, relays))
            } finally {
                cancel()
            }
        }

        fun cancel() {
            client.close()
            scope.cancel()
        }
    }

    fun startNostrConnect(
        relays: List<String> = NOSTR_CONNECT_DEFAULT_RELAYS,
        onAuthUrl: (String?) -> Unit = {},
        transportFactory: (List<String>, String) -> Nip46Transport = ::newNip46Transport,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): PendingNostrConnect {
        val clientSecretKey = generatePrivateKey().toHex()
        val secret = generatePrivateKey().toHex().take(16)
        val client = newLoginClient(clientSecretKey, null, relays, onAuthUrl, transportFactory, scope)
        val uri = buildNostrConnectUri(client.clientPubkey, relays, secret, NIP46_PERMISSIONS)
        return PendingNostrConnect(uri, relays, clientSecretKey, secret, client, scope)
    }

    private fun newLoginClient(
        clientSecretKey: String,
        remoteSignerPubkey: String?,
        relays: List<String>,
        onAuthUrl: (String?) -> Unit,
        transportFactory: (List<String>, String) -> Nip46Transport,
        scope: CoroutineScope,
    ): Nip46Client {
        return Nip46Client(
            clientSecretKey = clientSecretKey,
            remoteSignerPubkey = remoteSignerPubkey,
            transport = transportFactory(relays, clientPubkeyOf(clientSecretKey)),
            scope = scope,
            // 接続時は利用者が署名器で承認することが多いので、応答を長めに待つ。
            requestTimeoutMillis = 120_000L,
            onAuthUrl = onAuthUrl,
        ).also { it.start() }
    }

    private suspend fun requestUserPubkey(client: Nip46Client): String {
        val pubkey = client.request("get_public_key", emptyList()).trim().lowercase()
        if (!isHex64(pubkey)) throw SignerException.InvalidResponse("公開鍵の形式が正しくありません")
        return pubkey
    }
}
