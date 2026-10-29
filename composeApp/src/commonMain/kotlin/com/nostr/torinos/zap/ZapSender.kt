package com.nostr.torinos.zap

import com.nostr.torinos.crypto.sha256
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** 送信中の Zap の段階。⚡の中の進み具合に使う。 */
sealed interface ZapProgress {
    data object ResolvingEndpoint : ZapProgress
    data object RequestingInvoice : ZapProgress
    /** ウォレットへ渡したあと、レシートを待っている。 */
    data class AwaitingPayment(val invoice: String) : ZapProgress
}

/** 画面に伝えること。 */
sealed interface ZapEvent {
    /** 請求書をウォレットアプリへ渡す。 */
    data class OpenWallet(val eventId: String, val invoice: String) : ZapEvent
    data class Message(val text: String) : ZapEvent
}

/** 送信に使う外の機能。テストで差し替える。 */
internal class ZapSenderDependencies(
    val sign: suspend (content: String, kind: Int, tags: List<List<String>>) -> NostrEvent,
    /** ⚡を押した時点のプロフィール。古ければ取り直す。 */
    val profileOf: suspend (pubkey: String) -> NostrProfile?,
    val endpointOf: suspend (url: String) -> PayEndpoint,
    val httpGet: suspend (url: String) -> String,
    val ownReadRelays: () -> List<String>,
    val authorReadRelays: (pubkey: String) -> List<String>,
    /** [requestId] のレシートのうち、[zapperPubkey]（受け取り先の `nostrPubkey`）が署名したものが [relays] に届くまで待つ。 */
    val awaitReceipt: suspend (relays: List<String>, target: ZapTarget, requestId: String, zapperPubkey: String, since: Long) -> Unit,
    val nowMillis: () -> Long,
    val log: (String) -> Unit = {},
)

/**
 * Zap の送信（設計 §8.1）。アカウントのセッションのスコープで動き、セッションが閉じれば止まる。
 *
 * 受け取り先の解決 → Zap リクエストの署名 → 請求書の取得と検査 → ウォレットへ渡す → レシートを待つ。
 * 請求書を受け取るまでは [cancel] で取り消せる。ウォレットへ渡したあとは取り消さない。
 */
