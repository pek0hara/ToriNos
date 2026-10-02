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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sms
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.crypto.isWebPlatform
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.noteContextForChannel
import com.nostr.torinos.network.deleteLegacyChannelCacheDatabase
import com.nostr.torinos.ui.channel.ChannelReplyContextBuilder
import com.nostr.torinos.ui.channel.channelComposerRelayContext
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.ui.components.CustomEmojiOpenRequest
import com.nostr.torinos.ui.components.LocalCustomEmojiNavigator
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.network.DisplayPreferencesStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.resolveReplyTarget
import com.nostr.torinos.ui.article.ArticleDetailScreen
import com.nostr.torinos.ui.article.ArticleEditorScreen
import com.nostr.torinos.ui.article.ArticleAuthorFilter
import com.nostr.torinos.ui.article.ArticleHubScreen
import com.nostr.torinos.ui.article.UserArticleListScreen
import com.nostr.torinos.ui.channel.ChannelListScreen
import com.nostr.torinos.ui.channel.ChannelScreen
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.LocalQuotePostHandler
import com.nostr.torinos.ui.feed.FeedTab
import com.nostr.torinos.ui.feed.FeedChromeState
import com.nostr.torinos.ui.feed.FeedScreen
import com.nostr.torinos.ui.feed.shouldResetFeedAfterBackground
import com.nostr.torinos.ui.notification.NotificationsDrawer
import com.nostr.torinos.ui.notification.NotificationsViewModel
import com.nostr.torinos.ui.notification.NotificationTargetDestination
import com.nostr.torinos.ui.settings.MuteListScreen
import com.nostr.torinos.ui.settings.NgWordScreen
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.ui.post.FeedInlineComposerBackHandler
import com.nostr.torinos.ui.post.FeedInlinePostComposer
import com.nostr.torinos.ui.post.JournalScreen
import com.nostr.torinos.ui.post.PostViewModel
import com.nostr.torinos.ui.post.postFromFeedInline
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
import com.nostr.torinos.ui.status.StatusScreen
import com.nostr.torinos.ui.thread.ThreadScreen
import com.nostr.torinos.ui.thread.ThreadViewModel
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException
import kotlinx.serialization.Serializable
import kotlin.time.Clock

// 型安全なルート定義（パラメータ付き画面）
@Serializable data class ChannelRoute(val channelId: String)
@Serializable data class ProfileRoute(val pubkey: String)
@Serializable data class UserJournalRoute(val pubkey: String)
@Serializable data class ArticleRoute(val pubkey: String, val identifier: String)
@Serializable data class ArticleEditorRoute(val pubkey: String, val identifier: String)
@Serializable data class UserArticlesRoute(val pubkey: String)
@Serializable data class FollowingRoute(val pubkey: String)
@Serializable data class FollowersRoute(val pubkey: String)
@Serializable data class SearchRoute(val query: String = "")
@Serializable data class CustomEmojiRoute(
    val query: String = "",
    val imageUrl: String = "",
    val setAddress: String = "",
)
@Serializable data class ThreadRoute(
    val eventId: String,
    val initialTab: String = "auto",
    val source: String = "",
    val channelId: String = "",
)

private const val ThreadSourceChannel = "channel"
internal enum class PendingKeyAction {
    NewPost,
    Article,
    Reply,
    Quote,
    Profile,
    Status,
    Journal,
}

