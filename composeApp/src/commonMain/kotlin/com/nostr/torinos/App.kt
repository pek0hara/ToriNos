package com.nostr.torinos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.noteContextForChannel
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.ChannelCacheStore
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.StatusStore
import com.nostr.torinos.ui.channel.ChannelListScreen
import com.nostr.torinos.ui.channel.ChannelScreen
import com.nostr.torinos.ui.feed.FeedTab
import com.nostr.torinos.ui.feed.FeedScreen
import com.nostr.torinos.ui.notification.NotificationsDrawer
import com.nostr.torinos.ui.notification.NotificationsViewModel
import com.nostr.torinos.ui.settings.MuteListScreen
import com.nostr.torinos.ui.settings.NgWordScreen
import com.nostr.torinos.ui.post.DiaryScreen
import com.nostr.torinos.ui.post.PostMemoData
import com.nostr.torinos.ui.post.PostSheet
import com.nostr.torinos.ui.profile.FollowListMode
import com.nostr.torinos.ui.profile.FollowListScreen
import com.nostr.torinos.ui.profile.MyProfileScreen
import com.nostr.torinos.ui.profile.UserProfileScreen
import com.nostr.torinos.ui.relay.RelaySettingsScreen
import com.nostr.torinos.ui.search.SearchScreen
import com.nostr.torinos.ui.settings.QuickSettingsDialogs
import com.nostr.torinos.ui.settings.CustomEmojiSettingsScreen
import com.nostr.torinos.ui.settings.SettingsScreen
import com.nostr.torinos.ui.service.ServiceTab
import com.nostr.torinos.ui.setup.KeySetupScreen
import com.nostr.torinos.ui.status.StatusScreen
import com.nostr.torinos.ui.theme.NostrTheme
import com.nostr.torinos.ui.thread.ThreadScreen
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException
import kotlinx.serialization.Serializable

// 型安全なルート定義（パラメータ付き画面）
@Serializable data class ChannelRoute(val channelId: String)
@Serializable data class ProfileRoute(val pubkey: String)
@Serializable data class FollowingRoute(val pubkey: String)
@Serializable data class FollowersRoute(val pubkey: String)
@Serializable data class SearchRoute(val query: String = "")
@Serializable data class CustomEmojiRoute(val query: String = "")
@Serializable data class ThreadRoute(
    val eventId: String,
    val initialTab: String = "replies",
    val source: String = "",
    val channelId: String = "",
)

private const val ThreadSourceChannel = "channel"

private enum class PendingKeyAction {
    NewPost,
    Reply,
    Profile,
    Status,
    Diary,
}

