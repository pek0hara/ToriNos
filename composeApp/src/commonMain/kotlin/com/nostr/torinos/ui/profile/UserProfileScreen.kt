package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.feed.FeedViewModel
import kotlinx.coroutines.delay

private const val DEFERRED_PROFILE_CONTENT_DELAY_MS = 800L

@Composable
fun UserProfileScreen(
    pubkey: String,
    onBack: (() -> Unit)? = null,
    isOwnProfile: Boolean = false,
    ownPubkey: String? = null,
    onUserClick: (String) -> Unit = {},
    onOpenFollowing: (() -> Unit)? = null,
    onOpenFollowers: (() -> Unit)? = null,
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
) {
    val relays by RelayStore.relays.collectAsState(
        initial = RelayStore.defaults.filter { it.enabled }.map { it.url },
    )
    val contentRelayUrl = relays.firstOrNull()
    val ownerKey = ownPubkey ?: "anonymous"
    val viewModel: UserProfileViewModel = viewModel(key = "profile-$pubkey-${contentRelayUrl ?: "all"}-$ownerKey") {
        UserProfileViewModel(pubkey, deferredRelayUrl = contentRelayUrl)
    }
    val postsViewModel: FeedViewModel = viewModel(key = "user-feed-$pubkey-${contentRelayUrl ?: "all"}-$ownerKey-posts") {
        FeedViewModel(
            authorPubkey = pubkey,
            relayUrl = contentRelayUrl,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = false,
            filterMutedUsers = false,
        )
    }
    val postsAndRepliesViewModel: FeedViewModel = viewModel(key = "user-feed-$pubkey-${contentRelayUrl ?: "all"}-$ownerKey-posts-replies") {
        FeedViewModel(
            authorPubkey = pubkey,
            relayUrl = contentRelayUrl,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = true,
            filterMutedUsers = false,
        )
    }
    val state by viewModel.state.collectAsState()
    val postsState by postsViewModel.state.collectAsState()
    val postsAndRepliesState by postsAndRepliesViewModel.state.collectAsState()
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val isMuted = mutedPubkeys.contains(pubkey)
    val snackbarHostState = remember { SnackbarHostState() }
    var deferredContentStarted by remember(pubkey) { mutableStateOf(false) }
    var showRelayList by remember(pubkey) { mutableStateOf(false) }
    var selectedTab by remember(pubkey) { mutableStateOf(ProfileTimelineTab.Posts) }

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        postsViewModel.injectProfile(pubkey, profile)
        postsAndRepliesViewModel.injectProfile(pubkey, profile)
        if (!deferredContentStarted) {
            deferredContentStarted = true
            viewModel.loadFollowingCount()
            postsViewModel.startSubscriptions()
            postsAndRepliesViewModel.startSubscriptions()
        }
    }

    LaunchedEffect(pubkey) {
        delay(DEFERRED_PROFILE_CONTENT_DELAY_MS)
        if (!deferredContentStarted) {
            deferredContentStarted = true
            viewModel.loadFollowingCount()
            postsViewModel.startSubscriptions()
            postsAndRepliesViewModel.startSubscriptions()
        }
    }

    LaunchedEffect(state.followError) {
        if (state.followError != null) {
            snackbarHostState.showSnackbar(state.followError!!)
            viewModel.clearFollowError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (showRelayList) {
            ProfileRelayListDialog(
                relayUrls = state.relayUrls,
                onDismiss = { showRelayList = false },
            )
        }

        val profileHeader: LazyListScope.() -> Unit = {
            item {
                ProfileHeader(
                    pubkey = pubkey,
                    profile = state.profile,
                    linkedProfiles = state.linkedProfiles,
                    isOwnProfile = isOwnProfile,
                    isFollowing = state.isFollowing,
                    isFollowLoading = state.isFollowLoading,
                    canFollow = state.canFollow,
                    isMuted = isMuted,
                    relayUrls = state.relayUrls,
                    generalStatus = state.generalStatus,
                    onFollow = viewModel::follow,
                    onUnfollow = viewModel::unfollow,
                    onMuteToggle = {
                        if (isMuted) MuteStore.unmute(pubkey) else MuteStore.mute(pubkey)
                    },
                    onUserClick = onUserClick,
                    onBack = onBack,
                )
                HorizontalDivider()
            }

            item {
                ProfileStatsRow(
                    followingCount = state.followingCount,
                    followersCount = state.followersCount,
                    followersCountSuffix = if (state.isFollowersCountLimited) "+" else "",
                    isFollowersLoading = state.isFollowersLoading,
                    onFetchFollowers = viewModel::loadFollowersCount,
                    followersFetched = state.followersLoaded,
                    relayCount = state.relayUrls.size,
                    onOpenFollowing = onOpenFollowing,
                    onOpenFollowers = onOpenFollowers,
                    onOpenRelays = { showRelayList = true },
                )
                HorizontalDivider()
            }

            item {
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    ProfileTimelineTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        when (selectedTab) {
            ProfileTimelineTab.Posts -> NoteTimeline(
                state = postsState,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLoadMore = postsViewModel::loadMore,
                onLike = postsViewModel::react,
                onUnlike = postsViewModel::unreact,
                onDelete = postsViewModel::deleteEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .profileTimelineTabSwipe(
                        currentTab = selectedTab,
                        onTabChange = { selectedTab = it },
                    ),
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onRepost = postsViewModel::repost,
                onUnrepost = postsViewModel::unrepost,
                onReport = postsViewModel::reportEvent,
                emptyText = "このユーザーのポストはありません",
                header = profileHeader,
            )
            ProfileTimelineTab.PostsAndReplies -> NoteTimeline(
                state = postsAndRepliesState,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLoadMore = postsAndRepliesViewModel::loadMore,
                onLike = postsAndRepliesViewModel::react,
                onUnlike = postsAndRepliesViewModel::unreact,
                onDelete = postsAndRepliesViewModel::deleteEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .profileTimelineTabSwipe(
                        currentTab = selectedTab,
                        onTabChange = { selectedTab = it },
                    ),
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onRepost = postsAndRepliesViewModel::repost,
                onUnrepost = postsAndRepliesViewModel::unrepost,
                onReport = postsAndRepliesViewModel::reportEvent,
                emptyText = "このユーザーのポストと返信はありません",
                header = profileHeader,
            )
        }
    }
}