@Composable
internal fun AppSessionCoordinator(
    accountSession: AccountSession?,
    ageVerificationStatus: String?,
    isAgeVerificationLoaded: Boolean,
    onAgeVerificationChanged: (String?) -> Unit,
    pendingComposerRequests: PendingComposerRequestHolder,
) {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val composer = remember { ComposerCoordinator(pendingComposerRequests) }
        // 簡易コンポーザーと PostSheet が同じ入力状態・送信処理を共有する。
        val postViewModel = accountSessionViewModel<PostViewModel>(
            key = "post-composer",
        ) { session -> PostViewModel(session) }
        val postState by postViewModel.state.collectAsState()
        val useFooterComposer by DisplayPreferencesStore.useFooterComposer.collectAsState()
        val dismissInlineKeyboard = rememberDismissKeyboard()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var snackbarFailedRelays by remember { mutableStateOf<List<String>>(emptyList()) }
        var publishFailureDialogRelays by remember { mutableStateOf<List<String>?>(null) }
        val uiExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught UI coroutine exception")
        }
        val replyResolutionTracker = remember { ReplyResolutionTracker() }
        var replyResolutionJob by remember { mutableStateOf<Job?>(null) }

        val ownPubkey = accountSession?.pubkey
        var ownProfile by remember { mutableStateOf<NostrProfile?>(null) }
        val isAccountLoaded = true
        val notificationsViewModel = ownPubkey?.let { pubkey ->
            viewModel<NotificationsViewModel>(
                key = "notifications-$pubkey",
                factory = viewModelFactory {
                    initializer { NotificationsViewModel(pubkey, accountSession.muteStore) }
                },
            )
        }
        val notificationsState = notificationsViewModel?.state?.collectAsState()?.value
        var feedScrollToTopRequest by remember { mutableStateOf(0) }
        var currentFeedTab by remember { mutableStateOf(FeedTab.Following) }
        var feedScrollToTopTargetTab by remember { mutableStateOf(FeedTab.Following) }
        var feedTabChangeRequest by remember { mutableStateOf(0) }
        val feedChromeState = remember { FeedChromeState() }
        var feedLongBackgroundResetRequest by remember { mutableStateOf(0) }
        var backgroundedAtMillis by remember { mutableStateOf<Long?>(null) }
        var showQuickSettings by remember { mutableStateOf(false) }
        var relaySettingsNavigationRequest by remember { mutableStateOf(0) }
        val drawerCoordinator = rememberDrawerCoordinator(scope)
        val profileDrawerStateHolder = rememberSaveableStateHolder()
        var lastProfileDrawerPubkey by rememberSaveable { mutableStateOf<String?>(null) }
        val profileDrawerStateOwners = remember { mutableMapOf<String, String?>() }
        var handledProfileNavigationSessionId by remember { mutableStateOf(0) }
        val notificationsDrawerState = drawerCoordinator.notificationsState
        val profileDrawerState = drawerCoordinator.profileState
        val followingFeedListState = remember { LazyListState() }
        val globalFeedListState = remember { LazyListState() }
        var currentServiceTab by remember { mutableStateOf(ServiceTab.Articles) }
        // 記事タブの絞り込み。リレー切り替えでは維持し、アカウント切り替えとアプリ再起動で既定値へ戻る。
        var articleAuthorFilter by remember { mutableStateOf(ArticleAuthorFilter.Following) }
        var articleTopic by remember { mutableStateOf<String?>(null) }
        // 記事へのコメントは投稿後にスレッドへ遷移せず、記事詳細のコメント一覧を取り直す。
        var articleCommentParentId by remember { mutableStateOf<String?>(null) }
        var articleCommentPostedSignal by remember { mutableStateOf(0) }
        val appLifecycle = LocalLifecycleOwner.current.lifecycle

        LaunchedEffect(appLifecycle) {
            appLifecycle.currentStateFlow.collect { state ->
                val nowMillis = Clock.System.now().toEpochMilliseconds()
                if (state.isAtLeast(Lifecycle.State.STARTED)) {
                    backgroundedAtMillis?.let { backgroundedAt ->
                        if (shouldResetFeedAfterBackground(backgroundedAt, nowMillis)) {
                            feedLongBackgroundResetRequest++
                        }
                    }
                    backgroundedAtMillis = null
                } else if (backgroundedAtMillis == null) {
                    backgroundedAtMillis = nowMillis
                }
            }
        }

        fun openProfileDrawer(pubkey: String) {
            drawerCoordinator.openProfile(pubkey)
        }

        fun openNotificationsDrawer() {
            drawerCoordinator.openNotifications()
        }

        fun closeProfileDrawerAndThen(action: () -> Unit) {
            drawerCoordinator.closeProfileAndThen(action)
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
            feedChromeState.collapseFraction = 0f
            if (isWebPlatform) {
                if (currentRoute == "feed") return
                // URL の #journal から直接起動した場合も、グラフの起点を残してフィードへ戻す。
                nav.navigate("feed") {
                    popUpTo(nav.graph.id)
                    launchSingleTop = true
                }
                return
            }
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

        // 起動時のアカウント復元は AccountSessionManager が担当する。旧チャンネルDBの削除は独立して行う。
        LaunchedEffect(Unit) {
            try {
                deleteLegacyChannelCacheDatabase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to delete legacy channel cache")
            }
        }

        AccountTransitionEffect(
            session = accountSession,
            onOwnProfileChanged = { ownProfile = it },
        )

        fun runWithPrivateKey(
            missingAction: PendingKeyAction,
            onAvailable: (pubkeyHex: String) -> Unit,
        ) {
            val pubkey = ownPubkey
            if (pubkey != null) {
                onAvailable(pubkey)
            } else {
                composer.pendingKeyAction = missingAction
                // 鍵設定の完了でセッションごと作り直されるので、再開要求はセッションの外側に残す。
                if (missingAction == PendingKeyAction.NewPost) {
                    composer.pendingRequests.requestNewPost()
                }
                composer.showKeySetup = true
            }
        }

        fun cancelPendingReplyResolution() {
            replyResolutionTracker.invalidate()
            replyResolutionJob?.cancel()
            replyResolutionJob = null
        }

        fun openReplyComposer(
            event: NostrEvent,
            preview: String?,
            noteContext: NoteContext,
            parentRelayHint: String? = null,
        ) {
            cancelPendingReplyResolution()
            if (noteContext is NoteContext.Channel) {
                // 送信先の初期選択が最初から正しいよう、relay context を作ってから投稿画面を開く(第16.14節)。
                val request = replyResolutionTracker.begin()
                replyResolutionJob = scope.launch {
                    val relayContext = channelComposerRelayContext(noteContext.channelId)
                    if (!replyResolutionTracker.isCurrent(request)) return@launch
                    val target = ChannelReplyContextBuilder.replyTarget(
                        event, noteContext.channelId, parentRelayHint, relayContext,
                    )
                    replyResolutionJob = null
                    if (target == null) {
                        snackbarHostState.showSnackbar("返信元のスレッド情報を取得できませんでした")
                        return@launch
                    }
                    composer.prepareReply(target, preview, noteContext, relayContext)
                    runWithPrivateKey(PendingKeyAction.Reply) {
                        composer.openFullScreen()
                    }
                }
                return
            }
            val requiresRootResolution = noteContext == NoteContext.Timeline &&
                (event.kind == COMMENT_EVENT_KIND ||
                    (event.kind == 1 && event.tags.any { it.firstOrNull() == "e" }))
            if (!requiresRootResolution &&
                composer.prepareReply(event, preview, noteContext)
            ) {
                runWithPrivateKey(PendingKeyAction.Reply) {
                    composer.openFullScreen()
                }
                return
            }
            val request = replyResolutionTracker.begin()
            replyResolutionJob = scope.launch {
                val target = resolveReplyTarget(event, noteContext)
                if (!replyResolutionTracker.isCurrent(request)) return@launch
                if (target == null) {
                    replyResolutionJob = null
                    snackbarHostState.showSnackbar("返信元のスレッド情報を取得できませんでした")
                    return@launch
                }
                composer.prepareReply(target, preview, noteContext)
                runWithPrivateKey(PendingKeyAction.Reply) {
                    composer.openFullScreen()
                }
                replyResolutionJob = null
            }
        }

        fun openArticleCommentComposer(target: ReplyTarget, preview: String) {
            cancelPendingReplyResolution()
            composer.prepareReply(target, preview, NoteContext.Timeline)
            articleCommentParentId = target.parent.id
            runWithPrivateKey(PendingKeyAction.Reply) {
                composer.openFullScreen()
            }
        }

        fun requestOwnProfile() {
            runWithPrivateKey(PendingKeyAction.Profile, ::openProfileDrawer)
        }

        fun requestNotifications() {
            openNotificationsDrawer()
        }

        LaunchedEffect(profileDrawerState.currentValue, profileDrawerState.targetValue) {
            drawerCoordinator.onProfileStateChanged()
        }

        LaunchedEffect(
            drawerCoordinator.profileDestination?.stateKey,
            drawerCoordinator.profileNavigationSessionId,
        ) {
            val destination = drawerCoordinator.profileDestination ?: return@LaunchedEffect
            val sessionId = drawerCoordinator.profileNavigationSessionId
            val destinationPubkey = when (destination) {
                is ProfileDrawerDestination.Profile -> destination.pubkey
                is ProfileDrawerDestination.Following -> destination.pubkey
                is ProfileDrawerDestination.Followers -> destination.pubkey
                is ProfileDrawerDestination.Thread -> lastProfileDrawerPubkey
            }
            if (handledProfileNavigationSessionId != sessionId) {
                val nextPubkey = (destination as? ProfileDrawerDestination.Profile)?.pubkey
                profileDrawerStateOwners
                    .filterValues { ownerPubkey -> ownerPubkey != nextPubkey }
                    .keys
                    .toList()
                    .forEach { stateKey ->
                        profileDrawerStateHolder.removeState("profile-drawer-$stateKey")
                        profileDrawerStateOwners.remove(stateKey)
                    }
                handledProfileNavigationSessionId = sessionId
            }
            profileDrawerStateOwners[destination.stateKey] = destinationPubkey
            if (destinationPubkey != null) {
                lastProfileDrawerPubkey = destinationPubkey
            }
        }

        LaunchedEffect(notificationsDrawerState.currentValue) {
            if (notificationsDrawerState.currentValue == DrawerValue.Open) {
                notificationsViewModel?.markAllRead()
                drawerCoordinator.onNotificationsOpened()
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
        val isFeedRoute = currentRoute == "feed"
        val isNewPostRoute = currentRoute == "feed" || currentRoute == "journal"
        // フィードでは下部バーをリストに重ね、高さは変えずに下へずらして隠す。
        // 折りたたみ量はスクロール中に毎フレーム変わるので、graphicsLayerの中でだけ読む。
        fun activeFeedChromeCollapseFraction(): Float =
            if (isFeedRoute) feedChromeState.collapseFraction.coerceIn(0f, 1f) else 0f

        LaunchedEffect(currentRoute) {
            cancelPendingReplyResolution()
            if (currentRoute != "feed") {
                feedChromeState.collapseFraction = 0f
            }
        }

        // 画面が変わったら簡易コンポーザーを閉じ、新規投稿状態を破棄する。
        // フィードとジャーナル間でも入力を持ち越さない。
        // フィードタブ（フォロー／グローバル）の切り替えはルートが変わらないので閉じない。
        // 起動直後の最初のルート確定は画面の切り替えではないので、鍵設定後に再開した投稿欄を閉じない。
        var lastComposerRoute by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(currentRoute) {
            val previousRoute = lastComposerRoute
            if (currentRoute != null) lastComposerRoute = currentRoute
            if (previousRoute != null && currentRoute != null && currentRoute != previousRoute) {
                composer.closeInline(
                    isPosting = postViewModel.state.value.isPosting,
                    resetPost = postViewModel::reset,
                )
            }
        }

        // 設定でフッター投稿をオフにしたら、開いている簡易投稿欄と「…」で保持中の入力を破棄する。
        LaunchedEffect(useFooterComposer) {
            if (!useFooterComposer) {
                composer.closeInline(
                    isPosting = postViewModel.state.value.isPosting,
                    resetPost = postViewModel::reset,
                )
            }
        }

        // 鍵設定の完了でセッションが作り直された後、＋からの新規投稿を一度だけ再開する。
        LaunchedEffect(ownPubkey, composer.pendingRequests.registeringPubkey) {
            if (ownPubkey != null) {
                composer.resumeAfterKeySetup(ownPubkey, useFooterComposer, postViewModel::reset)
            }
        }

        // 送信中に「…」でフッターメニューへ切り替えた後の失敗は、簡易欄が見えないのでSnackbarで知らせる。
        // 本文とエラーは保持しているので、＋で開き直せばそのまま再送できる。
        LaunchedEffect(postViewModel) {
            var wasPosting = false
            postViewModel.state.collect { state ->
                val error = state.error
                if (wasPosting && !state.isPosting && error != null && composer.hasHeldInlineDraft) {
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Long)
                    }
                }
                wasPosting = state.isPosting
            }
        }

        // キーボードが閉じている状態のBackは、FABの「…」と同じく入力を保持してフッターメニューへ戻す。
        // キーボード表示中の最初のBackはシステムがキーボードを閉じる。
        FeedInlineComposerBackHandler(
            enabled = composer.presentation == ComposerPresentation.FeedInline,
            onBack = composer::switchInlineToMenu,
        )

        QuickSettingsDialogs(
            open = showQuickSettings,
            ownPubkey = ownPubkey,
            onOpenChange = { showQuickSettings = it },
            onAccountChanged = {},
            onAddAccountClick = {
                composer.pendingKeyAction = null
                composer.showKeySetup = true
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

        // カスタム絵文字タップ → 絵文字設定画面（対象絵文字付き）へ遷移。
        // 読み手（本文・リアクション）が多いので、再コンポーズのたびに値を変えない。
        val openCustomEmoji = remember(nav) {
            { request: CustomEmojiOpenRequest ->
                nav.navigate(
                    CustomEmojiRoute(
                        query = request.shortcode,
                        imageUrl = request.imageUrl,
                        setAddress = request.setAddress?.value.orEmpty(),
                    ),
                )
            }
        }
        CompositionLocalProvider(
            LocalCustomEmojiNavigator provides openCustomEmoji,
            LocalQuotePostHandler provides { event: NostrEvent ->
                cancelPendingReplyResolution()
                composer.prepareQuote(event)
                runWithPrivateKey(PendingKeyAction.Quote) {
                    composer.openFullScreen()
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
                    windowInsets = WindowInsets(0),
                ) {
                        val drawerDestination = drawerCoordinator.profileDestination
                        when {
                            drawerDestination == null -> Unit
                            !drawerCoordinator.isProfileContentReady -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                            else -> profileDrawerStateHolder.SaveableStateProvider(
                                key = "profile-drawer-${drawerDestination.stateKey}",
                            ) {
                                when (drawerDestination) {
                                    is ProfileDrawerDestination.Following -> FollowListScreen(
                                        mode = FollowListMode.FOLLOWING,
                                        ownPubkey = drawerDestination.pubkey,
                                        onBack = drawerCoordinator::navigateBackOrCloseProfile,
                                        onUserClick = ::openProfileDrawer,
                                    )
                                    is ProfileDrawerDestination.Followers -> FollowListScreen(
                                        mode = FollowListMode.FOLLOWERS,
                                        ownPubkey = drawerDestination.pubkey,
                                        onBack = drawerCoordinator::navigateBackOrCloseProfile,
                                        onUserClick = ::openProfileDrawer,
                                    )
                                    is ProfileDrawerDestination.Profile -> if (
                                        drawerDestination.pubkey == ownPubkey
                                    ) {
                                        MyProfileScreen(
                                            ownPubkey = drawerDestination.pubkey,
                                            onBack = drawerCoordinator::navigateBackOrCloseProfile,
                                            onOpenFollowing = {
                                                drawerCoordinator.openFollowing(drawerDestination.pubkey)
                                            },
                                            onOpenFollowers = {
                                                drawerCoordinator.openFollowers(drawerDestination.pubkey)
                                            },
                                            onOpenSettings = {
                                                closeProfileDrawerAndThen { showQuickSettings = true }
                                            },
                                            onUserClick = ::openProfileDrawer,
                                            onReply = { event, preview ->
                                                closeProfileDrawerAndThen {
                                                    openReplyComposer(event, preview, NoteContext.Timeline)
                                                }
                                            },
                                            onOpenReplies = { eventId ->
                                                drawerCoordinator.openThread(drawerDestination, eventId)
                                            },
                                            onOpenLikes = { eventId ->
                                                drawerCoordinator.openThread(drawerDestination, eventId, "likes")
                                            },
                                            onOpenReposts = { eventId ->
                                                drawerCoordinator.openThread(drawerDestination, eventId, "reposts")
                                            },
                                            longBackgroundResetRequest = feedLongBackgroundResetRequest,
                                        )
                                    } else {
                                        UserProfileScreen(
                                            pubkey = drawerDestination.pubkey,
                                            onBack = drawerCoordinator::navigateBackOrCloseProfile,
                                            isOwnProfile = false,
                                            ownPubkey = ownPubkey,
                                            onOpenFollowing = {
                                                drawerCoordinator.openFollowing(drawerDestination.pubkey)
                                            },
                                            onOpenFollowers = {
                                                drawerCoordinator.openFollowers(drawerDestination.pubkey)
                                            },
                                            onUserClick = ::openProfileDrawer,
                                            onReply = { event, preview ->
                                                closeProfileDrawerAndThen {
                                                    openReplyComposer(event, preview, NoteContext.Timeline)
                                                }
                                            },
                                            onOpenReplies = { eventId ->
                                                drawerCoordinator.openThread(drawerDestination, eventId)
                                            },
                                            onOpenLikes = { eventId ->
                                                drawerCoordinator.openThread(drawerDestination, eventId, "likes")
                                            },
                                            onOpenReposts = { eventId ->
                                                drawerCoordinator.openThread(drawerDestination, eventId, "reposts")
                                            },
                                            onOpenJournal = {
                                                closeProfileDrawerAndThen {
                                                    nav.navigate(UserJournalRoute(drawerDestination.pubkey))
                                                }
                                            },
                                            longBackgroundResetRequest = feedLongBackgroundResetRequest,
                                        )
                                    }
                                    is ProfileDrawerDestination.Thread -> {
                                        val channelId = drawerDestination.channelId
                                        val threadViewModel = accountSessionViewModel<ThreadViewModel>(
                                            key = "profile-drawer-thread-${drawerDestination.eventId}-${channelId ?: "note"}",
                                        ) { session ->
                                            ThreadViewModel(
                                                eventId = drawerDestination.eventId,
                                                noteContext = noteContextForChannel(channelId),
                                                accountSession = session,
                                            )
                                        }
                                        ThreadScreen(
                                            eventId = drawerDestination.eventId,
                                            initialTab = drawerDestination.initialTab,
                                            channelId = channelId,
                                            onBack = drawerCoordinator::navigateBackOrCloseProfile,
                                            enableSwipeBack = true,
                                            onUserClick = ::openProfileDrawer,
                                            onReply = { event, preview, chId ->
                                                closeProfileDrawerAndThen {
                                                    openReplyComposer(event, preview, noteContextForChannel(chId))
                                                }
                                            },
                                            onOpenThread = { eventId ->
                                                drawerCoordinator.openThread(
                                                    source = drawerDestination,
                                                    eventId = eventId,
                                                    channelId = channelId,
                                                )
                                            },
                                            onOpenLikes = { eventId ->
                                                drawerCoordinator.openThread(
                                                    source = drawerDestination,
                                                    eventId = eventId,
                                                    initialTab = "likes",
                                                    channelId = channelId,
                                                )
                                            },
                                            onOpenReposts = { eventId ->
                                                drawerCoordinator.openThread(
                                                    source = drawerDestination,
                                                    eventId = eventId,
                                                    initialTab = "reposts",
                                                    channelId = channelId,
                                                )
                                            },
                                            ownPubkey = ownPubkey,
                                            viewModel = threadViewModel,
                                        )
                                    }
                                }
                            }
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
                        scrollToTopRequest = drawerCoordinator.notificationsScrollToTopRequest,
                        onUserClick = ::openProfileDrawer,
                        onOpenTarget = { destination ->
                            scope.launch { notificationsDrawerState.close() }
                            when (destination) {
                                is NotificationTargetDestination.Thread -> nav.navigate(ThreadRoute(destination.eventId))
                                is NotificationTargetDestination.ChannelThread -> nav.navigate(
                                    ThreadRoute(destination.eventId, source = ThreadSourceChannel, channelId = destination.channelId),
                                )
                                is NotificationTargetDestination.Article -> nav.navigate(ArticleRoute(destination.pubkey, destination.identifier))
                            }
                        },
                    )
                },
            ) {
            AppScaffold(
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
                            // フィードと自分のジャーナルは、投稿の起動・展開を同じFABで扱う。
                            "feed", "journal" -> when (composer.presentation) {
                                ComposerPresentation.Hidden -> Box(
                                    modifier = Modifier.graphicsLayer {
                                        translationY = bottomBarHeightPx * activeFeedChromeCollapseFraction()
                                    },
                                ) {
                                    PostFloatingActionButton(
                                        onPostClick = {
                                            cancelPendingReplyResolution()
                                            runWithPrivateKey(PendingKeyAction.NewPost) {
                                                // 下部バーが一部隠れたまま簡易欄が開かないよう、先に表示状態へ戻す。
                                                feedChromeState.collapseFraction = 0f
                                                composer.openNewPost(useFooterComposer, postViewModel::reset)
                                            }
                                        },
                                    )
                                }
                                // △で投稿シートへ展開する（閉じるのは簡易欄左端の▼）。
                                ComposerPresentation.FeedInline -> {
                                    AppFloatingActionButton(
                                        onClick = {
                                            // キーボードを閉じてから全画面の投稿シートへ切り替える。
                                            dismissInlineKeyboard()
                                            composer.expandInline()
                                        },
                                        icon = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "詳細な投稿画面を開く",
                                    )
                                }
                                ComposerPresentation.FullScreen -> Unit
                            }
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
                                ServiceTab.Status -> AppFloatingActionButton(
                                    onClick = {
                                        runWithPrivateKey(PendingKeyAction.Status) {
                                            composer.showStatusComposer = true
                                        }
                                    },
                                    icon = Icons.Default.Sms,
                                    contentDescription = "ステータス追加",
                                )
                                else -> Unit
                            }
                            "status" -> AppFloatingActionButton(
                                onClick = {
                                    runWithPrivateKey(PendingKeyAction.Status) {
                                        composer.showStatusComposer = true
                                    }
                                },
                                icon = Icons.Default.Sms,
                                contentDescription = "ステータス追加",
                            )
                            else -> Unit
                        }
                    }
                },
                bottomBar = {
                    // フィードと自分のジャーナルで、同じ簡易投稿欄をフッターメニューと入れ替えて表示する。
                    if (isNewPostRoute && composer.presentation == ComposerPresentation.FeedInline) {
                        FeedInlinePostComposer(
                            state = postState,
                            onTextChange = postViewModel::onTextChange,
                            onSend = postViewModel::postFromFeedInline,
                            // 左端の▼で入力を保持したままフッターメニューへ戻す。
                            onClose = composer::switchInlineToMenu,
                            // iOS Safari はタップ中の focus でしかキーボードを出さないため、Web では自動フォーカスしない。
                            autoFocus = !isWebPlatform,
                        )
                    } else if (hasBottomBar) {
                        Box(
                            modifier = Modifier
                                .height(AppNavigationBarHeight)
                                .graphicsLayer {
                                    translationY = bottomBarHeightPx * activeFeedChromeCollapseFraction()
                                }
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            NavigationBar(
                                modifier = Modifier
                                    .requiredHeight(AppNavigationBarHeight)
                                    .graphicsLayer { alpha = 1f - activeFeedChromeCollapseFraction() },
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
                                        feedChromeState.collapseFraction = 0f
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
                                                composer.journalToggleCalendarRequest++
                                            } else {
                                                composer.journalShowCalendarRequest++
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
                    // フィードは下部バーの裏までリストを描き、余白はリストのcontentPaddingで取る。
                    .padding(bottom = if (currentRoute == "feed") 0.dp else padding.calculateBottomPadding()),
            ) {
                AppNavigationGraph(
                    navController = nav,
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
                            onReply = { event, preview ->
                                openReplyComposer(event, preview, NoteContext.Timeline)
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
                            longBackgroundResetRequest = feedLongBackgroundResetRequest,
                            chromeState = feedChromeState,
                            bottomContentPadding = padding.calculateBottomPadding(),
                            // 簡易投稿欄が開いている間は、下部クロームを表示状態に固定する。
                            chromeCollapseEnabled = composer.presentation != ComposerPresentation.FeedInline,
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
                                    authorFilter = articleAuthorFilter,
                                    onAuthorFilterChange = { articleAuthorFilter = it },
                                    topic = articleTopic,
                                    onTopicChange = { articleTopic = it },
                                    selectedServiceTab = currentServiceTab,
                                    onServiceTabSelected = { currentServiceTab = it },
                                )
                            }
                            ServiceTab.Status -> {
                                StatusScreen(
                                    ownPubkey = ownPubkey,
                                    ownProfile = ownProfile,
                                    showComposer = composer.showStatusComposer,
                                    onComposerShown = { composer.showStatusComposer = false },
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
                            onTopicClick = { topic ->
                                articleTopic = topic
                                currentServiceTab = ServiceTab.Articles
                                // 記事タブがスタックにあればそこまで戻し、途中の一覧や詳細を積み残さない。
                                if (!nav.popBackStack(route = "services", inclusive = false)) {
                                    navigateTopLevelRoute("services")
                                }
                            },
                            onComment = ::openArticleCommentComposer,
                            commentPostedSignal = articleCommentPostedSignal,
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
                    composable<UserArticlesRoute> { backStack ->
                        val route = backStack.toRoute<UserArticlesRoute>()
                        UserArticleListScreen(
                            pubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onArticleClick = { pubkey, identifier ->
                                nav.navigate(ArticleRoute(pubkey, identifier))
                            },
                            onUserClick = ::openProfileDrawer,
                        )
                    }
                    composable("journal") {
                        JournalScreen(
                            onBack = { nav.popBackStack() },
                            toggleCalendarRequest = composer.journalToggleCalendarRequest,
                            showCalendarRequest = composer.journalShowCalendarRequest,
                            accountKey = accountSession?.sessionId.orEmpty(),
                            onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onReply = { event, preview ->
                                openReplyComposer(event, preview, NoteContext.Timeline)
                            },
                            onUserClick = ::openProfileDrawer,
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
                            onReply = { event, preview, chId, parentRelayHint ->
                                openReplyComposer(event, preview, noteContextForChannel(chId), parentRelayHint)
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
                            onReply = { event, preview, chId ->
                                openReplyComposer(event, preview, noteContextForChannel(chId))
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
                            onReply = { event, preview ->
                                openReplyComposer(event, preview, NoteContext.Timeline)
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
                            longBackgroundResetRequest = feedLongBackgroundResetRequest,
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onAccountChanged = {},
                            onAddAccountClick = {
                                composer.pendingKeyAction = null
                                composer.showKeySetup = true
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
                            initialImageUrl = route.imageUrl,
                            initialSetAddress = EmojiSetAddress.parse(route.setAddress),
                        )
                    }
                    composable<SearchRoute> { backStack ->
                        val route = backStack.toRoute<SearchRoute>()
                        SearchScreen(
                            initialQuery = route.query,
                            onBack = { nav.popBackStack() },
                            onUserClick = ::openProfileDrawer,
                            onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId)) },
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
                            onReply = { event, preview ->
                                openReplyComposer(event, preview, NoteContext.Timeline)
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
                            longBackgroundResetRequest = feedLongBackgroundResetRequest,
                        )
                    }
                    composable<UserJournalRoute> { backStack ->
                        val route = backStack.toRoute<UserJournalRoute>()
                        JournalScreen(
                            onBack = { nav.popBackStack() },
                            onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onReply = { event, preview ->
                                openReplyComposer(event, preview, NoteContext.Timeline)
                            },
                            onUserClick = ::openProfileDrawer,
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

        // 年齢確認はアプリストアの審査要件のため、ストアを通さない Web 版では出さない。
        if (!isWebPlatform && isAgeVerificationLoaded && ageVerificationStatus != AgeVerificationAccepted) {
            AgeVerificationDialog(
                blocked = ageVerificationStatus == AgeVerificationBlocked,
                onAccept = { onAgeVerificationChanged(AgeVerificationAccepted) },
                onReject = { onAgeVerificationChanged(AgeVerificationBlocked) },
                onRetry = { onAgeVerificationChanged(null) },
            )
        }

        ComposerHost(
            coordinator = composer,
            postViewModel = postViewModel,
            onDraftSaved = {
                scope.launch {
                    snackbarHostState.showSnackbar("下書きを保存しました")
                }
            },
            onOpenCustomEmojiSettings = { nav.navigate(CustomEmojiRoute()) },
            onBackgroundPostFailed = { message ->
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
                }
            },
            onPosted = { completion ->
                val eventId = completion.eventId
                val postedReplyToId = completion.replyToId
                val postedNoteContext = completion.noteContext
                val publishResult = completion.publishResult
                val warning = completion.warning
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarFailedRelays = publishResult.failedRelays.keys.toList()
                    snackbarHostState.showSnackbar(
                        message = warning ?: publishResult.snackbarMessage(),
                        duration = if (warning != null || publishResult.failureCount > 0) {
                            SnackbarDuration.Long
                        } else {
                            SnackbarDuration.Short
                        },
                    )
                    snackbarFailedRelays = emptyList()
                }
                if (postedReplyToId != null && postedReplyToId == articleCommentParentId) {
                    articleCommentPostedSignal++
                } else if (postedReplyToId != null) {
                    when (postedNoteContext) {
                        is NoteContext.Channel -> nav.navigate(
                            ThreadRoute(
                                eventId = eventId,
                                source = ThreadSourceChannel,
                                channelId = postedNoteContext.channelId,
                            ),
                        )
                        NoteContext.Timeline -> nav.navigate(ThreadRoute(eventId))
                    }
                }
            },
        )

        publishFailureDialogRelays?.let { failedRelays ->
            PublishFailureDialog(
                failedRelays = failedRelays,
                onDismiss = { publishFailureDialogRelays = null },
            )
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