@Composable
fun App() {
    NostrTheme {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        var showPostSheet by remember { mutableStateOf(false) }
        var showStatusComposer by remember { mutableStateOf(false) }
        var replyToId by remember { mutableStateOf<String?>(null) }
        var replyToPubkey by remember { mutableStateOf<String?>(null) }
        var replyToPreview by remember { mutableStateOf<String?>(null) }
        var replyNoteContext by remember { mutableStateOf<NoteContext>(NoteContext.Timeline) }
        var selectedMemo by remember { mutableStateOf<PostMemoData?>(null) }
        var localDraft by remember { mutableStateOf<PostMemoData?>(null) }
        var memoRefreshTodayRequest by remember { mutableStateOf(0) }
        var showKeySetup by remember { mutableStateOf(false) }
        var pendingKeyAction by remember { mutableStateOf<PendingKeyAction?>(null) }
        val scope = rememberCoroutineScope()
        val uiExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught UI coroutine exception")
        }

        var ownPubkey by remember { mutableStateOf<String?>(null) }
        var ownProfile by remember { mutableStateOf<NostrProfile?>(null) }
        var isAccountLoaded by remember { mutableStateOf(false) }
        val notificationsViewModel = ownPubkey?.let { pubkey ->
            viewModel<NotificationsViewModel>(
                key = "notifications-$pubkey",
                factory = viewModelFactory { initializer { NotificationsViewModel(pubkey) } },
            )
        }
        val notificationsState = notificationsViewModel?.state?.collectAsState()?.value
        var feedScrollToTopRequest by remember { mutableStateOf(0) }
        var currentFeedTab by remember { mutableStateOf(FeedTab.Following) }
        var feedScrollToTopTargetTab by remember { mutableStateOf(FeedTab.Following) }
        var feedTabChangeRequest by remember { mutableStateOf(0) }
        var notificationsScrollToTopRequest by remember { mutableStateOf(0) }
        var showQuickSettings by remember { mutableStateOf(false) }
        val notificationsDrawerState = rememberDrawerState(DrawerValue.Closed)
        val followingFeedListState = remember { LazyListState() }
        val globalFeedListState = remember { LazyListState() }
        var currentServiceTab by remember { mutableStateOf(ServiceTab.Channels) }

        fun navigateServiceTab(tab: ServiceTab) {
            currentServiceTab = tab
            nav.navigate("services") {
                popUpTo("feed") { saveState = true; inclusive = false }
                launchSingleTop = true
                restoreState = true
            }
        }

        // 未登録カスタム絵文字タップ → 絵文字設定画面（検索クエリ付き）へ遷移
        LaunchedEffect(Unit) {
            CustomEmojiStore.openSearchEvent.collect { shortcode ->
                nav.navigate(CustomEmojiRoute(query = shortcode))
            }
        }

        // 起動時に保存済み秘密鍵から公開鍵を読み込む & DBを整理
        LaunchedEffect(Unit) {
            appLog("[App] startup: loading saved public key")
            try {
                ownPubkey = loadPublicKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load public key on startup")
            } finally {
                isAccountLoaded = true
            }
            try {
                ChannelCacheStore.prune()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to prune channel cache")
            }
        }

        // ownPubkey が確定したらプロフィールを購読（ログイン後の再実行にも対応）
        LaunchedEffect(ownPubkey) {
            val pk = ownPubkey ?: return@LaunchedEffect
            ownProfile = null
            try {
                coroutineScope {
                    val profileEvent = async(start = CoroutineStart.UNDISPATCHED) {
                        NostrRepository.events("app-self-profile").first { it.kind == 0 }
                    }
                    NostrRepository.subscribe(
                        "app-self-profile",
                        NostrFilter(kinds = listOf(0), authors = listOf(pk), limit = 1),
                    )
                    ownProfile = profileEvent.await().toProfile()
                    NostrRepository.close("app-self-profile")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load own profile")
            }
        }

        fun runWithPrivateKey(
            missingAction: PendingKeyAction,
            onAvailable: (pubkeyHex: String) -> Unit,
        ) {
            scope.launch(uiExceptionHandler) {
                val pubkey = ownPubkey ?: try {
                    loadPublicKey()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logException("App", e, "Failed to load public key before protected action")
                    null
                }

                if (pubkey != null) {
                    ownPubkey = pubkey
                    onAvailable(pubkey)
                } else {
                    pendingKeyAction = missingAction
                    showKeySetup = true
                }
            }
        }

        fun clearLocalAccountState() {
            ownPubkey = null
            ownProfile = null
            isAccountLoaded = true
            showPostSheet = false
            showStatusComposer = false
            showKeySetup = false
            pendingKeyAction = null
            replyToId = null
            replyToPubkey = null
            replyToPreview = null
            replyNoteContext = NoteContext.Timeline
            selectedMemo = null
            localDraft = null
            FollowRepository.reload()
            currentFeedTab = FeedTab.Global
            feedScrollToTopTargetTab = FeedTab.Global
            feedTabChangeRequest++
            nav.navigate("feed") {
                popUpTo("feed") { inclusive = true }
                launchSingleTop = true
            }
        }

        fun handleAccountChanged(pubkey: String?) {
            if (pubkey == null) {
                clearLocalAccountState()
                return
            }
            ownPubkey = pubkey
            ownProfile = null
            isAccountLoaded = true
            showPostSheet = false
            showStatusComposer = false
            showKeySetup = false
            pendingKeyAction = null
            replyToId = null
            replyToPubkey = null
            replyToPreview = null
            replyNoteContext = NoteContext.Timeline
            selectedMemo = null
            localDraft = null
            FollowRepository.reload()
            currentFeedTab = FeedTab.Global
            feedScrollToTopTargetTab = FeedTab.Global
            feedTabChangeRequest++
            nav.navigate("feed") {
                popUpTo("feed") { inclusive = true }
                launchSingleTop = true
            }
        }

        val bottomBarRoutes = setOf("feed", "services", "channels", "status", "diary")
        val routeName = currentRoute?.substringBefore("/")
        val isChannelRoute = routeName?.endsWith("ChannelRoute") == true
        val threadRoute = if (routeName?.endsWith("ThreadRoute") == true) {
            runCatching { backStackEntry?.toRoute<ThreadRoute>() }.getOrNull()
        } else {
            null
        }
        val isChannelThreadRoute = threadRoute?.source == ThreadSourceChannel
        val isProfileRoute = currentRoute == "myprofile" ||
            routeName?.endsWith("ProfileRoute") == true
        val hasBottomBar = currentRoute in bottomBarRoutes || isChannelThreadRoute || isProfileRoute
        // DataStoreから取得
        val statusVisible = StatusStore.statusVisible.collectAsState().value

        ModalNavigationDrawer(
            drawerState = notificationsDrawerState,
            drawerContent = {
                NotificationsDrawer(
                    ownPubkey = ownPubkey,
                    scrollToTopRequest = notificationsScrollToTopRequest,
                    onUserClick = { pubkey ->
                        scope.launch { notificationsDrawerState.close() }
                        nav.navigate(ProfileRoute(pubkey))
                    },
                    onOpenThread = { eventId ->
                        scope.launch { notificationsDrawerState.close() }
                        nav.navigate(ThreadRoute(eventId))
                    },
                )
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                floatingActionButton = {
                    if (isWriteSupported) {
                        when (currentRoute) {
                            "feed" -> PostFloatingActionButton(
                                onPostClick = {
                                    runWithPrivateKey(PendingKeyAction.NewPost) {
                                        selectedMemo = null
                                        replyToId = null
                                        replyToPubkey = null
                                        replyToPreview = null
                                        replyNoteContext = NoteContext.Timeline
                                        showPostSheet = true
                                    }
                                },
                            )
                            "services" -> if (currentServiceTab == ServiceTab.Status) FloatingActionButton(onClick = {
                                runWithPrivateKey(PendingKeyAction.Status) {
                                    showStatusComposer = true
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "ステータス追加")
                            } else Unit
                            "status" -> FloatingActionButton(onClick = {
                                runWithPrivateKey(PendingKeyAction.Status) {
                                    showStatusComposer = true
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "ステータス追加")
                            }
                            else -> Unit
                        }
                    }
                },
                bottomBar = {
                    if (hasBottomBar) {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                label = { Text("フィード") },
                                selected = currentRoute == "feed",
                                onClick = {
                                    if (currentRoute == "feed") {
                                        feedScrollToTopTargetTab = currentFeedTab
                                        feedScrollToTopRequest++
                                    } else {
                                        nav.navigate("feed") {
                                            popUpTo("feed") { saveState = true; inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Today, contentDescription = null) },
                                label = { Text("ダイアリー") },
                                selected = currentRoute == "diary",
                                onClick = {
                                    runWithPrivateKey(PendingKeyAction.Diary) {
                                        nav.navigate("diary") {
                                            popUpTo("feed") { saveState = true; inclusive = false }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                label = { Text("サービス") },
                                selected = currentRoute == "services" ||
                                    currentRoute == "channels" ||
                                    isChannelRoute ||
                                    isChannelThreadRoute ||
                                    currentRoute == "status",
                                onClick = { navigateServiceTab(currentServiceTab) },
                            )
                        }
                    }
                },
            ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = "feed",
                    modifier = Modifier.weight(1f),
                ) {
                    composable("feed") {
                        FeedScreen(
                            onOpenSettings = { showQuickSettings = true },
                            onOpenNotifications = {
                                notificationsViewModel?.markAllRead()
                                notificationsScrollToTopRequest++
                                scope.launch { notificationsDrawerState.open() }
                            },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onOpenProfile = {
                                runWithPrivateKey(PendingKeyAction.Profile) {
                                    nav.navigate("myprofile") { launchSingleTop = true }
                                }
                            },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                replyNoteContext = NoteContext.Timeline
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                            onOpenSearch = { query -> nav.navigate(SearchRoute(query)) },
                            ownPubkey = ownPubkey,
                            ownProfile = ownProfile,
                            isAccountLoaded = isAccountLoaded,
                            scrollToTopRequest = feedScrollToTopRequest,
                            scrollToTopTargetTab = feedScrollToTopTargetTab,
                            onCurrentFeedTabChanged = { currentFeedTab = it },
                            requestedFeedTab = FeedTab.Global,
                            feedTabChangeRequest = feedTabChangeRequest,
                            followingListState = followingFeedListState,
                            globalListState = globalFeedListState,
                            hasNotifications = notificationsState?.hasUnread == true,
                        )
                    }
                    composable("services") {
                        when (currentServiceTab) {
                            ServiceTab.Channels -> {
                                ChannelListScreen(
                                    onChannelClick = { id -> nav.navigate(ChannelRoute(id)) },
                                    ownPubkey = ownPubkey,
                                    ownProfile = ownProfile,
                                    onOpenProfile = {
                                        runWithPrivateKey(PendingKeyAction.Profile) {
                                            nav.navigate("myprofile") { launchSingleTop = true }
                                        }
                                    },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                )
                            }
                            ServiceTab.Status -> {
                                StatusScreen(
                                    ownPubkey = ownPubkey,
                                    ownProfile = ownProfile,
                                    showComposer = showStatusComposer,
                                    onComposerShown = { showStatusComposer = false },
                                    onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                                    onOpenProfile = {
                                        runWithPrivateKey(PendingKeyAction.Profile) {
                                            nav.navigate("myprofile") { launchSingleTop = true }
                                        }
                                    },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                )
                            }
                        }
                    }
                    composable("channels") {
                        LaunchedEffect(Unit) {
                            currentServiceTab = ServiceTab.Channels
                            nav.navigate("services") {
                                popUpTo("channels") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    composable("status") {
                        LaunchedEffect(Unit) {
                            currentServiceTab = ServiceTab.Status
                            nav.navigate("services") {
                                popUpTo("status") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    composable("diary") {
                        DiaryScreen(
                            onBack = { nav.popBackStack() },
                            refreshTodayRequest = memoRefreshTodayRequest,
                            onNewPost = {
                                selectedMemo = null
                                replyToId = null
                                replyToPubkey = null
                                replyToPreview = null
                                replyNoteContext = NoteContext.Timeline
                                showPostSheet = true
                            },
                            onOpenMemo = { memo ->
                                selectedMemo = memo
                                replyToId = memo.replyToId
                                replyToPubkey = memo.replyToPubkey
                                replyToPreview = null
                                replyNoteContext = memo.channelId
                                    ?.let { NoteContext.Channel(it) }
                                    ?: NoteContext.Timeline
                                showPostSheet = true
                            },
                            onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                replyNoteContext = NoteContext.Timeline
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            ownPubkey = ownPubkey,
                        )
                    }
                    composable<ChannelRoute> { backStack ->
                        val route = backStack.toRoute<ChannelRoute>()
                        ChannelScreen(
                            channelId = route.channelId,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onReply = { eventId, authorPk, preview, chId ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                replyNoteContext = noteContextForChannel(chId)
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenThread = { eventId ->
                                nav.navigate(ThreadRoute(eventId, source = ThreadSourceChannel, channelId = route.channelId))
                            },
                            onOpenLikes = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "likes", ThreadSourceChannel, route.channelId))
                            },
                            onOpenReposts = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "reposts", ThreadSourceChannel, route.channelId))
                            },
                            ownPubkey = ownPubkey,
                        )
                    }
                    composable<ThreadRoute> { backStack ->
                        val route = backStack.toRoute<ThreadRoute>()
                        ThreadScreen(
                            eventId = route.eventId,
                            initialTab = route.initialTab,
                            channelId = route.channelId.takeIf { it.isNotBlank() },
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onReply = { eventId, authorPk, preview, chId ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                replyNoteContext = noteContextForChannel(chId)
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenThread = { eventId ->
                                nav.navigate(
                                    ThreadRoute(
                                        eventId = eventId,
                                        source = route.source,
                                        channelId = route.channelId,
                                    ),
                                )
                            },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes", route.source, route.channelId)) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts", route.source, route.channelId)) },
                            ownPubkey = ownPubkey,
                        )
                    }
                    composable("myprofile") {
                        val pubkey = ownPubkey ?: return@composable
                        MyProfileScreen(
                            ownPubkey = pubkey,
                            onBack = { nav.popBackStack() },
                            onOpenFollowing = { nav.navigate(FollowingRoute(pubkey)) },
                            onOpenFollowers = { nav.navigate(FollowersRoute(pubkey)) },
                            onOpenSettings = { showQuickSettings = true },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                replyNoteContext = NoteContext.Timeline
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onAccountChanged = ::handleAccountChanged,
                            onAddAccountClick = {
                                pendingKeyAction = null
                                showKeySetup = true
                            },
                            onMuteListClick = { nav.navigate("mute-list") },
                            onNgWordClick = { nav.navigate("ng-words") },
                            onCustomEmojiClick = { nav.navigate(CustomEmojiRoute()) },
                        )
                    }
                    composable("mute-list") {
                        MuteListScreen(
                            onBack = { nav.popBackStack() },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                        )
                    }
                    composable("ng-words") {
                        NgWordScreen(onBack = { nav.popBackStack() })
                    }
                    composable("relay-settings") {
                        RelaySettingsScreen(onBack = { nav.popBackStack() })
                    }
                    composable<CustomEmojiRoute> { backStack ->
                        val route = backStack.toRoute<CustomEmojiRoute>()
                        CustomEmojiSettingsScreen(
                            onBack = { nav.popBackStack() },
                            initialQuery = route.query,
                        )
                    }
                    composable<SearchRoute> { backStack ->
                        val route = backStack.toRoute<SearchRoute>()
                        SearchScreen(
                            initialQuery = route.query,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                        )
                    }
                    composable<FollowingRoute> { backStack ->
                        val route = backStack.toRoute<FollowingRoute>()
                        FollowListScreen(
                            mode = FollowListMode.FOLLOWING,
                            ownPubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                        )
                    }
                    composable<FollowersRoute> { backStack ->
                        val route = backStack.toRoute<FollowersRoute>()
                        FollowListScreen(
                            mode = FollowListMode.FOLLOWERS,
                            ownPubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                        )
                    }
                    composable<ProfileRoute> { backStack ->
                        val route = backStack.toRoute<ProfileRoute>()
                        UserProfileScreen(
                            pubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            isOwnProfile = false,
                            ownPubkey = ownPubkey,
                            onOpenFollowing = { nav.navigate(FollowingRoute(route.pubkey)) },
                            onOpenFollowers = { nav.navigate(FollowersRoute(route.pubkey)) },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                replyNoteContext = NoteContext.Timeline
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                        )
                    }
                }
            }
            }
        }

        QuickSettingsDialogs(
            open = showQuickSettings,
            ownPubkey = ownPubkey,
            onOpenChange = { showQuickSettings = it },
            onAccountChanged = ::handleAccountChanged,
            onAddAccountClick = {
                pendingKeyAction = null
                showKeySetup = true
            },
            onRelaySettingsClick = { nav.navigate("relay-settings") },
            onCustomEmojiSettingsClick = { nav.navigate(CustomEmojiRoute()) },
            onOpenAllSettings = { nav.navigate("settings") },
            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
        )

        if (showPostSheet) {
            PostSheet(
                onDismiss = {
                    localDraft = null
                    showPostSheet = false
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    replyNoteContext = NoteContext.Timeline
                    selectedMemo = null
                },
                onCancel = { draft ->
                    if (selectedMemo == null) {
                        localDraft = draft
                    }
                    showPostSheet = false
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    replyNoteContext = NoteContext.Timeline
                    selectedMemo = null
                },
                onMemoSaved = {
                    memoRefreshTodayRequest++
                    nav.navigate("diary") { launchSingleTop = true }
                },
                replyToId = replyToId,
                replyToPubkey = replyToPubkey,
                replyToPreview = replyToPreview,
                noteContext = replyNoteContext,
                initialMemo = selectedMemo ?: localDraft,
                initialMemoRestoreMessage = if (selectedMemo == null && localDraft != null) {
                    "下書きを復元しました"
                } else {
                    null
                },
                saveLocalDraftOnCancel = selectedMemo == null,
            )
        }

        if (showKeySetup) {
            KeySetupScreen(
                onSetupComplete = { pubkeyHex ->
                    val action = pendingKeyAction
                    showKeySetup = false
                    pendingKeyAction = null
                    // 保存直後に導出済みの公開鍵を直接セット（Keychain 再読み込み不要）
                    ownPubkey = pubkeyHex
                    ownProfile = null
                    FollowRepository.reload()
                    when (action) {
                        PendingKeyAction.NewPost -> {
                            selectedMemo = null
                            replyToId = null
                            replyToPubkey = null
                            replyToPreview = null
                            replyNoteContext = NoteContext.Timeline
                            currentFeedTab = FeedTab.Global
                            feedScrollToTopTargetTab = FeedTab.Global
                            feedTabChangeRequest++
                            nav.navigate("feed") {
                                popUpTo("feed") { inclusive = true }
                                launchSingleTop = true
                            }
                            showPostSheet = true
                        }
                        PendingKeyAction.Reply -> showPostSheet = true
                        PendingKeyAction.Diary -> {
                            nav.navigate("diary") { launchSingleTop = true }
                        }
                        PendingKeyAction.Profile -> {
                            currentFeedTab = FeedTab.Global
                            feedScrollToTopTargetTab = FeedTab.Global
                            feedTabChangeRequest++
                            nav.navigate("feed") {
                                popUpTo("feed") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        PendingKeyAction.Status -> {
                            navigateServiceTab(ServiceTab.Status)
                            showStatusComposer = true
                        }
                        null -> {
                            currentFeedTab = FeedTab.Global
                            feedScrollToTopTargetTab = FeedTab.Global
                            feedTabChangeRequest++
                            nav.navigate("feed") {
                                popUpTo("feed") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                },
                onDismiss = {
                    pendingKeyAction = null
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    replyNoteContext = NoteContext.Timeline
                    selectedMemo = null
                    showKeySetup = false
                },
            )
        }
    }
}

@Composable
private fun PostFloatingActionButton(
    onPostClick: () -> Unit,
) {
    FloatingActionButton(onClick = onPostClick) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "ポスト",
            modifier = Modifier.size(24.dp),
        )
    }
}
