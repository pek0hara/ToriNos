package com.nostr.torinos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.account.AccountSessionState
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.account.accountScopedViewModelKey
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.noteContextForChannel
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.ChannelCacheStore
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.FollowRepository
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayListSynchronizer
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.article.ArticleDetailScreen
import com.nostr.torinos.ui.article.ArticleEditorScreen
import com.nostr.torinos.ui.article.ArticleHubScreen
import com.nostr.torinos.ui.article.UserArticleListScreen
import com.nostr.torinos.ui.channel.ChannelListScreen
import com.nostr.torinos.ui.channel.ChannelScreen
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.LocalQuotePostHandler
import com.nostr.torinos.ui.feed.FeedTab
import com.nostr.torinos.ui.feed.FeedScreen
import com.nostr.torinos.ui.live.LiveDetailScreen
import com.nostr.torinos.ui.live.LiveHubScreen
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
@Serializable data class ProfileRoute(val pubkey: String)
@Serializable data class UserJournalRoute(val pubkey: String)
@Serializable data class ArticleRoute(val pubkey: String, val identifier: String)
@Serializable data class ArticleEditorRoute(val pubkey: String, val identifier: String)
@Serializable data class UserArticlesRoute(val pubkey: String)
@Serializable data class LiveRoute(val pubkey: String, val identifier: String, val openChat: Boolean = false)
@Serializable data class FollowingRoute(val pubkey: String)
@Serializable data class FollowersRoute(val pubkey: String)
@Serializable data class SearchRoute(val query: String = "")
@Serializable data class CustomEmojiRoute(val query: String = "")
@Serializable data class ThreadRoute(
    val eventId: String,
    val initialTab: String = "auto",
    val source: String = "",
    val channelId: String = "",
)

private const val ThreadSourceChannel = "channel"
private const val AgeVerificationKey = "age_verification_status_v1"
private const val AgeVerificationAccepted = "accepted_13_or_older"
private const val AgeVerificationBlocked = "blocked_under_13"

private enum class PendingKeyAction {
    NewPost,
    Article,
    Reply,
    Quote,
    Profile,
    Status,
    Journal,
    Live,
}

