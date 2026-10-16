package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.feed.FeedViewModel.UiState
import com.nostr.torinos.ui.feed.FeedViewModel.InitialFeedState

/** キャッシュとプロフィール読み出しはこのパイプラインに限定。window判定は純粋関数。 */
internal class FeedProjectionPipeline(
    private val cachedProfiles: (Collection<String>) -> Map<String, NostrProfile> = ProfileRepository::getCached,
) {
    private val feedItemMapper = FeedItemMapper()
    private val mentionedPubkeysCache = MentionedPubkeysCache()
    fun project(input: FeedProjectionInput): UiState {
        val events = input.events
        val oldestVisibleAt = input.oldestVisibleAt
        val current = input.current
        val ownPubkeySnapshot = input.ownPubkeySnapshot
        val eventSortTimesSnapshot = input.eventSortTimesSnapshot
        val heldEventIds = input.heldEventIds
        val uncommittedEventIds = input.uncommittedEventIds
        val windowAnchor = input.windowAnchor
        val window = planFeedWindow(events, oldestVisibleAt, eventSortTimesSnapshot, windowAnchor)
        val visibleEvents = window.events
        val visibleEventIds = visibleEvents.mapTo(linkedSetOf()) { it.id }
        val retainedEventIds = visibleEventIds + visibleEvents.mapNotNull { it.replyTargetId() } +
            visibleEvents.flatMap { quotedEventIds(it) }
        // 投稿ごとの付随情報は、まだ表示していない保持中の投稿の分も残す。表示前に捨てると、
        // 後から表示したときにリポスト表示・件数・引用カードが失われ、取り直しもされない。
        // 2 つの集合を結合して作り直さず、どちらかに含まれるかで判定する。
        val isKept: (String) -> Boolean = {
            it in retainedEventIds || it in heldEventIds || it in uncommittedEventIds
        }
        val quotedEvents = current.quotedEvents.filterKeys(isKept)
        val replies = current.replies.filterKeys(isKept)
        val quoteRepostEvents = current.quoteRepostEvents.filterKeys(isKept)
        // プロフィールはキャッシュから引き直せるので、表示中の投稿に関わる分だけ持つ。
        val retainedPubkeys = buildSet {
            visibleEvents.forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            quotedEvents.filterKeys { it in retainedEventIds }.values.forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            replies.filterKeys { it in visibleEventIds }.values.flatten().forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            quoteRepostEvents.filterKeys { it in visibleEventIds }.values.flatten().forEach { event ->
                add(event.pubkey)
                addAll(mentionedPubkeysCache.mentionedPubkeys(event))
            }
            current.reactionEvents
                .filterKeys { it in retainedEventIds }
                .values
                .flatten()
                .forEach { add(it.pubkey) }
            current.repostPubkeys
                .filterKeys { it in retainedEventIds }
                .values
                .flatten()
                .forEach(::add)
            current.repostedByPubkeys.forEach { (eventId, pubkey) ->
                if (eventId in visibleEventIds) add(pubkey)
            }
            ownPubkeySnapshot?.let(::add)
        }
        val profiles = current.profiles.filterKeys { it in retainedPubkeys } +
            cachedProfiles(retainedPubkeys)
        val parsedContents = feedItemMapper.map(visibleEvents)

        return assembleFeedState(
            current, window, profiles, parsedContents, quotedEvents,
            current.repostedByPubkeys.filterKeys(isKept),
        )
    }
}
