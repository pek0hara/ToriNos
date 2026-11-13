package com.nostr.torinos.badge

import com.nostr.torinos.account.SavedAccountPubkeys
import com.nostr.torinos.network.CachePersistence
import com.nostr.torinos.network.LocalTextStore
import com.nostr.torinos.network.platformCacheTextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/**
 * [BadgeEventStore]を端末に保存し、起動時に読み戻す(badge-feature-design §14)。保存先がない(Web)場合は
 * メモリでの共有だけ行う。読み込みが終わると[BadgeEventStore.markReady]で、待っていた著者が読み込まれる。
 */
internal fun badgeCachePersistence(
    store: BadgeEventStore,
    storage: LocalTextStore?,
    scope: CoroutineScope,
    accountPubkeys: Flow<Set<String>>,
) = CachePersistence(
    name = "BadgeCachePersistence",
    storage = storage,
    scope = scope,
    accountPubkeys = accountPubkeys,
    pin = store::setPinnedUsers,
    version = store.version,
    restore = store::restore,
    encode = { store.encode(BadgeEventStore.MAX_PERSISTED_USERS) },
    onRestored = store::markReady,
    describe = { "users=${store.userCount()} badges=${store.badgeCount()}" },
)

/** アプリ全体で共有するバッジのイベント本体。端末からの読み込みが終わるまでready=false。 */
internal val AppBadgeEventStore = BadgeEventStore(ready = false)

internal val AppBadgeCachePersistence = badgeCachePersistence(
    store = AppBadgeEventStore,
    storage = platformCacheTextStore("badge_cache.json"),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    accountPubkeys = SavedAccountPubkeys,
)
