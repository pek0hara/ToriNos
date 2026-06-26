package com.nostr.torinos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavOptionsBuilder
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.GroupRef
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.noteContextForChannel
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.ChannelCacheStore
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.article.ArticleDetailScreen
import com.nostr.torinos.ui.article.ArticleHubScreen
import com.nostr.torinos.ui.article.UserArticleListScreen
import com.nostr.torinos.ui.channel.ChannelListScreen
import com.nostr.torinos.ui.channel.ChannelScreen
import com.nostr.torinos.ui.feed.FeedTab
import com.nostr.torinos.ui.feed.FeedScreen
import com.nostr.torinos.ui.live.LiveDetailScreen
import com.nostr.torinos.ui.live.LiveHubScreen
import com.nostr.torinos.ui.group.Nip29GroupListScreen
import com.nostr.torinos.ui.group.Nip29GroupScreen
import com.nostr.torinos.ui.notification.NotificationsDrawer
import com.nostr.torinos.ui.notification.NotificationsViewModel
import com.nostr.torinos.ui.settings.MuteListScreen
import com.nostr.torinos.ui.settings.NgWordScreen
import com.nostr.torinos.ui.post.JournalScreen
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
@Serializable data class Nip29GroupRoute(val relayUrl: String, val groupId: String)
@Serializable data class ProfileRoute(val pubkey: String)
@Serializable data class UserJournalRoute(val pubkey: String)
@Serializable data class ArticleRoute(val pubkey: String, val identifier: String)
@Serializable data class UserArticlesRoute(val pubkey: String)
@Serializable data class LiveRoute(val pubkey: String, val identifier: String)
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
    Journal,
    Live,
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
        var selectedMemoDeleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        var localDraft by remember { mutableStateOf<PostMemoData?>(null) }
        var memoRefreshTodayRequest by remember { mutableStateOf(0) }
        var journalToggleCalendarRequest by remember { mutableStateOf(0) }
        var journalShowCalendarRequest by remember { mutableStateOf(0) }
        var liveCreateRequest by remember { mutableStateOf(0) }
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
        var feedChromeCollapseFraction by remember { mutableStateOf(0f) }
        var notificationsScrollToTopRequest by remember { mutableStateOf(0) }
        var showQuickSettings by remember { mutableStateOf(false) }
        var relaySettingsNavigationRequest by remember { mutableStateOf(0) }
        var accountStateResetKey by remember { mutableIntStateOf(0) }
        val notificationsDrawerState = rememberDrawerState(DrawerValue.Closed)
        val followingFeedListState = remember(accountStateResetKey) { LazyListState() }
        val globalFeedListState = remember(accountStateResetKey) { LazyListState() }
        var currentServiceTab by remember { mutableStateOf(ServiceTab.Articles) }

        fun navigateTopLevelRoute(route: String) {
            if (currentRoute == route) return
            val poppedToFeed = nav.popBackStack(route = "feed", inclusive = true, saveState = true)
            if (!poppedToFeed) {
                currentRoute?.let { nav.popBackStack(route = it, inclusive = true, saveState = true) }
            }
            nav.navigate(route) {
                launchSingleTop = true
                restoreState = true
            }
        }

        fun navigateFeedTab() {
            feedChromeCollapseFraction = 0f
            navigateTopLevelRoute("feed")
        }

        fun currentProfileRoute(): String? {
            val route = nav.currentBackStackEntry?.destination?.route ?: currentRoute ?: return null
            val routeName = route.substringBefore("/")
            return route.takeIf { it == "myprofile" || routeName.endsWith("ProfileRoute") }
        }

        fun NavOptionsBuilder.closeProfileRoute() {
            val route = currentProfileRoute() ?: return
            popUpTo(route) { inclusive = true }
        }

        fun navigateJournalTab() {
            navigateTopLevelRoute("journal")
        }

        fun navigateServiceTab(tab: ServiceTab) {
            currentServiceTab = tab
            navigateTopLevelRoute("services")
        }

        fun navigateNextServiceTab() {
            val currentIndex = ServiceTab.entries.indexOf(currentServiceTab).coerceAtLeast(0)
            val nextIndex = (currentIndex + 1) % ServiceTab.entries.size
            navigateServiceTab(ServiceTab.entries[nextIndex])
        }

        fun requestRelaySettings() {
            showQuickSettings = false
            relaySettingsNavigationRequest++
        }

        LaunchedEffect(relaySettingsNavigationRequest) {
            if (relaySettingsNavigationRequest <= 0) return@LaunchedEffect
            nav.navigate("relay-settings") {
                launchSingleTop = true
                closeProfileRoute()
            }
        }

        // 未登録カスタム絵文字タップ → 絵文字設定画面（検索クエリ付き）へ遷移
        LaunchedEffect(Unit) {
            CustomEmojiStore.openSearchEvent.collect { shortcode ->
                nav.navigate(CustomEmojiRoute(query = shortcode)) { closeProfileRoute() }
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
            liveCreateRequest = 0
            replyToId = null
            replyToPubkey = null
            replyToPreview = null
            replyNoteContext = NoteContext.Timeline
            selectedMemo = null
            selectedMemoDeleteAction = null
            localDraft = null
            FollowRepository.reload()
            MuteStore.resetForAccountChange()
            accountStateResetKey++
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
            liveCreateRequest = 0
            replyToId = null
            replyToPubkey = null
            replyToPreview = null
            replyNoteContext = NoteContext.Timeline
            selectedMemo = null
            selectedMemoDeleteAction = null
            localDraft = null
            FollowRepository.reload()
            MuteStore.resetForAccountChange()
            accountStateResetKey++
            currentFeedTab = FeedTab.Global
            feedScrollToTopTargetTab = FeedTab.Global
            feedTabChangeRequest++
            nav.navigate("feed") {
                popUpTo("feed") { inclusive = true }
                launchSingleTop = true
            }
        }

        val bottomBarRoutes = setOf("feed", "services", "channels", "status", "journal")
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
        val density = LocalDensity.current
        val bottomBarHeightPx = with(density) { AppNavigationBarHeight.toPx() }.toInt()
        val activeFeedChromeCollapseFraction = if (currentRoute == "feed") feedChromeCollapseFraction else 0f
        val collapsedBottomBarHeightPx = (bottomBarHeightPx * (1f - activeFeedChromeCollapseFraction)).toInt()
        val bottomBarAlpha = 1f - activeFeedChromeCollapseFraction

        LaunchedEffect(currentRoute) {
            if (currentRoute != "feed") {
                feedChromeCollapseFraction = 0f
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
            onRelaySettingsClick = {
                requestRelaySettings()
            },
            onCustomEmojiSettingsClick = {
                nav.navigate(CustomEmojiRoute()) { closeProfileRoute() }
            },
            onOpenAllSettings = {
                nav.navigate("settings") { closeProfileRoute() }
            },
            onUserClick = { pk ->
                nav.navigate(ProfileRoute(pk)) { closeProfileRoute() }
            },
        )

        ModalNavigationDrawer(
            drawerState = notificationsDrawerState,
            drawerContent = {
                NotificationsDrawer(
                    ownPubkey = ownPubkey,
                    isOpen = notificationsDrawerState.currentValue == DrawerValue.Open,
                    scrollToTopRequest = notificationsScrollToTopRequest,
                    onUserClick = { pubkey ->
                        scope.launch { notificationsDrawerState.close() }
                        nav.navigate(ProfileRoute(pubkey)) { closeProfileRoute() }
                    },
                    onOpenThread = { eventId ->
                        scope.launch { notificationsDrawerState.close() }
                        nav.navigate(ThreadRoute(eventId)) { closeProfileRoute() }
                    },
                )
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                containerColor = MaterialTheme.colorScheme.background,
                floatingActionButton = {
                    if (isWriteSupported) {
                        when (currentRoute) {
                            "feed" -> PostFloatingActionButton(
                                onPostClick = {
                                    runWithPrivateKey(PendingKeyAction.NewPost) {
                                        selectedMemo = null
                                        selectedMemoDeleteAction = null
                                        replyToId = null
                                        replyToPubkey = null
                                        replyToPreview = null
                                        replyNoteContext = NoteContext.Timeline
                                        showPostSheet = true
                                    }
                                },
                            )
                            "services" -> when (currentServiceTab) {
                                ServiceTab.Live -> AppFloatingActionButton(onClick = {
                                    runWithPrivateKey(PendingKeyAction.Live) {
                                        liveCreateRequest++
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "ライブを投稿")
                                }
                                ServiceTab.Status -> AppFloatingActionButton(onClick = {
                                    runWithPrivateKey(PendingKeyAction.Status) {
                                        showStatusComposer = true
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "ステータス追加")
                                }
                                else -> Unit
                            }
                            "status" -> AppFloatingActionButton(onClick = {
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
                        Box(
                            modifier = Modifier
                                .height(with(density) { collapsedBottomBarHeightPx.toDp() })
                                .clipToBounds()
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            NavigationBar(
                                modifier = Modifier
                                    .requiredHeight(AppNavigationBarHeight)
                                    .alpha(bottomBarAlpha),
                                containerColor = MaterialTheme.colorScheme.background,
                                tonalElevation = 0.dp,
                            ) {
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Home,
                                            contentDescription = null,
                                            modifier = Modifier.size(if (currentRoute == "feed") 26.dp else 24.dp),
                                        )
                                    },
                                    selected = currentRoute == "feed",
                                    colors = appNavigationBarItemColors(),
                                    onClick = {
                                        feedChromeCollapseFraction = 0f
                                        if (currentRoute == "feed") {
                                            feedScrollToTopTargetTab = currentFeedTab
                                            feedScrollToTopRequest++
                                        } else {
                                            navigateFeedTab()
                                        }
                                    },
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Today,
                                            contentDescription = null,
                                            modifier = Modifier.size(if (currentRoute == "journal") 26.dp else 24.dp),
                                        )
                                    },
                                    selected = currentRoute == "journal",
                                    colors = appNavigationBarItemColors(),
                                    onClick = {
                                        runWithPrivateKey(PendingKeyAction.Journal) {
                                            if (currentRoute == "journal") {
                                                journalToggleCalendarRequest++
                                            } else {
                                                journalShowCalendarRequest++
                                                navigateJournalTab()
                                            }
                                        }
                                    },
                                )
                                val isServiceRoute = currentRoute == "services" ||
                                    currentRoute == "channels" ||
                                    isChannelRoute ||
                                    isChannelThreadRoute ||
                                    currentRoute == "status"
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Apps,
                                            contentDescription = null,
                                            modifier = Modifier.size(if (isServiceRoute) 26.dp else 24.dp),
                                        )
                                    },
                                    selected = isServiceRoute,
                                    colors = appNavigationBarItemColors(),
                                    onClick = {
                                        if (currentRoute == "services") {
                                            navigateNextServiceTab()
                                        } else {
                                            navigateServiceTab(currentServiceTab)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
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
                            onOpenRelaySettings = {
                                requestRelaySettings()
                            },
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
                            accountResetKey = accountStateResetKey,
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
                            chromeCollapseFraction = feedChromeCollapseFraction,
                            onChromeCollapseFractionChange = { feedChromeCollapseFraction = it },
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
                                    onOpenRelaySettings = {
                                        requestRelaySettings()
                                    },
                                    onOpenSettings = { showQuickSettings = true },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                )
                            }
                            ServiceTab.Groups -> {
                                Nip29GroupListScreen(
                                    ownPubkey = ownPubkey,
                                    ownProfile = ownProfile,
                                    onGroupClick = { ref ->
                                        nav.navigate(Nip29GroupRoute(ref.relayUrl, ref.groupId))
                                    },
                                    onOpenProfile = {
                                        runWithPrivateKey(PendingKeyAction.Profile) {
                                            nav.navigate("myprofile") { launchSingleTop = true }
                                        }
                                    },
                                    onOpenRelaySettings = {
                                        requestRelaySettings()
                                    },
                                    onOpenSettings = { showQuickSettings = true },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                )
                            }
                            ServiceTab.Articles -> {
                                ArticleHubScreen(
                                    ownPubkey = ownPubkey,
                                    ownProfile = ownProfile,
                                    onOpenProfile = {
                                        runWithPrivateKey(PendingKeyAction.Profile) {
                                            nav.navigate("myprofile") { launchSingleTop = true }
                                        }
                                    },
                                    onOpenSettings = { showQuickSettings = true },
                                    onOpenRelaySettings = {
                                        requestRelaySettings()
                                    },
                                    onArticleClick = { pubkey, identifier ->
                                        nav.navigate(ArticleRoute(pubkey, identifier))
                                    },
                                    onAuthorClick = { pubkey ->
                                        nav.navigate(UserArticlesRoute(pubkey))
                                    },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                )
                            }
                            ServiceTab.Live -> {
                                LiveHubScreen(
                                    ownPubkey = ownPubkey,
                                    ownProfile = ownProfile,
                                    onOpenProfile = {
                                        runWithPrivateKey(PendingKeyAction.Profile) {
                                            nav.navigate("myprofile") { launchSingleTop = true }
                                        }
                                    },
                                    onOpenSettings = { showQuickSettings = true },
                                    onOpenRelaySettings = {
                                        requestRelaySettings()
                                    },
                                    onLiveClick = { pubkey, identifier ->
                                        nav.navigate(LiveRoute(pubkey, identifier))
                                    },
                                    onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                    createLiveRequest = liveCreateRequest,
                                    onCreateLiveRequestConsumed = { liveCreateRequest = 0 },
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
                                    onOpenRelaySettings = {
                                        requestRelaySettings()
                                    },
                                    onOpenSettings = { showQuickSettings = true },
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
                    composable<ArticleRoute> { backStack ->
                        val route = backStack.toRoute<ArticleRoute>()
                        ArticleDetailScreen(
                            pubkey = route.pubkey,
                            identifier = route.identifier,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onNoteClick = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                        )
                    }
                    composable<LiveRoute> { backStack ->
                        val route = backStack.toRoute<LiveRoute>()
                        LiveDetailScreen(
                            pubkey = route.pubkey,
                            identifier = route.identifier,
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                        )
                    }
                    composable<UserArticlesRoute> { backStack ->
                        val route = backStack.toRoute<UserArticlesRoute>()
                        UserArticleListScreen(
                            pubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onArticleClick = { pubkey, identifier ->
                                nav.navigate(ArticleRoute(pubkey, identifier))
                            },
                        )
                    }
                    composable("journal") {
                        JournalScreen(
                            onBack = { nav.popBackStack() },
                            refreshTodayRequest = memoRefreshTodayRequest,
                            toggleCalendarRequest = journalToggleCalendarRequest,
                            showCalendarRequest = journalShowCalendarRequest,
                            accountKey = accountStateResetKey.toString(),
                            onNewPost = {
                                selectedMemo = null
                                selectedMemoDeleteAction = null
                                replyToId = null
                                replyToPubkey = null
                                replyToPreview = null
                                replyNoteContext = NoteContext.Timeline
                                showPostSheet = true
                            },
                            onOpenMemo = { memo, deleteAction ->
                                selectedMemo = memo
                                selectedMemoDeleteAction = deleteAction
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
                            onOpenArticle = { pubkey, identifier -> nav.navigate(ArticleRoute(pubkey, identifier)) },
                            ownPubkey = ownPubkey,
                            ownProfile = ownProfile,
                            onOpenRelaySettings = {
                                requestRelaySettings()
                            },
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
                    composable<Nip29GroupRoute> { backStack ->
                        val route = backStack.toRoute<Nip29GroupRoute>()
                        Nip29GroupScreen(
                            ref = GroupRef.create(route.relayUrl, route.groupId),
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onDeleted = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
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
                            onOpenFollowing = {
                                nav.navigate(FollowingRoute(pubkey)) { closeProfileRoute() }
                            },
                            onOpenFollowers = {
                                nav.navigate(FollowersRoute(pubkey)) { closeProfileRoute() }
                            },
                            onOpenSettings = { showQuickSettings = true },
                            onUserClick = { pk ->
                                nav.navigate(ProfileRoute(pk)) { closeProfileRoute() }
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
                            onOpenReplies = { eventId ->
                                nav.navigate(ThreadRoute(eventId)) { closeProfileRoute() }
                            },
                            onOpenLikes = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "likes")) { closeProfileRoute() }
                            },
                            onOpenReposts = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "reposts")) { closeProfileRoute() }
                            },
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
                        val isOwnRouteProfile = route.pubkey == ownPubkey
                        UserProfileScreen(
                            pubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            isOwnProfile = isOwnRouteProfile,
                            ownPubkey = ownPubkey,
                            onOpenFollowing = {
                                nav.navigate(FollowingRoute(route.pubkey)) { closeProfileRoute() }
                            },
                            onOpenFollowers = {
                                nav.navigate(FollowersRoute(route.pubkey)) { closeProfileRoute() }
                            },
                            onUserClick = { pk ->
                                nav.navigate(ProfileRoute(pk)) { closeProfileRoute() }
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
                            onOpenReplies = { eventId ->
                                nav.navigate(ThreadRoute(eventId)) { closeProfileRoute() }
                            },
                            onOpenLikes = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "likes")) { closeProfileRoute() }
                            },
                            onOpenReposts = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "reposts")) { closeProfileRoute() }
                            },
                            onOpenJournal = if (!isOwnRouteProfile) {
                                {
                                    nav.navigate(UserJournalRoute(route.pubkey))
                                }
                            } else null,
                        )
                    }
                    composable<UserJournalRoute> { backStack ->
                        val route = backStack.toRoute<UserJournalRoute>()
                        JournalScreen(
                            onBack = { nav.popBackStack() },
                            refreshTodayRequest = 0,
                            onNewPost = {},
                            onOpenMemo = { _, _ -> },
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
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                            onOpenArticle = { pubkey, identifier -> nav.navigate(ArticleRoute(pubkey, identifier)) },
                            ownPubkey = ownPubkey,
                            ownProfile = ownProfile,
                            onOpenRelaySettings = {
                                requestRelaySettings()
                            },
                            targetPubkey = route.pubkey,
                        )
                    }
                }
            }
            }
        }

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
                    selectedMemoDeleteAction = null
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
                    selectedMemoDeleteAction = null
                },
                onMemoSaved = {
                    memoRefreshTodayRequest++
                    navigateJournalTab()
                },
                onDeleteMemo = selectedMemoDeleteAction?.let { deleteAction ->
                    {
                        localDraft = null
                        showPostSheet = false
                        replyToId = null
                        replyToPubkey = null
                        replyToPreview = null
                        replyNoteContext = NoteContext.Timeline
                        selectedMemo = null
                        selectedMemoDeleteAction = null
                        deleteAction()
                    }
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
                onOpenCustomEmojiSettings = { draft ->
                    if (selectedMemo == null) {
                        localDraft = draft
                    }
                    showPostSheet = false
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    replyNoteContext = NoteContext.Timeline
                    selectedMemo = null
                    selectedMemoDeleteAction = null
                    nav.navigate(CustomEmojiRoute()) { closeProfileRoute() }
                },
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
                    MuteStore.resetForAccountChange()
                    accountStateResetKey++
                    when (action) {
                        PendingKeyAction.NewPost -> {
                            selectedMemo = null
                            selectedMemoDeleteAction = null
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
                        PendingKeyAction.Journal -> {
                            if (currentRoute == "journal") {
                                journalToggleCalendarRequest++
                            } else {
                                journalShowCalendarRequest++
                                navigateJournalTab()
                            }
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
                        PendingKeyAction.Live -> {
                            navigateServiceTab(ServiceTab.Live)
                            liveCreateRequest++
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
                    selectedMemoDeleteAction = null
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
    AppFloatingActionButton(onClick = onPostClick) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "ポスト",
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun AppFloatingActionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 1.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 1.dp,
        ),
        content = content,
    )
}

@Composable
private fun appNavigationBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor = Color.Transparent,
)

private val AppNavigationBarHeight = 80.dp
