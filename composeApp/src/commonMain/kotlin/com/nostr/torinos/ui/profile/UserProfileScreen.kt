package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.nostr.torinos.account.accountScopedViewModelKey
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.components.AppFloatingActionButton
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
    onOpenJournal: (() -> Unit)? = null,
) {
    val relays by RelayStore.relays.collectAsState(
        initial = RelayStore.defaults.filter { it.enabled }.map { it.url },
    )
    val contentRelayUrl = relays.firstOrNull()
    val ownerKey = ownPubkey ?: "anonymous"
    val viewModel: UserProfileViewModel = viewModel(
        key = accountScopedViewModelKey("profile-$pubkey-${contentRelayUrl ?: "all"}-$ownerKey"),
    ) {
        UserProfileViewModel(pubkey, deferredRelayUrl = contentRelayUrl)
    }
    var showRelayList by remember(pubkey) { mutableStateOf(false) }
    var selectedTab by remember(pubkey) { mutableStateOf(ProfileTimelineTab.Posts) }
    val feedViewModel: FeedViewModel = viewModel(
        key = accountScopedViewModelKey(
            "user-feed-$pubkey-${contentRelayUrl ?: "all"}-$ownerKey-${selectedTab.name}",
        ),
    ) {
        FeedViewModel(
            authorPubkey = pubkey,
            relayUrl = contentRelayUrl,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = selectedTab == ProfileTimelineTab.PostsAndReplies,
            filterMutedUsers = false,
        )
    }
    val state by viewModel.state.collectAsState()
    val feedState by feedViewModel.state.collectAsState()
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val isMuted = mutedPubkeys.contains(pubkey)
    val snackbarHostState = remember { SnackbarHostState() }
    var deferredContentStarted by remember(pubkey) { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.refreshProfile()
    }

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        if (!deferredContentStarted) {
            deferredContentStarted = true
            viewModel.loadFollowingCount()
        }
    }

    LaunchedEffect(pubkey) {
        delay(DEFERRED_PROFILE_CONTENT_DELAY_MS)
        if (!deferredContentStarted) {
            deferredContentStarted = true
            viewModel.loadFollowingCount()
        }
    }

    LaunchedEffect(feedViewModel, state.profile, deferredContentStarted) {
        state.profile?.let { feedViewModel.injectProfile(pubkey, it) }
        if (deferredContentStarted) feedViewModel.startSubscriptions()
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
        floatingActionButton = {
            if (!isOwnProfile && onOpenJournal != null) {
                AppFloatingActionButton(
                    onClick = onOpenJournal,
                    icon = Icons.Default.Today,
                    contentDescription = "ジャーナル",
                )
            }
        },
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
                state = feedState,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLoadMore = feedViewModel::loadMore,
                onLike = feedViewModel::react,
                onUnlike = feedViewModel::unreact,
                onEmojiReact = feedViewModel::reactWithEmoji,
                onEmojiUnreact = feedViewModel::unreactWithEmoji,
                onDelete = feedViewModel::deleteEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onRepost = feedViewModel::repost,
                onUnrepost = feedViewModel::unrepost,
                onReport = feedViewModel::reportEvent,
                emptyText = "このユーザーのポストはありません",
                header = profileHeader,
            )
            ProfileTimelineTab.PostsAndReplies -> NoteTimeline(
                state = feedState,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLoadMore = feedViewModel::loadMore,
                onLike = feedViewModel::react,
                onUnlike = feedViewModel::unreact,
                onEmojiReact = feedViewModel::reactWithEmoji,
                onEmojiUnreact = feedViewModel::unreactWithEmoji,
                onDelete = feedViewModel::deleteEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onRepost = feedViewModel::repost,
                onUnrepost = feedViewModel::unrepost,
                onReport = feedViewModel::reportEvent,
                emptyText = "このユーザーのポストと返信はありません",
                header = profileHeader,
            )
        }
    }
}
