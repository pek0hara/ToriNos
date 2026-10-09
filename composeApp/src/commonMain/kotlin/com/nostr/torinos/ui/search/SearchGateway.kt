package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/** 検索が使う通信とプロフィールの境界。テストでは偽実装に差し替える。 */
internal interface SearchGateway {
    suspend fun openSubscription(spec: SubscriptionSpec): SubscriptionSession

    /** 変更されたpubkey。空集合は「全件が変わり得る」を表す（購読開始直後を含む）。 */
    fun profileChanges(): Flow<Set<String>>

    fun cachedProfiles(pubkeys: Collection<String>): Map<String, NostrProfile>

    /**
     * 共有の取得調整役へプロフィール取得を依頼する。依頼済みの通信は他画面と共有するため、
     * 検索画面の停止では取り消さない。
     */
    suspend fun requestProfiles(pubkeys: Set<String>, maxAgeMillis: Long)

    suspend fun searchUsers(query: String, relayUrl: String, limit: Int): List<Pair<String, NostrProfile>>
}

internal object RepositorySearchGateway : SearchGateway {
    override suspend fun openSubscription(spec: SubscriptionSpec): SubscriptionSession =
        NostrRepository.openSubscription(spec)

    override fun profileChanges(): Flow<Set<String>> = ProfileRepository.observeChanges()

    override fun cachedProfiles(pubkeys: Collection<String>): Map<String, NostrProfile> =
        ProfileRepository.getCached(pubkeys)

    override suspend fun requestProfiles(pubkeys: Set<String>, maxAgeMillis: Long) {
        ProfileRepository.ensureProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(maxAgeMillis))
    }

    override suspend fun searchUsers(query: String, relayUrl: String, limit: Int): List<Pair<String, NostrProfile>> =
        ProfileRepository.searchProfiles(query, relayUrl, limit)
}

/** 検索の所有者（ViewModel）ごとに異なる購読IDの接頭辞。通常画面とサイドパネルが同時に検索しても衝突しない。 */
internal fun newSearchOwnerId(): String = "srch-" + Random.nextLong(0, 36L * 36 * 36 * 36 * 36 * 36).toString(36)
