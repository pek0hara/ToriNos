package com.nostr.torinos.zap

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayListEventCache
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.util.appLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private const val PROFILE_MAX_AGE_MS = 10 * 60 * 1_000L
/**
 * レシート待ちの購読 ID。Zap リクエストごとに違う ID にする（同じ ID はリレーで前の購読を上書きする）。
 * 共有のカウンターだと、別々のスレッドで同時に始まった Zap で重なることがある。
 */
internal fun zapReceiptSubscriptionId(requestId: String): String = "zap-receipt-${requestId.take(16)}"

/** NIP-65 の kind 10002 のうち、読み込み（マーカーなし・`read`）のリレー。 */
internal fun readRelaysOf(relayList: NostrEvent?): List<String> =
    relayList?.tags.orEmpty()
        .filter { it.firstOrNull() == "r" && it.getOrNull(1) != null && it.getOrNull(2).let { marker -> marker == null || marker == "read" } }
        .map { it[1] }

/**
 * [requestId] の Zap リクエストのレシートが届くまで待つ。届いたレシートは付随情報ストアにも入る。
 * 誰でも kind 9735 を出せるので、受け取り先のサーバーの鍵 [zapperPubkey] で署名されたものだけを支払いの確認とする。
 */
internal suspend fun awaitZapReceipt(relays: List<String>, target: ZapTarget, requestId: String, zapperPubkey: String, since: Long) {
    if (relays.isEmpty()) return awaitCancellation()
    val session = NostrRepository.openSubscription(
        SubscriptionSpec(
            id = zapReceiptSubscriptionId(requestId),
            filters = listOf(
                NostrFilter(
                    kinds = listOf(ZAP_RECEIPT_KIND),
                    authors = listOf(zapperPubkey),
                    eTags = listOf(target.eventId),
                    pTags = listOf(target.authorPubkey),
                    since = since,
                ),
            ),
            target = RelayTarget.Explicit(relays.toSet()),
            behavior = SubscriptionBehavior.Live,
            replayCachedEvents = false,
        ),
    )
    try {
        session.signals.first { signal ->
            signal is SubscriptionSignal.Event && signal.event.kind == ZAP_RECEIPT_KIND &&
                signal.event.pubkey == zapperPubkey &&
                withContext(Dispatchers.Default) { parseZapReceipt(signal.event)?.requestId == requestId }
        }
    } finally {
        session.close()
    }
}

private suspend fun awaitCancellation(): Nothing = kotlinx.coroutines.awaitCancellation()

internal fun createZapSender(session: AccountSession, scope: CoroutineScope): ZapSender = ZapSender(
    scope = scope,
    deps = ZapSenderDependencies(
        sign = { content, kind, tags -> session.signer.sign(content = content, kind = kind, tags = tags) },
        profileOf = { pubkey ->
            ProfileRepository.awaitProfiles(setOf(pubkey), ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS), timeoutMillis = 5_000L)[pubkey]
                ?: ProfileRepository.getCached(pubkey)
        },
        endpointOf = { url -> LnurlHttp.endpoints.get(url) },
        httpGet = LnurlHttp::get,
        ownReadRelays = session.relayStore::enabledRelayUrlsSnapshot,
        authorReadRelays = { pubkey -> readRelaysOf(RelayListEventCache.get(pubkey)) },
        awaitReceipt = ::awaitZapReceipt,
        nowMillis = { Clock.System.now().toEpochMilliseconds() },
        log = ::appLog,
    ),
)
