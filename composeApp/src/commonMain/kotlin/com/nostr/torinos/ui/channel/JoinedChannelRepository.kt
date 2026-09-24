package com.nostr.torinos.ui.channel

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.JoinedChannelList
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.normalizeRelayUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * アカウントの参加中チャンネル(kind 10005)。★と同期する(2026-09-24 ユーザー判断、第32章)。
 *
 * 書き込み前に必ず最新のリストを取り直し、どのリレーからも応答が無ければ送信しない
 * (他クライアントが書いたリストを空で上書きしないため)。join/leave は直列化する。
 */
internal class JoinedChannelRepository(
    private val ownerPubkey: String,
    private val signer: AccountSigner?,
    private val fetchLatest: suspend () -> FetchResult = { defaultFetch(ownerPubkey) },
    private val publish: suspend (NostrEvent) -> RelayPublishResult = { event ->
        NostrRepository.publishToRelaysWithResult(event, RelayStore.writableRelayUrlsSnapshot(), awaitAcceptance = true)
    },
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    data class FetchResult(val events: List<NostrEvent>, val answered: Boolean)

    private val mutex = Mutex()
    private var latestEvent: NostrEvent? = null
    private val _joined = MutableStateFlow<Set<String>?>(null)
    /** null はまだ取得できていない(不明)。 */
    val joined: StateFlow<Set<String>?> = _joined.asStateFlow()

    suspend fun refresh(): Result<Set<String>> = mutex.withLock { refreshLocked() }

    suspend fun setJoined(channelId: String, join: Boolean, relayHint: String?): Result<Set<String>> = mutex.withLock {
        val activeSigner = signer ?: return@withLock Result.failure(IllegalStateException("秘密鍵が設定されていません"))
        val current = refreshLocked().getOrElse { return@withLock Result.failure(it) }
        val update = JoinedChannelList.next(latestEvent, channelId, join, relayHint, now())
            ?: return@withLock Result.success(current)
        try {
            val event = activeSigner.sign(update.content, JoinedChannelList.KIND, update.tags, update.createdAt)
            val result = publish(event)
            if (result.succeededRelays.isEmpty()) {
                return@withLock Result.failure(IllegalStateException("すべてのリレーへの送信に失敗しました"))
            }
            latestEvent = event
            val joined = JoinedChannelList.joinedIds(event).toSet()
            _joined.value = joined
            Result.success(joined)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun refreshLocked(): Result<Set<String>> {
        val fetched = try {
            fetchLatest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        if (!fetched.answered) return Result.failure(IllegalStateException("参加中チャンネルの一覧を取得できませんでした"))
        val candidates = fetched.events + listOfNotNull(latestEvent)
        latestEvent = JoinedChannelList.latest(candidates, ownerPubkey)
        val joined = JoinedChannelList.joinedIds(latestEvent).toSet()
        _joined.value = joined
        return Result.success(joined)
    }

    companion object {
        private suspend fun defaultFetch(ownerPubkey: String): FetchResult {
            val events = mutableListOf<NostrEvent>()
            val relays = normalizeRelayUrls(
                RelayStore.writableRelayUrlsSnapshot() + RelayStore.enabledRelayUrlsSnapshot(),
                limit = Int.MAX_VALUE,
            ).toSet()
            val answered = fetchChannelEvents(
                SubscriptionSpec(
                    id = "joined-channels-${ownerPubkey.take(8)}-${Clock.System.now().toEpochMilliseconds()}",
                    filters = listOf(NostrFilter(kinds = listOf(JoinedChannelList.KIND), authors = listOf(ownerPubkey), limit = 5)),
                    target = RelayTarget.Explicit(relays),
                    behavior = SubscriptionBehavior.Fetch(10_000),
                ),
                settleAfterFirstEoseMillis = 1_500,
            ) { event -> events += event }
            return FetchResult(events, answered)
        }
    }
}
