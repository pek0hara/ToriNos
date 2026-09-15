package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.feed.FeedViewModel
import com.nostr.torinos.ui.status.StatusComposerSheet
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent

@Composable
fun MyProfileScreen(
    ownPubkey: String,
    onBack: (() -> Unit)? = null,
    onOpenFollowing: () -> Unit = {},
    onOpenFollowers: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onReply: ((event: NostrEvent, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    viewModel: MyProfileViewModel = accountSessionViewModel(
        key = "my-profile-$ownPubkey",
    ) { accountSession -> MyProfileViewModel(ownPubkey, accountSession) },
) {
    val postsViewModel: FeedViewModel = accountSessionViewModel(
        key = "my-feed-$ownPubkey-posts",
    ) { accountSession ->
        FeedViewModel(
            accountSession = accountSession,
            authorPubkey = ownPubkey,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = false,
        )
    }
    val postsAndRepliesViewModel: FeedViewModel = accountSessionViewModel(
        key = "my-feed-$ownPubkey-posts-replies",
    ) { accountSession ->
        FeedViewModel(
            accountSession = accountSession,
            authorPubkey = ownPubkey,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = true,
            feedEventKinds = setOf(1, COMMENT_EVENT_KIND),
        )
    }

    val state by viewModel.state.collectAsState()
    val postsState by postsViewModel.state.collectAsState()
    val postsAndRepliesState by postsAndRepliesViewModel.state.collectAsState()
    var showRelayList by remember(ownPubkey) { mutableStateOf(false) }
    var showBannerEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showAvatarEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showNameEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showAboutEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showStatusEdit by remember(ownPubkey) { mutableStateOf(false) }
    var bannerHeightPx by remember(ownPubkey) { mutableIntStateOf(0) }
    var selectedTabName by rememberSaveable(ownPubkey) {
        mutableStateOf(ProfileTimelineTab.Posts.name)
    }
    val selectedTab = ProfileTimelineTab.entries.firstOrNull { it.name == selectedTabName }
        ?: ProfileTimelineTab.Posts
    val editProfileViewModel = accountSessionViewModel<EditProfileViewModel>(
        key = "edit-profile-$ownPubkey",
    ) { accountSession -> EditProfileViewModel(accountSession = accountSession) }
    val profileListState = rememberSaveable(ownPubkey, saver = LazyListState.Saver) {
        LazyListState()
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshProfile()
    }

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        postsViewModel.injectProfile(ownPubkey, profile)
        postsAndRepliesViewModel.injectProfile(ownPubkey, profile)
    }

    LaunchedEffect(selectedTab, postsViewModel, postsAndRepliesViewModel) {
        when (selectedTab) {
            ProfileTimelineTab.Posts -> postsViewModel.startSubscriptions()
            ProfileTimelineTab.PostsAndReplies -> postsAndRepliesViewModel.startSubscriptions()
        }
    }

    LaunchedEffect(state.generalStatusPublishCompletedCount) {
        if (state.generalStatusPublishCompletedCount > 0) {
            showStatusEdit = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            AppFloatingActionButton(
                onClick = { showStatusEdit = true },
                icon = Icons.Default.Sms,
                contentDescription = "ステータスを編集",
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
            )
        },
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
        if (showStatusEdit) {
            StatusComposerSheet(
                title = "ステータス",
                actionLabel = if (state.generalStatus == null) "追加" else "保存",
                initialStatusTag = PROFILE_GENERAL_STATUS_TAG,
                initialContent = state.generalStatus?.content.orEmpty(),
                initialExpiration = state.generalStatus?.expiration,
                initialReferenceUrl = state.generalStatus?.referenceUrl.orEmpty(),
                isPublishing = state.isGeneralStatusPublishing,
                errorMessage = state.generalStatusError,
                onDismiss = {
                    showStatusEdit = false
                    viewModel.clearGeneralStatusError()
                },
                onSubmit = viewModel::publishStatus,
                onDelete = state.generalStatus?.let {
                    { viewModel.publishStatus(PROFILE_GENERAL_STATUS_TAG, "", null, null) }
                },
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
                    generalStatus = state.generalStatus,
                    onEditGeneralStatus = { showStatusEdit = true },
                    onUserClick = onUserClick,
                    onBack = onBack,
                    onEditBanner = { showBannerEdit = true },
                    onEditAvatar = { showAvatarEdit = true },
                    onEditName = { showNameEdit = true },
                    onEditAbout = { showAboutEdit = true },
                    showBackButton = false,
                    onBannerHeightChanged = { bannerHeightPx = it },
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
                            onClick = { selectedTabName = tab.name },
                            text = { Text(tab.label) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        ProfileTimelineWithCollapsingHeader(
            listState = profileListState,
            pubkey = ownPubkey,
            profile = state.profile,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            bannerHeightPx = bannerHeightPx,
        ) {
            when (selectedTab) {
                ProfileTimelineTab.Posts -> NoteTimeline(
                    state = postsState,
                    ownPubkey = ownPubkey,
                    onUserClick = onUserClick,
                    onLoadMore = postsViewModel::loadMore,
                    onLike = postsViewModel::react,
                    onUnlike = postsViewModel::unreact,
                    onEmojiReact = postsViewModel::reactWithEmoji,
                    onEmojiUnreact = postsViewModel::unreactWithEmoji,
                    onDelete = postsViewModel::deleteEvent,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onReply = onReply,
                    onOpenReplies = onOpenReplies,
                    onOpenLikes = onOpenLikes,
                    onOpenReposts = onOpenReposts,
                    onRepost = postsViewModel::repost,
                    onUnrepost = postsViewModel::unrepost,
                    onReport = postsViewModel::reportEvent,
                    listState = profileListState,
                    header = profileHeader,
                )
                ProfileTimelineTab.PostsAndReplies -> NoteTimeline(
                    state = postsAndRepliesState,
                    ownPubkey = ownPubkey,
                    onUserClick = onUserClick,
                    onLoadMore = postsAndRepliesViewModel::loadMore,
                    onLike = postsAndRepliesViewModel::react,
                    onUnlike = postsAndRepliesViewModel::unreact,
                    onEmojiReact = postsAndRepliesViewModel::reactWithEmoji,
                    onEmojiUnreact = postsAndRepliesViewModel::unreactWithEmoji,
                    onDelete = postsAndRepliesViewModel::deleteEvent,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onReply = onReply,
                    onOpenReplies = onOpenReplies,
                    onOpenLikes = onOpenLikes,
                    onOpenReposts = onOpenReposts,
                    onRepost = postsAndRepliesViewModel::repost,
                    onUnrepost = postsAndRepliesViewModel::unrepost,
                    onReport = postsAndRepliesViewModel::reportEvent,
                    listState = profileListState,
                    header = profileHeader,
                )
            }
        }
    }
}
