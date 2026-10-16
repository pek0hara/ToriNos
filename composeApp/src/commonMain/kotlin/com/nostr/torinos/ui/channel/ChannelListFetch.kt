package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.FiniteFetchResult
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.fetchFiniteEvents

/** 表示できる部分結果と、対象リレー全体のページ完了を区別する。 */
internal suspend fun fetchChannelPage(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    settleAfterEoseMillis: Long? = null,
    onRelayEvent: (suspend (String, NostrEvent) -> Unit)? = null,
    onEvent: suspend (NostrEvent) -> Unit,
): FiniteFetchResult = fetchFiniteEvents(
    spec = spec,
    open = open,
    settleAfterEoseMillis = settleAfterEoseMillis,
    onEvent = { relay, event ->
        onRelayEvent?.invoke(relay, event)
        onEvent(event)
    },
)

/** 一覧の既存呼び出し用。部分応答は全取得成功に変換しない。 */
internal suspend fun fetchChannelEvents(
    spec: SubscriptionSpec,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
    settleAfterEoseMillis: Long? = null,
    onRelayEvent: (suspend (String, NostrEvent) -> Unit)? = null,
    onEvent: suspend (NostrEvent) -> Unit,
): Boolean = fetchChannelPage(spec, open, settleAfterEoseMillis, onRelayEvent, onEvent).allAnswered
