package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.feed.FeedViewModel

@Composable
fun MyProfileScreen(
    ownPubkey: String,
    onBack: (() -> Unit)? = null,
    onOpenFollowing: () -> Unit = {},
    onOpenFollowers: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    viewModel: MyProfileViewModel = viewModel(
        key = "my-profile-$ownPubkey",
        factory = viewModelFactory { initializer { MyProfileViewModel(ownPubkey) } },
    ),
    postsViewModel: FeedViewModel = viewModel(key = "my-feed-$ownPubkey-posts") {
        FeedViewModel(
            authorPubkey = ownPubkey,
            includeRepostsInFeed = true,
            includeRepliesInFeed = false,
        )
    },
    postsAndRepliesViewModel: FeedViewModel = viewModel(key = "my-feed-$ownPubkey-posts-replies") {
        FeedViewModel(
            authorPubkey = ownPubkey,
            includeRepostsInFeed = true,
            includeRepliesInFeed = true,
        )
    },
) {
    val state by viewModel.state.collectAsState()
    val postsState by postsViewModel.state.collectAsState()
    val postsAndRepliesState by postsAndRepliesViewModel.state.collectAsState()
    var showRelayList by remember { mutableStateOf(false) }
    var showBannerEdit by remember { mutableStateOf(false) }
    var showAvatarEdit by remember { mutableStateOf(false) }
    var showNameEdit by remember { mutableStateOf(false) }
    var showAboutEdit by remember { mutableStateOf(false) }
    var selectedTab by remember(ownPubkey) { mutableStateOf(ProfileTimelineTab.Posts) }
    val editProfileViewModel = viewModel<EditProfileViewModel>(
        key = "editProfile",
        factory = viewModelFactory { initializer { EditProfileViewModel() } },
    )

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        postsViewModel.injectProfile(ownPubkey, profile)
        postsAndRepliesViewModel.injectProfile(ownPubkey, profile)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (showAvatarEdit) {
            AvatarEditDialog(
                currentProfile = state.profile,
                pubkey = ownPubkey,
                viewModel = editProfileViewModel,
                onDismiss = { showAvatarEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showBannerEdit) {
            BannerEditDialog(
                currentProfile = state.profile,
                viewModel = editProfileViewModel,
                onDismiss = { showBannerEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showNameEdit) {
            NameEditDialog(
                currentProfile = state.profile,
                viewModel = editProfileViewModel,
                onDismiss = { showNameEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showAboutEdit) {
            AboutEditDialog(
                currentProfile = state.profile,
                viewModel = editProfileViewModel,
                onDismiss = { showAboutEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showRelayList) {
            ProfileRelayListDialog(
                relayUrls = state.relayUrls,
                onDismiss = { showRelayList = false },
            )
        }

        val profileHeader: LazyListScope.() -> Unit = {
            item {
                ProfileHeader(
                    pubkey = ownPubkey,
                    profile = state.profile,
                    linkedProfiles = state.linkedProfiles,
                    isOwnProfile = true,
                    relayUrls = state.relayUrls,
                    onUserClick = onUserClick,
                    onBack = onBack,
                    onOpenSettings = onOpenSettings,
                    onEditBanner = { showBannerEdit = true },
                    onEditAvatar = { showAvatarEdit = true },
                    onEditName = { showNameEdit = true },
                    onEditAbout = { showAboutEdit = true },
                )
                HorizontalDivider()
            }

            item {
                ProfileStatsRow(
                    followingCount = state.followingCount,
                    followersCount = state.followersCount,
                    isFollowersLoading = state.isFollowersLoading,
                    onFetchFollowers = viewModel::fetchFollowers,
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
                header = profileHeader,
            )
        }
    }
}
