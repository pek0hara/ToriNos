package com.nostr.torinos.ui.profile

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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
        factory = viewModelFactory { initializer { MyProfileViewModel(ownPubkey) } },
    ),
    feedViewModel: FeedViewModel = viewModel(key = "my-feed-$ownPubkey-reposts") {
        FeedViewModel(
            authorPubkey = ownPubkey,
            includeRepostsInFeed = true,
            includeRepliesInFeed = true,
        )
    },
    reactionsViewModel: MyProfileReactionsViewModel = viewModel(
        key = "my-reactions-$ownPubkey",
        factory = viewModelFactory { initializer { MyProfileReactionsViewModel(ownPubkey) } },
    ),
) {
    val state by viewModel.state.collectAsState()
    val feedState by feedViewModel.state.collectAsState()
    val reactionsState by reactionsViewModel.state.collectAsState()
    var showRelayList by remember { mutableStateOf(false) }
    var showBannerEdit by remember { mutableStateOf(false) }
    var showAvatarEdit by remember { mutableStateOf(false) }
    var showNameEdit by remember { mutableStateOf(false) }
    var showAboutEdit by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(MyProfileTab.Posts) }
    val editProfileViewModel = viewModel<EditProfileViewModel>(
        key = "editProfile",
        factory = viewModelFactory { initializer { EditProfileViewModel() } },
    )

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        feedViewModel.injectProfile(ownPubkey, profile)
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
                    MyProfileTab.entries.forEach { tab ->
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
            MyProfileTab.Posts -> NoteTimeline(
                state = feedState,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLoadMore = feedViewModel::loadMore,
                onLike = feedViewModel::react,
                onUnlike = feedViewModel::unreact,
                onDelete = feedViewModel::deleteEvent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .profileTabSwipe(
                        currentTab = selectedTab,
                        onTabChange = { selectedTab = it },
                    ),
                onReply = onReply,
                onOpenReplies = onOpenReplies,
                onOpenLikes = onOpenLikes,
                onOpenReposts = onOpenReposts,
                onRepost = feedViewModel::repost,
                onUnrepost = feedViewModel::unrepost,
                header = profileHeader,
            )
            MyProfileTab.Reactions -> MyProfileReactionsList(
                state = reactionsState,
                onUserClick = onUserClick,
                onOpenThread = onOpenReplies,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .profileTabSwipe(
                        currentTab = selectedTab,
                        onTabChange = { selectedTab = it },
                    ),
                header = profileHeader,
            )
        }
    }
}

private enum class MyProfileTab(val label: String) {
    Posts("投稿"),
    Reactions("反応"),
}

private fun Modifier.profileTabSwipe(
    currentTab: MyProfileTab,
    onTabChange: (MyProfileTab) -> Unit,
): Modifier = pointerInput(currentTab) {
    var dragAmount = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragAmount = 0f },
        onHorizontalDrag = { change, amount ->
            dragAmount += amount
            change.consume()
        },
        onDragEnd = {
            when {
                dragAmount < -SwipeThresholdPx && currentTab == MyProfileTab.Posts ->
                    onTabChange(MyProfileTab.Reactions)
                dragAmount > SwipeThresholdPx && currentTab == MyProfileTab.Reactions ->
                    onTabChange(MyProfileTab.Posts)
            }
        },
        onDragCancel = { dragAmount = 0f },
    )
}

private const val SwipeThresholdPx = 80f
