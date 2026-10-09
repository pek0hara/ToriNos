package com.nostr.torinos.ui.feed

import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.util.engagementTraceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 取得先リレーごとに1つの [EngagementFetcher]。フィードとポスト詳細が共有し、同じ投稿を重ねて取りにいかない。
 * 画面と同じくメインスレッドで動かす（利用者の登録・追跡と取得の調停を直列にするため）。
 */
internal object SharedEngagementFetchers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fetchers = mutableMapOf<RelayTarget, EngagementFetcher>()
    private var nextKey = 0

    fun forTarget(target: RelayTarget): EngagementFetcher = fetchers.getOrPut(target) {
        val key = ++nextKey
        EngagementFetcher(
            scope = scope,
            subscriptions = RepositoryFeedSubscriptionGateway,
            relayTarget = target,
            store = EngagementStore.shared,
            subscriptionIdPrefix = "reac-shared$key",
            log = { message -> engagementTraceLog { "shared#$key ${message()}" } },
        )
    }
}