@Composable
fun App() {
    NostrTheme {
        val accountSessionState by AccountSessions.manager.state.collectAsState()
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        var showPostSheet by remember { mutableStateOf(false) }
        var showStatusComposer by remember { mutableStateOf(false) }
        var replyToId by remember { mutableStateOf<String?>(null) }
        var replyToPubkey by remember { mutableStateOf<String?>(null) }
        var replyToPreview by remember { mutableStateOf<String?>(null) }
        var quoteToId by remember { mutableStateOf<String?>(null) }
        var quoteToPubkey by remember { mutableStateOf<String?>(null) }
        var quoteToPreview by remember { mutableStateOf<String?>(null) }
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
        val snackbarHostState = remember { SnackbarHostState() }
        var snackbarFailedRelays by remember { mutableStateOf<List<String>>(emptyList()) }
        var publishFailureDialogRelays by remember { mutableStateOf<List<String>?>(null) }
        val uiExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught UI coroutine exception")
        }

        var ownPubkey by remember { mutableStateOf<String?>(null) }
        var muteAccountPubkey by remember { mutableStateOf<String?>(null) }
        var ownProfile by remember { mutableStateOf<NostrProfile?>(null) }
        var isAccountLoaded by remember { mutableStateOf(false) }
        val notificationsViewModel = ownPubkey?.let { pubkey ->
            viewModel<NotificationsViewModel>(
                key = accountScopedViewModelKey("notifications-$pubkey"),
                factory = viewModelFactory { initializer { NotificationsViewModel(pubkey) } },
            )
        }
        val notificationsState = notificationsViewModel?.state?.collectAsState()?.value
        DisposableEffect(notificationsViewModel) {
            onDispose { notificationsViewModel?.stopForAccountChange() }
        }
        var feedScrollToTopRequest by remember { mutableStateOf(0) }
        var currentFeedTab by remember { mutableStateOf(FeedTab.Following) }
        var feedScrollToTopTargetTab by remember { mutableStateOf(FeedTab.Following) }
        var feedTabChangeRequest by remember { mutableStateOf(0) }
        var feedChromeCollapseFraction by remember { mutableStateOf(0f) }
        var notificationsScrollToTopRequest by remember { mutableStateOf(0) }
        var showQuickSettings by remember { mutableStateOf(false) }
        var relaySettingsNavigationRequest by remember { mutableStateOf(0) }
        var accountStateResetKey by remember { mutableIntStateOf(0) }
        var ageVerificationStatus by remember { mutableStateOf<String?>(null) }
        var isAgeVerificationLoaded by remember { mutableStateOf(false) }
        val notificationsDrawerState = rememberDrawerState(DrawerValue.Closed)
        val profileDrawerState = rememberDrawerState(DrawerValue.Closed)
        var profileDrawerPubkey by remember { mutableStateOf<String?>(null) }
        var profileDrawerContentReady by remember { mutableStateOf(false) }
        var hasProfileDrawerOpened by remember { mutableStateOf(false) }
        val drawerTransitionMutex = remember { Mutex() }
        val followingFeedListState = remember(accountStateResetKey) { LazyListState() }
        val globalFeedListState = remember(accountStateResetKey) { LazyListState() }
        var currentServiceTab by remember { mutableStateOf(ServiceTab.Articles) }

        LaunchedEffect(Unit) {
            AccountSessions.manager.initialize()
        }

        LaunchedEffect(accountSessionState) {
            when (val state = accountSessionState) {
                is AccountSessionState.Active -> {
                    if (ownPubkey != state.session.pubkey) {
                        ownPubkey = state.session.pubkey
                        ownProfile = null
                    }
                    if (muteAccountPubkey != state.session.pubkey) {
                        muteAccountPubkey = state.session.pubkey
                        MuteStore.resetForAccountChange(state.session.pubkey)
                    }
                    isAccountLoaded = true
                }
                is AccountSessionState.Anonymous -> {
                    ownPubkey = null
                    ownProfile = null
                    if (muteAccountPubkey != null) {
                        muteAccountPubkey = null
                        MuteStore.resetForAccountChange(null)
                    }
                    isAccountLoaded = true
                }
                AccountSessionState.Loading,
                is AccountSessionState.Switching,
                -> isAccountLoaded = false
            }
        }

        fun openProfileDrawer(pubkey: String) {
            scope.launch {
                drawerTransitionMutex.withLock {
                    profileDrawerContentReady = false
                    notificationsDrawerState.close()
                    profileDrawerState.close()
                    profileDrawerPubkey = pubkey
                    profileDrawerState.open()
                    profileDrawerContentReady = true
                }
            }
        }

        fun openNotificationsDrawer() {
            scope.launch {
                drawerTransitionMutex.withLock {
                    profileDrawerState.close()
                    notificationsDrawerState.open()
                }
            }
        }

        fun closeProfileDrawerAndThen(action: () -> Unit) {
            scope.launch {
                profileDrawerState.close()
                action()
            }
        }

        fun currentProfileRoute(): String? {
            val route = nav.currentBackStackEntry?.destination?.route ?: currentRoute ?: return null
            val routeName = route.substringBefore("/")
            return route.takeIf { it == "myprofile" || routeName.endsWith("ProfileRoute") }
        }

        fun navigateTopLevelRoute(route: String) {
            if (currentRoute == route) return
            currentProfileRoute()?.let { profileRoute ->
                nav.popBackStack(route = profileRoute, inclusive = true)
            }
            val activeRoute = nav.currentBackStackEntry?.destination?.route
            if (activeRoute == route) return
            val poppedToFeed = nav.popBackStack(route = "feed", inclusive = true, saveState = true)
            if (!poppedToFeed) {
                activeRoute?.let { nav.popBackStack(route = it, inclusive = true, saveState = true) }
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
            }
        }

        // 未登録カスタム絵文字タップ → 絵文字設定画面（検索クエリ付き）へ遷移
        LaunchedEffect(Unit) {
            CustomEmojiStore.openSearchEvent.collect { shortcode ->
                nav.navigate(CustomEmojiRoute(query = shortcode))
            }
        }

        LaunchedEffect(Unit) {
            try {
                ageVerificationStatus = LocalSettingsStorage.getString(AgeVerificationKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load age verification status")
                ageVerificationStatus = null
            } finally {
                isAgeVerificationLoaded = true
            }
        }

        // 起動時のアカウント復元は AccountSessionManager が担当する。DB整理は独立して行う。
        LaunchedEffect(Unit) {
            try {
                ChannelCacheStore.prune()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to prune channel cache")
            }
        }

        // ownPubkey が確定したら共通プロフィールキャッシュを監視する。
        LaunchedEffect(ownPubkey) {
            val pk = ownPubkey ?: return@LaunchedEffect
            try {
                ownProfile = ProfileRepository.getCached(pk)
                ProfileRepository.ensureProfiles(
                    setOf(pk),
                    ProfileFetchPolicy.CacheFirst(15 * 60 * 1_000L),
                )
                ProfileRepository.observe(pk).collect { ownProfile = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load own profile")
            }
        }

        // ログイン時に、他クライアントから公開済みの NIP-65 リレーリストを端末設定へ反映する。
        // まずアカウント別キャッシュを即時表示し、リレー設定の同期後にもう一度取得する。
        LaunchedEffect(ownPubkey, accountStateResetKey) {
            val pk = ownPubkey
            RelayStore.activateAccount(pk)
            FollowRepository.reload()
            if (pk == null) {
                RelayListSynchronizer.stopObserving()
                return@LaunchedEffect
            }
            try {
                RelayListSynchronizer.syncFromRelays(pk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to synchronize relay list")
            }
            FollowRepository.refresh()
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

        fun requestOwnProfile() {
            runWithPrivateKey(PendingKeyAction.Profile, ::openProfileDrawer)
        }

        fun requestNotifications() {
            openNotificationsDrawer()
        }

        LaunchedEffect(profileDrawerState.currentValue, profileDrawerState.targetValue) {
            when {
                profileDrawerState.currentValue == DrawerValue.Open -> {
                    hasProfileDrawerOpened = true
                }
                profileDrawerState.currentValue == DrawerValue.Closed &&
                    profileDrawerState.targetValue == DrawerValue.Closed &&
                    hasProfileDrawerOpened -> {
                    hasProfileDrawerOpened = false
                    profileDrawerContentReady = false
                    profileDrawerPubkey = null
                }
            }
        }

        LaunchedEffect(notificationsDrawerState.currentValue) {
            if (notificationsDrawerState.currentValue == DrawerValue.Open) {
                notificationsViewModel?.markAllRead()
                notificationsScrollToTopRequest++
            }
        }

        fun clearLocalAccountState() {
            scope.launch { profileDrawerState.close() }
            profileDrawerContentReady = false
            profileDrawerPubkey = null
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
            quoteToId = null
            quoteToPubkey = null
            quoteToPreview = null
            replyNoteContext = NoteContext.Timeline
            selectedMemo = null
            selectedMemoDeleteAction = null
            localDraft = null
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
            scope.launch { profileDrawerState.close() }
            profileDrawerContentReady = false
            profileDrawerPubkey = null
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
            quoteToId = null
            quoteToPubkey = null
            quoteToPreview = null
            replyNoteContext = NoteContext.Timeline
            selectedMemo = null
            selectedMemoDeleteAction = null
            localDraft = null
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
                nav.navigate(CustomEmojiRoute())
            },
            onOpenAllSettings = {
                nav.navigate("settings")
            },
            onUserClick = { pk ->
                openProfileDrawer(pk)
            },
        )

        CompositionLocalProvider(
            LocalQuotePostHandler provides { event: NostrEvent ->
                selectedMemo = null
                selectedMemoDeleteAction = null
                localDraft = null
                replyToId = null
                replyToPubkey = null
                replyToPreview = null
                replyNoteContext = NoteContext.Timeline
                quoteToId = event.id
                quoteToPubkey = event.pubkey
                quoteToPreview = event.content.ifBlank { "投稿 ${event.id.take(8)}" }
                runWithPrivateKey(PendingKeyAction.Quote) {
                    showPostSheet = true
                }
            },
        ) {
        AppModalNavigationDrawer(
            drawerState = profileDrawerState,
            endDrawer = false,
            gesturesEnabled = profileDrawerState.currentValue != DrawerValue.Closed ||
                profileDrawerState.targetValue != DrawerValue.Closed,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ProfileDrawerWidthFraction),
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                ) {
                        val drawerPubkey = profileDrawerPubkey
                        when {
                            drawerPubkey == null -> Unit
                            drawerPubkey == ownPubkey -> MyProfileScreen(
                            ownPubkey = drawerPubkey,
                            onBack = { scope.launch { profileDrawerState.close() } },
                            onOpenFollowing = {
                                closeProfileDrawerAndThen { nav.navigate(FollowingRoute(drawerPubkey)) }
                            },
                            onOpenFollowers = {
                                closeProfileDrawerAndThen { nav.navigate(FollowersRoute(drawerPubkey)) }
                            },
                            onOpenSettings = {
                                closeProfileDrawerAndThen { showQuickSettings = true }
                            },
                            onUserClick = ::openProfileDrawer,
                            onReply = { eventId, authorPk, preview ->
                                closeProfileDrawerAndThen {
                                    replyToId = eventId
                                    replyToPubkey = authorPk
                                    replyToPreview = preview
                                    replyNoteContext = NoteContext.Timeline
                                    runWithPrivateKey(PendingKeyAction.Reply) { showPostSheet = true }
                                }
                            },
                            onOpenReplies = { eventId ->
                                closeProfileDrawerAndThen { nav.navigate(ThreadRoute(eventId)) }
                            },
                            onOpenLikes = { eventId ->
                                closeProfileDrawerAndThen { nav.navigate(ThreadRoute(eventId, "likes")) }
                            },
                            onOpenReposts = { eventId ->
                                closeProfileDrawerAndThen { nav.navigate(ThreadRoute(eventId, "reposts")) }
                            },
                        )
                            !profileDrawerContentReady -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                            else -> UserProfileScreen(
                            pubkey = drawerPubkey,
                            onBack = { scope.launch { profileDrawerState.close() } },
                            isOwnProfile = false,
                            ownPubkey = ownPubkey,
                            onOpenFollowing = {
                                closeProfileDrawerAndThen { nav.navigate(FollowingRoute(drawerPubkey)) }
                            },
                            onOpenFollowers = {
                                closeProfileDrawerAndThen { nav.navigate(FollowersRoute(drawerPubkey)) }
                            },
                            onUserClick = ::openProfileDrawer,
                            onReply = { eventId, authorPk, preview ->
                                closeProfileDrawerAndThen {
                                    replyToId = eventId
                                    replyToPubkey = authorPk
                                    replyToPreview = preview
                                    replyNoteContext = NoteContext.Timeline
                                    runWithPrivateKey(PendingKeyAction.Reply) { showPostSheet = true }
                                }
                            },
                            onOpenReplies = { eventId ->
                                closeProfileDrawerAndThen { nav.navigate(ThreadRoute(eventId)) }
                            },
                            onOpenLikes = { eventId ->
                                closeProfileDrawerAndThen { nav.navigate(ThreadRoute(eventId, "likes")) }
                            },
                            onOpenReposts = { eventId ->
                                closeProfileDrawerAndThen { nav.navigate(ThreadRoute(eventId, "reposts")) }
                            },
                            onOpenJournal = {
                                closeProfileDrawerAndThen { nav.navigate(UserJournalRoute(drawerPubkey)) }
                            },
                            )
                        }
                    }
            },
        ) {
            AppModalNavigationDrawer(
                drawerState = notificationsDrawerState,
                endDrawer = true,
                gesturesEnabled = true,
                drawerContent = {
                    NotificationsDrawer(
                        ownPubkey = ownPubkey,
                        isOpen = notificationsDrawerState.currentValue == DrawerValue.Open,
                        scrollToTopRequest = notificationsScrollToTopRequest,
                        onUserClick = ::openProfileDrawer,
                        onOpenThread = { eventId ->
                            scope.launch { notificationsDrawerState.close() }
                            nav.navigate(ThreadRoute(eventId))
                        },
                    )
                },
            ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = {
                    SnackbarHost(snackbarHostState) { data ->
                        Snackbar(
                            modifier = Modifier.clickable(enabled = snackbarFailedRelays.isNotEmpty()) {
                                publishFailureDialogRelays = snackbarFailedRelays
                                data.dismiss()
                            },
                        ) {
                            Text(data.visuals.message)
                        }
                    }
                },
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
                                ServiceTab.Articles -> AppFloatingActionButton(
                                    onClick = {
                                        runWithPrivateKey(PendingKeyAction.Article) {
                                            nav.navigate("article-editor")
                                        }
                                    },
                                    icon = Icons.Default.Add,
                                    contentDescription = "記事を書く",
                                )
                                ServiceTab.Live -> AppFloatingActionButton(
                                    onClick = {
                                        runWithPrivateKey(PendingKeyAction.Live) {
                                            liveCreateRequest++
                                        }
                                    },
                                    icon = Icons.Default.Add,
                                    contentDescription = "ライブを投稿",
                                )
                                ServiceTab.Status -> AppFloatingActionButton(
                                    onClick = {
                                        runWithPrivateKey(PendingKeyAction.Status) {
                                            showStatusComposer = true
                                        }
                                    },
                                    icon = Icons.Default.Add,
                                    contentDescription = "ステータス追加",
                                )
                                else -> Unit
                            }
                            "status" -> AppFloatingActionButton(
                                onClick = {
                                    runWithPrivateKey(PendingKeyAction.Status) {
                                        showStatusComposer = true
                                    }
                                },
                                icon = Icons.Default.Add,
                                contentDescription = "ステータス追加",
                            )
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
                                requestNotifications()
                            },
                            onUserClick = ::openProfileDrawer,
                            onOpenProfile = {
                                requestOwnProfile()
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
                                        requestOwnProfile()
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
                                        requestOwnProfile()
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
                                        requestOwnProfile()
                                    },
                                    onOpenSettings = { showQuickSettings = true },
                                    onOpenRelaySettings = {
                                        requestRelaySettings()
                                    },
                                    onLiveClick = { pubkey, identifier ->
                                        nav.navigate(LiveRoute(pubkey, identifier))
                                    },
                                    onLiveChatClick = { pubkey, identifier ->
                                        nav.navigate(LiveRoute(pubkey, identifier, openChat = true))
                                    },
                                    onUserClick = ::openProfileDrawer,
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
                                    onUserClick = ::openProfileDrawer,
                                    onOpenProfile = {
                                        requestOwnProfile()
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
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onEditArticle = { pubkey, identifier ->
                                nav.navigate(ArticleEditorRoute(pubkey, identifier))
                            },
                            onUserClick = ::openProfileDrawer,
                            onNoteClick = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                        )
                    }
                    composable("article-editor") {
                        ArticleEditorScreen(
                            onBack = { nav.popBackStack() },
                            onPublished = { pubkey, identifier ->
                                nav.navigate(ArticleRoute(pubkey, identifier)) {
                                    popUpTo("article-editor") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable<ArticleEditorRoute> { backStack ->
                        val route = backStack.toRoute<ArticleEditorRoute>()
                        val selectedArticleRelayUrl by RelayStore.selectedArticleRelayUrl.collectAsState()
                        ArticleEditorScreen(
                            editPubkey = route.pubkey,
                            editIdentifier = route.identifier,
                            relayUrl = selectedArticleRelayUrl,
                            onBack = { nav.popBackStack() },
                            onPublished = { pubkey, identifier ->
                                nav.navigate(ArticleRoute(pubkey, identifier)) {
                                    popUpTo(ArticleEditorRoute(route.pubkey, route.identifier)) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable<LiveRoute> { backStack ->
                        val route = backStack.toRoute<LiveRoute>()
                        LiveDetailScreen(
                            pubkey = route.pubkey,
                            identifier = route.identifier,
                            openChatInitially = route.openChat,
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = ::openProfileDrawer,
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
                            onUserClick = ::openProfileDrawer,
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
                            onUserClick = ::openProfileDrawer,
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
                            onUserClick = ::openProfileDrawer,
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
                                nav.navigate(FollowingRoute(pubkey))
                            },
                            onOpenFollowers = {
                                nav.navigate(FollowersRoute(pubkey))
                            },
                            onOpenSettings = { showQuickSettings = true },
                            onUserClick = { pk ->
                                openProfileDrawer(pk)
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
                                nav.navigate(ThreadRoute(eventId))
                            },
                            onOpenLikes = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "likes"))
                            },
                            onOpenReposts = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "reposts"))
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
                            onUserClick = ::openProfileDrawer,
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
                            onUserClick = ::openProfileDrawer,
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
                            onUserClick = ::openProfileDrawer,
                        )
                    }
                    composable<FollowersRoute> { backStack ->
                        val route = backStack.toRoute<FollowersRoute>()
                        FollowListScreen(
                            mode = FollowListMode.FOLLOWERS,
                            ownPubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = ::openProfileDrawer,
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
                                nav.navigate(FollowingRoute(route.pubkey))
                            },
                            onOpenFollowers = {
                                nav.navigate(FollowersRoute(route.pubkey))
                            },
                            onUserClick = { pk ->
                                openProfileDrawer(pk)
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
                                nav.navigate(ThreadRoute(eventId))
                            },
                            onOpenLikes = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "likes"))
                            },
                            onOpenReposts = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "reposts"))
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
                            onUserClick = ::openProfileDrawer,
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
        }
        }

        if (isAgeVerificationLoaded && ageVerificationStatus != AgeVerificationAccepted) {
            AgeVerificationDialog(
                blocked = ageVerificationStatus == AgeVerificationBlocked,
                onAccept = {
                    ageVerificationStatus = AgeVerificationAccepted
                    scope.launch(uiExceptionHandler) {
                        try {
                            LocalSettingsStorage.putString(AgeVerificationKey, AgeVerificationAccepted)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logException("App", e, "Failed to save age verification acceptance")
                        }
                    }
                },
                onReject = {
                    ageVerificationStatus = AgeVerificationBlocked
                    scope.launch(uiExceptionHandler) {
                        try {
                            LocalSettingsStorage.putString(AgeVerificationKey, AgeVerificationBlocked)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logException("App", e, "Failed to save age verification rejection")
                        }
                    }
                },
                onRetry = {
                    ageVerificationStatus = null
                    scope.launch(uiExceptionHandler) {
                        try {
                            LocalSettingsStorage.putString(AgeVerificationKey, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logException("App", e, "Failed to reset age verification status")
                        }
                    }
                },
            )
        }

        if (showPostSheet) {
            PostSheet(
                onDismiss = {
                    localDraft = null
                    showPostSheet = false
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    quoteToId = null
                    quoteToPubkey = null
                    quoteToPreview = null
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
                    quoteToId = null
                    quoteToPubkey = null
                    quoteToPreview = null
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
                quoteToId = quoteToId,
                quoteToPubkey = quoteToPubkey,
                quoteToPreview = quoteToPreview,
                noteContext = replyNoteContext,
                initialMemo = selectedMemo ?: localDraft,
                initialMemoRestoreMessage = if (selectedMemo == null && localDraft != null) {
                    "下書きを復元しました"
                } else {
                    null
                },
                autoFocus = selectedMemo == null && replyToId == null && quoteToId == null,
                saveLocalDraftOnCancel = selectedMemo == null,
                onOpenCustomEmojiSettings = { draft ->
                    if (selectedMemo == null) {
                        localDraft = draft
                    }
                    showPostSheet = false
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    quoteToId = null
                    quoteToPubkey = null
                    quoteToPreview = null
                    replyNoteContext = NoteContext.Timeline
                    selectedMemo = null
                    selectedMemoDeleteAction = null
                    nav.navigate(CustomEmojiRoute())
                },
                onPosted = { eventId, postedReplyToId, postedNoteContext, publishResult ->
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarFailedRelays = publishResult.failedRelays.keys.toList()
                        snackbarHostState.showSnackbar(
                            message = publishResult.snackbarMessage(),
                            duration = if (publishResult.failureCount > 0) {
                                SnackbarDuration.Long
                            } else {
                                SnackbarDuration.Short
                            },
                        )
                        snackbarFailedRelays = emptyList()
                    }
                    if (postedReplyToId != null) {
                        when (postedNoteContext) {
                            is NoteContext.Channel -> {
                                nav.navigate(
                                    ThreadRoute(
                                        eventId = eventId,
                                        source = ThreadSourceChannel,
                                        channelId = postedNoteContext.channelId,
                                    ),
                                )
                            }
                            NoteContext.Timeline -> {
                                nav.navigate(ThreadRoute(eventId))
                            }
                        }
                    }
                },
            )
        }

        publishFailureDialogRelays?.let { failedRelays ->
            PublishFailureDialog(
                failedRelays = failedRelays,
                onDismiss = { publishFailureDialogRelays = null },
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
                        PendingKeyAction.Article -> {
                            currentServiceTab = ServiceTab.Articles
                            nav.navigate("article-editor")
                        }
                        PendingKeyAction.Reply -> showPostSheet = true
                        PendingKeyAction.Quote -> showPostSheet = true
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
                            openProfileDrawer(pubkeyHex)
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
                    quoteToId = null
                    quoteToPubkey = null
                    quoteToPreview = null
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
private fun AgeVerificationDialog(
    blocked: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRetry: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(if (blocked) "利用できません" else "年齢確認")
        },
        text = {
            Text(
                if (blocked) {
                    "ToriNos はユーザー投稿を含むソーシャルアプリです。13歳未満の方は利用できません。"
                } else {
                    "ToriNos はユーザー投稿を含むソーシャルアプリです。利用を続けるには、13歳以上であることを確認してください。"
                },
            )
        },
        confirmButton = {
            if (!blocked) {
                Button(onClick = onAccept) {
                    Text("13歳以上です")
                }
            }
        },
        dismissButton = {
            if (blocked) {
                TextButton(onClick = onRetry) {
                    Text("選択をやり直す")
                }
            } else {
                TextButton(onClick = onReject) {
                    Text("13歳未満です")
                }
            }
        },
    )
}

private fun RelayPublishResult.snackbarMessage(): String =
    if (failureCount == 0) {
        "すべてのリレーに送信成功しました。"
    } else {
        "送信しました。成功${successCount}リレー 失敗${failureCount}リレー"
    }

@Composable
private fun PublishFailureDialog(
    failedRelays: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("送信に失敗したリレー") },
        text = {
            Text(failedRelays.joinToString(separator = "\n"))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
private fun PostFloatingActionButton(
    onPostClick: () -> Unit,
) {
    AppFloatingActionButton(
        onClick = onPostClick,
        icon = Icons.Default.Add,
        contentDescription = "ポスト",
    )
}

@Composable
private fun AppModalNavigationDrawer(
    drawerState: DrawerState,
    endDrawer: Boolean,
    gesturesEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val contentLayoutDirection = LocalLayoutDirection.current
    val drawerLayoutDirection = if (endDrawer) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides drawerLayoutDirection) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                    drawerContent()
                }
            },
            content = {
                CompositionLocalProvider(LocalLayoutDirection provides contentLayoutDirection) {
                    content()
                }
            },
        )
    }
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
private const val ProfileDrawerWidthFraction = 0.92f
