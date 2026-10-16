package com.nostr.torinos.ui.article

import com.nostr.torinos.article.articleSubscriptionId
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.FiniteFetchResult
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.fetchFiniteEvents

internal suspend fun fetchArticleEventPage(
    filter: NostrFilter,
    relayUrl: String? = null,
    open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
): FiniteFetchResult = fetchFiniteEvents(
    spec = SubscriptionSpec(
        id = articleSubscriptionId("article"),
        filters = listOf(filter),
        target = relayUrl?.let(RelayTarget::Single) ?: RelayTarget.AllEnabled,
        behavior = SubscriptionBehavior.Fetch(8_000),
    ),
    open = open,
)

/** 完全な空ページと、同秒境界から進めない部分ページを区別する。 */
internal data class ArticlePaginationDecision(val canLoadMore: Boolean, val incomplete: Boolean)

internal fun articlePaginationDecision(
    page: FiniteFetchResult?, existingIds: Set<String>, append: Boolean,
): ArticlePaginationDecision {
    if (page == null || page.rangeComplete) return ArticlePaginationDecision(false, false)
    if (!page.allAnswered) return ArticlePaginationDecision(false, true)
    val progressed = page.eventsById.keys.any { it !in existingIds }
    return if (append && !progressed) ArticlePaginationDecision(false, true)
    else ArticlePaginationDecision(true, false)
}
