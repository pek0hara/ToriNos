package com.nostr.torinos.ui.feed

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.ParsedNoteContent

/** UI側で固定し、計算dispatcherへ渡す入力。commit時はControllerがrevisionを照合する。 */
internal data class FeedProjectionInput(
    val events: List<NostrEvent>,
    val oldestVisibleAt: Long?,
    val current: FeedViewModel.UiState,
    val ownPubkeySnapshot: String?,
    val eventSortTimesSnapshot: Map<String, Long>,
    val heldEventIds: Set<String>,
    val uncommittedEventIds: Set<String>,
    val windowAnchor: String?,
)

/** キャッシュ・通信・可変モデルを参照しない表示投影。 */
internal fun assembleFeedState(
    current: FeedViewModel.UiState,
    window: FeedWindow,
    profiles: Map<String, NostrProfile>,
    parsedContents: Map<String, ParsedNoteContent>,
    quotedEvents: Map<String, NostrEvent>,
    repostedByPubkeys: Map<String, String>,
): FeedViewModel.UiState = current.copy(
    events = window.events,
    hasOlderStoredEvents = window.hasOlder,
    hasNewerStoredEvents = window.hasNewer,
    parsedContents = parsedContents,
    isInitialLoad = if (window.events.isNotEmpty()) false else current.isInitialLoad,
    initialFeedState = if (window.events.isNotEmpty()) FeedViewModel.InitialFeedState.ContentReady else current.initialFeedState,
    profiles = profiles,
    quotedEvents = quotedEvents,
    repostedByPubkeys = repostedByPubkeys,
)