class ZapSender internal constructor(
    private val scope: CoroutineScope,
    private val deps: ZapSenderDependencies,
    private val confirmTimeoutMillis: Long = 3 * 60 * 1_000L,
    private val maxAwaiting: Int = 3,
) {
    private class Running(val job: Job, var deadline: Long = Long.MAX_VALUE, var awaitingSince: Long = 0)

    private val lock = SynchronousLock()
    private val running = mutableMapOf<String, Running>()
    private val _progress = MutableStateFlow<Map<String, ZapProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ZapProgress>> = _progress.asStateFlow()

    private val _zappedByMe = MutableStateFlow<Set<String>>(emptySet())
    /** このセッションで支払いを確認できた投稿。 */
    val zappedByMe: StateFlow<Set<String>> = _zappedByMe.asStateFlow()

    private val _events = MutableSharedFlow<ZapEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ZapEvent> = _events.asSharedFlow()

    /**
     * 進み具合を変える。[lock]の中では呼ばない（Main.immediate の collector がその場で再開して [send] や [cancel] へ戻ると、
     * iOS の再入できないロックで止まるため。SessionSignalMailbox と同じ決まり）。
     */
    private fun setProgress(eventId: String, value: ZapProgress?) {
        _progress.update { if (value == null) it - eventId else it + (eventId to value) }
    }

    /** 受け取り先の応答（詳細シートで額の範囲とコメントの上限を出すため）。 */
    suspend fun endpointFor(target: ZapTarget): PayEndpoint {
        val address = deps.profileOf(target.authorPubkey)?.zapRecipientAddress() ?: throw ZapException(NO_ADDRESS_MESSAGE)
        return deps.endpointOf(address.url)
    }

    fun isRunning(eventId: String): Boolean = eventId in _progress.value

    /** 送信中なら取り消す。ウォレットへ渡したあと（支払い待ち）は待つのをやめるだけ。 */
    fun cancel(eventId: String) {
        val entry = lock.withLock { running.remove(eventId) } ?: return
        entry.job.cancel()
        setProgress(eventId, null)
    }

    /** ウォレットから戻ったときに呼ぶ。ウォレットで操作していた間は待ち時間に数えない。 */
    fun onAppForeground() {
        val now = deps.nowMillis()
        lock.withLock { running.values.forEach { if (it.deadline != Long.MAX_VALUE) it.deadline = now + confirmTimeoutMillis } }
    }

    /**
     * [amountSats] を [target] へ送る。[allowPlainPayment] が false なら、Zap に対応しない受け取り先へは送らない
     * （額のポップアップから知らずに Zap でない支払いをしないように）。
     */
    fun send(target: ZapTarget, amountSats: Long, comment: String = "", allowPlainPayment: Boolean = false) {
        if (target.hasZapSplit) {
            emit(ZapEvent.Message("分配の Zap には未対応です"))
            return
        }
        val job = lock.withLock {
            if (target.eventId in running) return
            scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { run(target, amountSats, comment, allowPlainPayment) }
                .also { running[target.eventId] = Running(it) }
        }
        val eventId = target.eventId
        setProgress(eventId, ZapProgress.ResolvingEndpoint)
        // 後片付けは完了の通知で行う。始まる前に取り消された job は run の本体も finally も通らないため。
        // 取り消し（cancel・支払い待ちの追い出し）はロックの外で job.cancel() するので、ここでロックを取っても重ならない。
        job.invokeOnCompletion {
            val finished = lock.withLock {
                if (running[eventId]?.job === job) running.remove(eventId)
                eventId !in running
            }
            if (finished) setProgress(eventId, null)
        }
        job.start()
    }

    private suspend fun run(target: ZapTarget, amountSats: Long, comment: String, allowPlainPayment: Boolean) {
        val eventId = target.eventId
        try {
            val endpoint = endpointFor(target)
            if (!endpoint.allowsNostr && !allowPlainPayment) throw ZapException(PLAIN_PAYMENT_MESSAGE)
            val amountMsats = amountSats * 1_000
            if (!endpoint.accepts(amountMsats)) {
                throw ZapException("この受け取り先に送れる額は ${(endpoint.minSendableMsats + 999) / 1_000}〜${endpoint.maxSendableMsats / 1_000} sats です")
            }
            setProgress(eventId, ZapProgress.RequestingInvoice)
            val trimmedComment = comment.trim().take(MAX_ZAP_COMMENT_LENGTH)
            val relays = zapRelays(deps.ownReadRelays(), target.extraRelays, deps.authorReadRelays(target.authorPubkey))
            val request = if (endpoint.allowsNostr) {
                deps.sign(trimmedComment, ZAP_REQUEST_KIND, zapRequestTags(target, amountMsats, endpoint.lnurl, relays))
            } else {
                null
            }
            val requestJson = request?.let { zapJson.encodeToString(NostrEvent.serializer(), it) }
            val callbackUrl = buildCallbackUrl(endpoint, amountMsats, requestJson, trimmedComment)
            deps.log("[Zap] callback ${redactCallbackUrl(callbackUrl)}")
            val invoice = parseInvoiceResponse(deps.httpGet(callbackUrl))
            val checked = checkInvoice(invoice, amountMsats, deps.nowMillis() / 1_000)
            if (requestJson != null && checked.descriptionHash != null &&
                checked.descriptionHash != sha256(requestJson.encodeToByteArray()).toHex()
            ) {
                // 一致しないとレシートが作られない可能性があるが、受け取り側の実装差で正しい Zap を止めないよう渡す。
                deps.log("[Zap] description hash mismatch")
            }
            // ここから先はウォレットへ渡すので取り消さない。
            emit(ZapEvent.OpenWallet(eventId, invoice))
            if (request == null) {
                emit(ZapEvent.Message("Zap に対応していない受け取り先のため、件数には出ません"))
                return
            }
            awaitPayment(target, invoice, request.id, endpoint.nostrPubkey ?: return, relays, checked.timestamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ZapException) {
            emit(ZapEvent.Message(e.message ?: "Zap を送れませんでした"))
        } catch (e: Exception) {
            deps.log("[Zap] failed: ${e::class.simpleName}")
            emit(ZapEvent.Message(e.message?.let { "Zap を送れませんでした: $it" } ?: "Zap を送れませんでした"))
        }
    }

    private suspend fun awaitPayment(
        target: ZapTarget,
        invoice: String,
        requestId: String,
        zapperPubkey: String,
        relays: List<String>,
        issuedAt: Long,
    ) {
        val eventId = target.eventId
        val now = deps.nowMillis()
        val evicted = lock.withLock {
            running[eventId]?.let { it.deadline = now + confirmTimeoutMillis; it.awaitingSince = now }
            // 支払い待ちは同時に maxAwaiting 件まで。超えたら古いものから待つのをやめる。
            // 今の Zap は必ず残す（同じミリ秒に並ぶと、並べ替えで自分が古い側に入ることがあるため）。
            val others = running.filter { (id, entry) -> id != eventId && entry.deadline != Long.MAX_VALUE }
                .entries.sortedBy { it.value.awaitingSince }
            others.dropLast((maxAwaiting - 1).coerceAtLeast(0)).map { (id, entry) ->
                running.remove(id)
                id to entry
            }
        }
        setProgress(eventId, ZapProgress.AwaitingPayment(invoice))
        evicted.forEach { (id, entry) ->
            entry.job.cancel()
            setProgress(id, null)
        }
        // 失敗を Deferred の外へ投げるとスコープごと止まることがあるため、値で受け取る。
        val watcher = scope.async {
            try {
                deps.awaitReceipt(relays, target, requestId, zapperPubkey, issuedAt - 60)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log("[Zap] receipt watch failed: ${e::class.simpleName}")
                false
            }
        }
        try {
            while (!watcher.isCompleted) {
                val deadline = lock.withLock { running[eventId]?.deadline } ?: return
                if (deps.nowMillis() >= deadline) break
                delay(POLL_INTERVAL_MS)
            }
            // ウォレットへ渡したあとは支払い済みかもしれないので、確かめられなくても「送れませんでした」とは言わない。
            if (watcher.isCompleted && watcher.await()) {
                _zappedByMe.value = _zappedByMe.value + eventId
            } else {
                emit(ZapEvent.Message(UNCONFIRMED_MESSAGE))
            }
        } finally {
            watcher.cancel()
        }
    }

    private fun emit(event: ZapEvent) {
        _events.tryEmit(event)
    }

    internal companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val UNCONFIRMED_MESSAGE = "支払いを確認できませんでした。ウォレットで支払いが済んでいれば、後で件数に反映されます"
        const val NO_ADDRESS_MESSAGE = "この人は Zap の受け取り先を設定していません"
        const val PLAIN_PAYMENT_MESSAGE = "この受け取り先は Zap に対応していません。⚡を長押しして開く画面から送れます（件数には出ません）"
        private val zapJson = Json
    }
}
