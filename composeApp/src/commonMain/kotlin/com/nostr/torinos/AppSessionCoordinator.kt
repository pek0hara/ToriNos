package com.nostr.torinos

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import com.nostr.torinos.article.*
import com.nostr.torinos.ui.article.MatometrActions
import com.nostr.torinos.ui.article.LocalMatometrActions
import com.nostr.torinos.ui.article.ArticleDraftListScreen
import com.nostr.torinos.ui.post.restoreReplyTarget
import com.nostr.torinos.ui.post.PostSheetInitialState
import com.nostr.torinos.ui.post.PendingPostInput
import com.nostr.torinos.ui.post.PendingPostInputs
import com.nostr.torinos.ui.post.PendingPostInputDialog
import kotlinx.coroutines.flow.first
import com.nostr.torinos.model.extractNostrEventReferences
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import com.nostr.torinos.ui.components.LocalArticleNavigator
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
import com.nostr.torinos.ui.components.TopLevelBackHandler
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
import com.nostr.torinos.ui.settings.AccountSwitcherSheet
import com.nostr.torinos.ui.settings.CustomEmojiSettingsScreen
import com.nostr.torinos.ui.settings.SettingsScreen
import com.nostr.torinos.ui.service.EmojiServiceHeader
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
@Serializable data class ArticleDraftRoute(val draftId: String)
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
        var showQuoteInputChoice by remember { mutableStateOf(false) }
        var recoveryInputId by remember { mutableStateOf<String?>(null) }
        var recoveryOpening by remember { mutableStateOf<Long?>(null) }
        var showPendingInputs by remember { mutableStateOf(false) }
        var pendingInputs by remember { mutableStateOf<List<PendingPostInput>>(emptyList()) }
        var pendingInputBusy by remember { mutableStateOf(false) }
        var pendingInputError by remember { mutableStateOf<String?>(null) }
        var matometrError by remember { mutableStateOf<String?>(null) }
        val inputLock = remember { kotlinx.coroutines.sync.Mutex() }
        fun activeRecoveryInputId(): String? = recoveryInputId.takeIf { recoveryOpening == composer.openingGeneration }
        fun snapshot(): com.nostr.torinos.ui.post.PostMemoData? = composer.currentInputSnapshot(
            postViewModel.currentMemoSnapshot(composer.replyTarget, composer.replyNoteContext))
        fun restoreInput(memo: com.nostr.torinos.ui.post.PostMemoData, offerPendingInputs: Boolean = true) {
            val context = memo.channelId?.let { NoteContext.Channel(it) } ?: NoteContext.Timeline
            composer.clearPostContext(clearDraft = true)
            composer.replyNoteContext = context
            composer.replyTarget = memo.restoreReplyTarget(context)
            composer.quoteToId = memo.quoteEventId
            composer.quoteToPubkey = memo.quotePubkey
            composer.quoteToPreview = memo.quotePreview
            memo.selectedRelayUrls?.let { urls -> composer.replyRelayContext = com.nostr.torinos.ui.channel.ComposerRelayContext(urls, emptyList(), null) }
            composer.localDraft = memo
            postViewModel.restoreMemo(memo)
            composer.openFullScreen(PostSheetInitialState.KeepCurrent, offerPendingInputs)
        }
        fun offerPendingInputs(opening: Long) {
            if (!composer.shouldOfferPendingInputs(opening)) return
            val session = accountSession ?: return
            scope.launch {
                inputLock.lock()
                try {
                    if (session.inputFrozen.value || !composer.shouldOfferPendingInputs(opening)) return@launch
                    val inputs = PendingPostInputs(session.articleDraftStore, session.pubkey).list(activeRecoveryInputId())
                    if (session.inputFrozen.value || !composer.shouldOfferPendingInputs(opening)) return@launch
                    pendingInputs = inputs
                    pendingInputError = null
                    showPendingInputs = pendingInputs.isNotEmpty()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { matometrError = e.message }
                finally { inputLock.unlock() }
            }
        }
        fun resolvePendingInput(input: PendingPostInput, apply: Boolean) {
            val session = accountSession ?: return
            if (pendingInputBusy || session.inputFrozen.value) return
            pendingInputBusy = true
            pendingInputError = null
            scope.launch {
                inputLock.lock()
                try {
                    check(!session.inputFrozen.value) { "アカウント切替の保存処理中です" }
                    check(composer.presentation != ComposerPresentation.Hidden) { "投稿画面が閉じられました。開き直してください" }
                    val inputs = PendingPostInputs(session.articleDraftStore, session.pubkey)
                    if (apply) {
                        val current = postViewModel.state.value
                        check(!current.isPosting && !current.isUploadingAny && !current.hasFailedUpload && !current.isSavingMemo && !current.isLoadingMemo) {
                            "送信・画像アップロード・下書き操作の完了後に適用してください"
                        }
                        inputs.apply(input, snapshot(), activeRecoveryInputId()) { memo ->
                            check(!session.inputFrozen.value) { "アカウント切替の保存処理中です" }
                            restoreInput(memo, offerPendingInputs = false)
                            recoveryInputId = input.id
                            recoveryOpening = composer.openingGeneration
                        }
                        recoveryInputId = "current"
                    } else inputs.discard(input)
                    showPendingInputs = false
                    pendingInputs = emptyList()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { pendingInputError = e.message ?: "保留入力を処理できませんでした" }
                finally { inputLock.unlock(); pendingInputBusy = false }
            }
        }
        LaunchedEffect(composer.presentation, composer.openingGeneration) {
            when (composer.presentation) {
                ComposerPresentation.FeedInline -> offerPendingInputs(composer.openingGeneration)
                ComposerPresentation.Hidden -> { showPendingInputs = false; pendingInputs = emptyList() }
                ComposerPresentation.FullScreen -> Unit // PostSheet calls back after its initial restore/reset.
            }
        }
        fun quoteSelected(newPost: Boolean) {
            val session = accountSession ?: return
            val selection = session.matometrSelection.beginHandoff() ?: return
            scope.launch {
                inputLock.lock()
                try {
                    val current = postViewModel.state.value
                    check(!current.isPosting && !current.isUploadingAny && !current.hasFailedUpload && !current.isSavingMemo && !current.isLoadingMemo) {
                        "送信・画像アップロード・下書き操作の完了後に引用投稿へ切り替えてください"
                    }
                    val previous = snapshot()
                    val base = if (newPost) null else previous
                    val next = buildMatometrQuoteMemo(base, selection.posts)
                    val data = session.articleDraftStore.transaction(session.pubkey) { data ->
                        if (selection.handoffId in data.handoffs) data else {
                            val pending = if (newPost && previous != null) data.composers + ("pending-${articleLocalId()}" to previous) else data.composers
                            data.copy(composers = (pending - "current") + (selection.handoffId to next), handoffs = data.handoffs + selection.handoffId)
                        }
                    }
                    session.matometrSelection.markCommitted(selection.handoffId)
                    check(!session.inputFrozen.value) { "アカウント切替の保存処理中です。引用投稿を再試行してください" }
                    val committed = data.composers[selection.handoffId] ?: error("引き継ぎ入力が見つかりません")
                    if (newPost || previous == null) restoreInput(committed)
                    else {
                        postViewModel.restoreMemo(committed)
                        composer.quoteToId = committed.quoteEventId
                        composer.quoteToPubkey = committed.quotePubkey
                        composer.quoteToPreview = committed.quotePreview
                        composer.localDraft = committed
                        composer.openFullScreen(PostSheetInitialState.KeepCurrent)
                    }
                    // Keep the committed input in this account session until the next normal input save.
                    recoveryInputId = selection.handoffId
                    recoveryOpening = composer.openingGeneration
                    session.matometrSelection.finishHandoff()
                } catch (e: Exception) { session.matometrSelection.failHandoff(e.message ?: "引用投稿を開けませんでした") }
                finally { inputLock.unlock() }
            }
        }
        fun createMatometr() {
            val session = accountSession ?: return
            val selection = session.matometrSelection.beginHandoff() ?: return
            scope.launch {
                try {
                    selection.committedId?.let { id ->
                        nav.navigate(ArticleDraftRoute(id))
                        session.matometrSelection.finishHandoff()
                        return@launch
                    }
                    val store = session.articleDraftStore
                    val references = selection.posts.map { it.reference }
                    val target = selection.targetDraftId
                    val committed = if (target == null) {
                        val data = store.read(session.pubkey)
                        data.drafts[selection.handoffId] ?: run {
                            val next = ArticleDraft(draftId = selection.handoffId, ownerPubkey = session.pubkey,
                                mode = ArticleDraftMode.MatometrStructured, references = references)
                            check(next.content.length <= ARTICLE_CONTENT_MAX_LENGTH) { "生成後の本文は10,000文字までです。選択する投稿を減らしてください" }
                            store.save(next, null)
                        }
                    } else {
                        val draft = store.read(session.pubkey).drafts[target] ?: error("追加先の下書きが見つかりません")
                        if (draft.revision != selection.targetRevision) throw ArticleStoreConflict()
                        store.save(com.nostr.torinos.article.appendMatometrReferences(draft, references).getOrThrow(), draft.revision)
                    }
                    session.matometrSelection.markCommitted(committed.draftId)
                    selection.posts.forEach { com.nostr.torinos.ui.article.ArticleMemoryCache.putEvent(null, it.event) }
                    nav.navigate(ArticleDraftRoute(committed.draftId))
                    session.matometrSelection.finishHandoff()
                } catch (e: Exception) { session.matometrSelection.failHandoff(e.message ?: "記事を作成できませんでした") }
            }
        }
        val matometrActions = MatometrActions(quote = {
            if (snapshot() != null) showQuoteInputChoice = true else quoteSelected(true)
        }, article = ::createMatometr, openNote = { nav.navigate(ThreadRoute(it)) }, cancelAdd = {
            // Adding to a draft started from its editor; cancelling returns there instead of leaving the user on the feed.
            val session = accountSession
            val target = session?.matometrSelection?.state?.value?.targetDraftId
            session?.matometrSelection?.cancel()
            if (target != null) nav.navigate(ArticleDraftRoute(target))
        })
        DisposableEffect(accountSession, postViewModel) {
            val unregister = accountSession?.registerInput("post-composer") {
                postViewModel.state.first { !it.isPosting && !it.isUploadingAny && !it.isSavingMemo && !it.isLoadingMemo }
                check(!postViewModel.state.value.hasFailedUpload) { "未アップロードの画像があります。再試行または削除してください" }
                accountSession.matometrSelection.state.first { !it.busy }
                inputLock.lock()
                try {
                    val memo = snapshot()
                    accountSession.articleDraftStore.transaction(accountSession.pubkey) { data ->
                        val inputId = activeRecoveryInputId() ?: "current"
                        data.copy(composers = if (memo == null) data.composers - inputId else data.composers + (inputId to memo))
                    }
                } finally { inputLock.unlock() }
            }
            onDispose { unregister?.invoke() }
        }
        if (showQuoteInputChoice) AlertDialog(onDismissRequest = { showQuoteInputChoice = false },
            title = { Text("引用をどの入力に追加しますか？") },
            text = { Text("現在の入力を保ったまま追加するか、現在の入力を一時的に保持して別投稿を作成できます。保留入力は投稿を開いたときに適用・破棄を選べます。アプリ終了やアカウント切替で消えます。") },
            confirmButton = { TextButton(onClick = { showQuoteInputChoice = false; quoteSelected(false) }) {
                Text(if (composer.replyNoteContext is NoteContext.Channel) "チャンネル投稿に追加" else if (composer.replyTarget != null) "返信に追加" else "既存入力へ追加")
            } }, dismissButton = { TextButton(onClick = { showQuoteInputChoice = false; quoteSelected(true) }) { Text("別投稿を作成") } })
        matometrError?.let { message -> AlertDialog(onDismissRequest = { matometrError = null }, title = { Text("入力を引き継げませんでした") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { matometrError = null }) { Text("閉じる") } }) }
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
        var showAccountSwitcher by remember(ownPubkey) { mutableStateOf(false) }
        var relaySettingsNavigationRequest by remember { mutableStateOf(0) }
        val drawerCoordinator = rememberDrawerCoordinator(scope)
        val sidePanelWidth = LocalSidePanelWidth.current
        val isSidePanelMode = sidePanelWidth != null
        val sidePanelNavigator = rememberSidePanelNavigator()
        val profileDrawerStateHolder = rememberSaveableStateHolder()
        var lastProfileDrawerPubkey by rememberSaveable { mutableStateOf<String?>(null) }
        val profileDrawerStateOwners = remember { mutableMapOf<String, String?>() }
        var handledProfileNavigationSessionId by remember { mutableStateOf(0) }
        val notificationsDrawerState = drawerCoordinator.notificationsState
        val profileDrawerState = drawerCoordinator.profileState
        val followingFeedListState = remember { LazyListState() }
        val globalFeedListState = remember { LazyListState() }
        var currentServiceTab by remember { mutableStateOf(ServiceTab.Articles) }
        val footerConfig by DisplayPreferencesStore.footerConfig.collectAsState()
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
                    popUpTo(nav.graph.id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                return
            }
            navigateTopLevelRoute("feed")
        }

        // Pop the editor first so the tab switch does not save it into the services tab's restored stack.
        fun leaveEditorToPickPosts() {
            nav.popBackStack()
            navigateFeedTab()
        }

        fun navigateJournalTab() {
            navigateTopLevelRoute("journal")
        }

        fun hasBackStackEntry(route: String): Boolean =
            runCatching { nav.getBackStackEntry(route) }.isSuccess

        // URL から直接開いたチャンネル画面など、どのタブにも属さない画面は保存せずに閉じてから移動する。
        fun closeOrphanPagesAndNavigate(route: String) {
            nav.navigate(route) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }

        // 今いるタブの最上位。タブ切り替えは移動前に元のタブを取り除くので、通常はこのうち1つだけがスタックにある。
        // Web 版は起点のフィードが下に残ることがあるので、フィードは最後に調べる。
        fun currentTabRoot(): String? = listOf("services", "journal", "feed").firstOrNull(::hasBackStackEntry)

        // フッターから選ぶ。今いるタブの上に積んだ画面（チャンネル詳細など）は閉じてから移る。
        // 積んだまま切り替えると保存したスタックごと復元され、同じ画面に戻ってしまうため。
        // 行き先が別のタブなら、そのタブがスタックの下に残っていても（Web 版のフィード）戻る操作では移らない。
        // 戻ると今のタブの画面が破棄され、サービスのタブごとのスクロール位置や絞り込みが失われる。
        fun selectFooterRoute(route: String) {
            if (route == "feed") feedChromeState.collapseFraction = 0f
            val root = currentTabRoot()
            if (root == null) {
                // URL から直接開いたチャンネル画面など、どのタブにも属さない画面だけのとき。
                closeOrphanPagesAndNavigate(route)
                return
            }
            nav.popBackStack(route = root, inclusive = false)
            if (root == route) return
            when (route) {
                "feed" -> navigateFeedTab()
                "journal" -> navigateJournalTab()
                else -> navigateTopLevelRoute(route)
            }
        }

        fun selectServiceTab(tab: ServiceTab) {
            currentServiceTab = tab
            if (currentRoute == "services") return
            selectFooterRoute("services")
        }

        fun requestRelaySettings() {
            relaySettingsNavigationRequest++
        }

        LaunchedEffect(relaySettingsNavigationRequest) {
            if (relaySettingsNavigationRequest <= 0) return@LaunchedEffect
            nav.navigate("relay-settings") {
                launchSingleTop = true
            }
        }

        fun openSettings() {
            if (isSidePanelMode) {
                sidePanelNavigator.openRoot(SidePanelDestination.Settings)
            } else {
                nav.navigate("settings") { launchSingleTop = true }
            }
        }

        fun openSearch(query: String) {
            if (isSidePanelMode) {
                sidePanelNavigator.openRoot(SidePanelDestination.Search(query))
            } else {
                nav.navigate(SearchRoute(query))
            }
        }

        LaunchedEffect(isSidePanelMode) {
            drawerCoordinator.updateProfileDocking(isSidePanelMode)
            if (isSidePanelMode) return@LaunchedEffect
            // 狭い幅に戻ったら、右パネルで開いていた画面を中央の画面遷移で表示し続ける。
            val destinations = sidePanelNavigator.entries.map { it.destination }
            sidePanelNavigator.disposeAll()
            destinations.forEach { destination ->
                when (destination) {
                    SidePanelDestination.Settings -> nav.navigate("settings")
                    SidePanelDestination.MuteList -> nav.navigate("mute-list")
                    SidePanelDestination.NgWords -> nav.navigate("ng-words")
                    SidePanelDestination.RelaySettings -> nav.navigate("relay-settings")
                    is SidePanelDestination.CustomEmoji -> nav.navigate(destination.route)
                    is SidePanelDestination.Search -> nav.navigate(SearchRoute(destination.query))
                }
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
                is ProfileDrawerDestination.Thread,
                is ProfileDrawerDestination.CustomEmoji -> lastProfileDrawerPubkey
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
        val isWideLayout = LocalIsWideLayout.current
        val density = LocalDensity.current
        val bottomBarHeight = appNavigationBarHeight()
        val bottomBarHeightPx = with(density) { bottomBarHeight.toPx() }.toInt()
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

        // タブの最上位でのBackはすぐにアプリを閉じない。ジャーナルとコンテンツはホームへ戻し、
        // ホームでは案内を出して、続けてもう一度Backしたときだけシステムに任せて閉じる。
        // 簡易投稿欄やドロワーのBackを優先させるため、それらより先に登録する。
        var isExitArmed by remember { mutableStateOf(false) }
        var exitArmJob by remember { mutableStateOf<Job?>(null) }
        val isTopLevelRoute = (currentRoute == "feed" || currentRoute == "journal" || currentRoute == "services") &&
            nav.previousBackStackEntry == null
        TopLevelBackHandler(
            enabled = isTopLevelRoute && !(currentRoute == "feed" && isExitArmed),
        ) {
            if (currentRoute != "feed") {
                navigateFeedTab()
                return@TopLevelBackHandler
            }
            isExitArmed = true
            exitArmJob?.cancel()
            exitArmJob = scope.launch {
                launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("もう一度戻るとアプリを終了します")
                }
                delay(TopLevelExitWindowMillis)
                isExitArmed = false
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }

        // キーボードが閉じている状態のBackは、FABの「…」と同じく入力を保持してフッターメニューへ戻す。
        // キーボード表示中の最初のBackはシステムがキーボードを閉じる。
        FeedInlineComposerBackHandler(
            enabled = composer.presentation == ComposerPresentation.FeedInline,
            onBack = composer::switchInlineToMenu,
        )

        if (showAccountSwitcher) {
            AccountSwitcherSheet(
                ownPubkey = ownPubkey,
                onDismiss = { showAccountSwitcher = false },
                onAccountChanged = { showAccountSwitcher = false },
                onAddAccountClick = {
                    showAccountSwitcher = false
                    closeProfileDrawerAndThen {
                        composer.pendingKeyAction = null
                        composer.showKeySetup = true
                    }
                },
            )
        }

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
        val quotePostHandler = { event: NostrEvent ->
            cancelPendingReplyResolution()
            composer.prepareQuote(event)
            runWithPrivateKey(PendingKeyAction.Quote) {
                composer.openFullScreen()
            }
        }
        // 投稿の中の記事引用から記事を開く。
        val articleNavigator = remember(nav) {
            { pubkey: String, identifier: String -> nav.navigate(ArticleRoute(pubkey, identifier)) }
        }
        // 記事一覧の「投稿フィードに共有」。投稿の引用と同じく、記事を引用した投稿シートを開く。
        val articleShareHandler = { quote: com.nostr.torinos.ui.post.PostQuote ->
            cancelPendingReplyResolution()
            composer.prepareArticleQuote(quote)
            runWithPrivateKey(PendingKeyAction.Quote) {
                composer.openFullScreen()
            }
        }
        // プロフィールドロワーの中身。PCブラウザの横長画面では左パネルに表示する。
        val profilePanelContent: @Composable (ProfileDrawerDestination?, Boolean) -> Unit = { drawerDestination, isReady ->
            when {
                drawerDestination == null -> Unit
                !isReady -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                else -> CompositionLocalProvider(
                    LocalCustomEmojiNavigator provides { request ->
                        drawerCoordinator.openCustomEmoji(drawerDestination, request)
                    },
                ) {
                    profileDrawerStateHolder.SaveableStateProvider(
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
                                    onOpenAccountSwitcher = { showAccountSwitcher = true },
                                    onOpenStatus = {
                                        closeProfileDrawerAndThen { nav.navigate(StatusPageRoute) { launchSingleTop = true } }
                                    },
                                    onOpenSettings = {
                                        closeProfileDrawerAndThen { openSettings() }
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
                            is ProfileDrawerDestination.CustomEmoji -> CustomEmojiSettingsScreen(
                                onBack = drawerCoordinator::navigateBackOrCloseProfile,
                                onOpenRelaySettings = {
                                    closeProfileDrawerAndThen { requestRelaySettings() }
                                },
                                onOpenProfile = ::openProfileDrawer,
                                initialQuery = drawerDestination.request.shortcode,
                                initialImageUrl = drawerDestination.request.imageUrl,
                                initialSetAddress = drawerDestination.request.setAddress,
                                returnToSourceOnDetailBack = true,
                            )
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
        }
        // 右パネルの画面。設定の子画面はパネル内に積み、投稿は中央、ユーザーは左パネルで開く。
        val sidePanelScreen: @Composable (SidePanelDestination) -> Unit = { destination ->
            when (destination) {
                SidePanelDestination.Settings -> SettingsScreen(
                    ownPubkey = ownPubkey,
                    onBack = sidePanelNavigator::back,
                    onAccountChanged = {},
                    onAddAccountClick = {
                        composer.pendingKeyAction = null
                        composer.showKeySetup = true
                    },
                    onMuteListClick = { sidePanelNavigator.push(SidePanelDestination.MuteList) },
                    onNgWordClick = { sidePanelNavigator.push(SidePanelDestination.NgWords) },
                    onCustomEmojiClick = { sidePanelNavigator.push(SidePanelDestination.CustomEmoji()) },
                    onRelaySettingsClick = { sidePanelNavigator.push(SidePanelDestination.RelaySettings) },
                )
                SidePanelDestination.MuteList -> MuteListScreen(
                    onBack = sidePanelNavigator::back,
                    onUserClick = ::openProfileDrawer,
                )
                SidePanelDestination.NgWords -> NgWordScreen(onBack = sidePanelNavigator::back)
                SidePanelDestination.RelaySettings -> RelaySettingsScreen(
                    onBack = sidePanelNavigator::back,
                    onOpenProfile = ::openProfileDrawer,
                )
                is SidePanelDestination.CustomEmoji -> CustomEmojiSettingsScreen(
                    onBack = sidePanelNavigator::back,
                    onOpenRelaySettings = { sidePanelNavigator.push(SidePanelDestination.RelaySettings) },
                    onOpenProfile = ::openProfileDrawer,
                    initialQuery = destination.route.query,
                    initialImageUrl = destination.route.imageUrl,
                    initialSetAddress = EmojiSetAddress.parse(destination.route.setAddress),
                )
                is SidePanelDestination.Search -> SearchScreen(
                    initialQuery = destination.query,
                    onBack = sidePanelNavigator::back,
                    onUserClick = ::openProfileDrawer,
                    onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                    onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                    onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                    onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                )
            }
        }
        SidePanelLayout(
            panelWidth = sidePanelWidth,
            left = {
                CompositionLocalProvider(
                    LocalCustomEmojiNavigator provides openCustomEmoji,
                    LocalQuotePostHandler provides quotePostHandler,
                    LocalArticleNavigator provides articleNavigator,
                    LocalMatometrActions provides matometrActions,
                ) {
                    // 閉じるアニメーション中は、最後に表示していたプロフィールを出し続ける。
                    SlidingSidePanel(value = drawerCoordinator.profileDestination) { displayed ->
                        profilePanelContent(displayed, true)
                    }
                }
            },
            right = {
                CompositionLocalProvider(
                    LocalCustomEmojiNavigator provides openCustomEmoji,
                    LocalQuotePostHandler provides quotePostHandler,
                    LocalArticleNavigator provides articleNavigator,
                    LocalMatometrActions provides matometrActions,
                ) {
                    SidePanelHost(sidePanelNavigator) { destination ->
                        sidePanelScreen(destination)
                    }
                }
            },
        ) {
        CompositionLocalProvider(
            LocalCustomEmojiNavigator provides openCustomEmoji,
            LocalQuotePostHandler provides quotePostHandler,
            LocalArticleNavigator provides articleNavigator,
                    LocalMatometrActions provides matometrActions,
        ) {
        AppModalNavigationDrawer(
            drawerState = profileDrawerState,
            endDrawer = false,
            // 左パネル表示中はプロフィールドロワーを使わない。
            gesturesEnabled = !isSidePanelMode && (
                profileDrawerState.currentValue != DrawerValue.Closed ||
                    profileDrawerState.targetValue != DrawerValue.Closed
                ),
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ProfileDrawerWidthFraction)
                        .then(if (isWideLayout) Modifier.widthIn(max = ProfileDrawerWideMaxWidth) else Modifier),
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                    windowInsets = WindowInsets(0),
                ) {
                        if (!isSidePanelMode) {
                            profilePanelContent(
                                drawerCoordinator.profileDestination,
                                drawerCoordinator.isProfileContentReady,
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
            val isServiceRoute = currentRoute == "services" ||
                currentRoute == "channels" ||
                isChannelRoute ||
                isChannelThreadRoute ||
                currentRoute == "status"
            // チャンネル詳細は通知などサービス外から開くこともあるので、ルートからチャンネルを選択中とする。
            val activeServiceTab = if (isChannelRoute || isChannelThreadRoute) ServiceTab.Channels else currentServiceTab
            // ボトムバーとNavigationRailで同じ導線を、設定の並び順で共有する。
            val footerDestinations = footerConfig.visibleItems.map { item ->
                MainDestination(
                    icon = item.icon,
                    label = item.label,
                    selected = when (item) {
                        FooterItem.Home -> currentRoute == "feed"
                        FooterItem.Journal -> currentRoute == "journal"
                        else -> isServiceRoute && activeServiceTab == item.serviceTab
                    },
                    onClick = when (item) {
                        FooterItem.Home -> {
                            {
                                if (currentRoute == "feed") {
                                    feedChromeState.collapseFraction = 0f
                                    feedScrollToTopTargetTab = currentFeedTab
                                    feedScrollToTopRequest++
                                } else {
                                    selectFooterRoute("feed")
                                }
                            }
                        }
                        FooterItem.Journal -> {
                            {
                                runWithPrivateKey(PendingKeyAction.Journal) {
                                    if (currentRoute == "journal") {
                                        composer.journalToggleCalendarRequest++
                                    } else {
                                        selectFooterRoute("journal")
                                    }
                                }
                            }
                        }
                        else -> {
                            { selectServiceTab(checkNotNull(item.serviceTab)) }
                        }
                    },
                )
            }
            AppScaffold(
                navigationRail = if (isWideLayout && hasBottomBar) {
                    {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                            // 横向きのスマートフォンなど縦が足りないときはスクロールする。
                            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                                Column(
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .heightIn(min = maxHeight),
                                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    footerDestinations.forEach { destination ->
                                        NavigationRailItem(
                                            icon = {
                                                Icon(
                                                    destination.icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(if (destination.selected) 26.dp else 24.dp),
                                                )
                                            },
                                            label = { Text(destination.label) },
                                            selected = destination.selected,
                                            colors = appNavigationRailItemColors(),
                                            onClick = destination.onClick,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    null
                },
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
                            // フィードは投稿の起動・展開を同じFABで扱う。
                            // ジャーナルの閉じた状態のFABは、画面側のカレンダー切り替えに譲る。
                            "feed", "journal" -> when (composer.presentation) {
                                ComposerPresentation.Hidden -> if (currentRoute == "feed") Box(
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
                            "status", StatusPageRoute -> AppFloatingActionButton(
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
                    } else if (hasBottomBar && !isWideLayout) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(bottomBarHeight)
                                .graphicsLayer {
                                    translationY = bottomBarHeightPx * activeFeedChromeCollapseFraction()
                                }
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            AppBottomBarRow(
                                destinations = footerDestinations,
                                modifier = Modifier.graphicsLayer { alpha = 1f - activeFeedChromeCollapseFraction() },
                            )
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
                            onOpenSettings = { openSettings() },
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
                            onOpenSearch = ::openSearch,
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
                        // コンテンツの切り替えは、ホームやジャーナルへのタブ切り替えと同じフェードにする。
                        // 左右スワイプでは切り替えない（本文の横スクロールやフィードのスワイプと揃えて混同を避ける）。
                        val servicePages = footerConfig.serviceTabs
                        // 非表示にしたコンテンツや URL から開いたコンテンツは、表示中の最初のコンテンツに寄せる。
                        // 1つも表示していなければホームへ移る。
                        val resolvedServiceTab = resolveServiceTab(currentServiceTab, servicePages)
                        LaunchedEffect(resolvedServiceTab, currentServiceTab) {
                            when (resolvedServiceTab) {
                                null -> selectFooterRoute("feed")
                                currentServiceTab -> Unit
                                else -> currentServiceTab = resolvedServiceTab
                            }
                        }
                        // タブを離れても一覧のスクロール位置などを残す。
                        val serviceStateHolder = rememberSaveableStateHolder()
                        if (resolvedServiceTab != null) AnimatedContent(
                            targetState = resolvedServiceTab,
                            transitionSpec = { appScreenEnterTransition() togetherWith appScreenExitTransition() },
                            modifier = Modifier.fillMaxSize(),
                            label = "serviceTab",
                        ) { tab ->
                            // 読み込みや購読は、切り替え先のタブだけが行う。フェードで消えていくタブは止める。
                            val isActive = tab == resolvedServiceTab
                            serviceStateHolder.SaveableStateProvider(tab.name) {
                                when (tab) {
                                    ServiceTab.Emojis -> {
                                        CustomEmojiSettingsScreen(
                                            isActive = isActive,
                                            onOpenRelaySettings = ::requestRelaySettings,
                                            onOpenProfile = ::openProfileDrawer,
                                            serviceHeader = {
                                                EmojiServiceHeader(
                                                    ownPubkey = ownPubkey,
                                                    ownProfile = ownProfile,
                                                    onOpenProfile = ::requestOwnProfile,
                                                    onOpenSettings = { openSettings() },
                                                    onOpenRelaySettings = ::requestRelaySettings,
                                                )
                                            },
                                        )
                                    }
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
                                            onOpenSettings = { openSettings() },
                                            isActive = isActive,
                                        )
                                    }
                                    ServiceTab.Articles -> {
                                        ArticleHubScreen(
                                    onOpenDrafts = { nav.navigate("article-drafts") },
                                            ownPubkey = ownPubkey,
                                            ownProfile = ownProfile,
                                            onOpenProfile = {
                                                requestOwnProfile()
                                            },
                                            onOpenSettings = { openSettings() },
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
                                            isActive = isActive,
                                            onShareToFeed = articleShareHandler,
                                        )
                                    }
                                    ServiceTab.Status -> {
                                        StatusScreen(
                                            ownPubkey = ownPubkey,
                                            ownProfile = ownProfile,
                                            showComposer = composer.showStatusComposer,
                                            onComposerShown = { composer.showStatusComposer = false },
                                            onRequestComposer = {
                                                runWithPrivateKey(PendingKeyAction.Status) {
                                                    composer.showStatusComposer = true
                                                }
                                            },
                                            onUserClick = ::openProfileDrawer,
                                            onOpenProfile = {
                                                requestOwnProfile()
                                            },
                                            onOpenRelaySettings = {
                                                requestRelaySettings()
                                            },
                                            onOpenSettings = { openSettings() },
                                            isActive = isActive,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // フッターでステータスを隠していても、マイプロフィールから開ける単独の画面。
                    composable(StatusPageRoute) {
                        StatusScreen(
                            ownPubkey = ownPubkey,
                            ownProfile = ownProfile,
                            showComposer = composer.showStatusComposer,
                            onComposerShown = { composer.showStatusComposer = false },
                            onRequestComposer = {
                                runWithPrivateKey(PendingKeyAction.Status) {
                                    composer.showStatusComposer = true
                                }
                            },
                            onUserClick = ::openProfileDrawer,
                            onOpenRelaySettings = ::requestRelaySettings,
                            onOpenSettings = { openSettings() },
                            // URL（#status-page）から直接開いたときは戻り先がないので、ホームへ移る。
                            onBack = {
                                if (nav.previousBackStackEntry != null) nav.popBackStack() else selectFooterRoute("feed")
                            },
                        )
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
                            // フッターでステータスを隠しているときは、サービス画面に開くと別のタブに寄せられるので単独の画面で開く。
                            val destination = if (ServiceTab.Status in footerConfig.serviceTabs) {
                                currentServiceTab = ServiceTab.Status
                                "services"
                            } else {
                                StatusPageRoute
                            }
                            nav.navigate(destination) {
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
                    composable("article-drafts") {
                        ArticleDraftListScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(ArticleDraftRoute(it)) })
                    }
                    composable<ArticleDraftRoute> { entry ->
                        val route = entry.toRoute<ArticleDraftRoute>()
                        ArticleEditorScreen(draftId = route.draftId, onBack = { nav.popBackStack() },
                            onAddPosts = ::leaveEditorToPickPosts,
                            onNoteClick = { nav.navigate(ThreadRoute(it)) }, onUserClick = ::openProfileDrawer,
                            onPublished = { pubkey, identifier -> nav.navigate(ArticleRoute(pubkey, identifier)) {
                                popUpTo(ArticleDraftRoute(route.draftId)) { inclusive = true }
                            } })
                    }
                    composable("article-editor") {
                        ArticleEditorScreen(
                            onAddPosts = ::leaveEditorToPickPosts,
                            onNoteClick = { nav.navigate(ThreadRoute(it)) }, onUserClick = ::openProfileDrawer,
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
                            onAddPosts = ::leaveEditorToPickPosts,
                            onNoteClick = { nav.navigate(ThreadRoute(it)) }, onUserClick = ::openProfileDrawer,
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
                            onShareToFeed = articleShareHandler,
                        )
                    }
                    composable("journal") {
                        JournalScreen(
                            onBack = { nav.popBackStack() },
                            toggleCalendarRequest = composer.journalToggleCalendarRequest,
                            showCalendarToggleFab = composer.presentation == ComposerPresentation.Hidden,
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
                            onOpenAccountSwitcher = { showAccountSwitcher = true },
                            onOpenStatus = { nav.navigate(StatusPageRoute) { launchSingleTop = true } },
                            onOpenSettings = { openSettings() },
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
                            onRelaySettingsClick = ::requestRelaySettings,
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
                        RelaySettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenProfile = ::openProfileDrawer,
                        )
                    }
                    composable<CustomEmojiRoute> { backStack ->
                        val route = backStack.toRoute<CustomEmojiRoute>()
                        CustomEmojiSettingsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenRelaySettings = ::requestRelaySettings,
                            onOpenProfile = ::openProfileDrawer,
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
            onPostSheetOpened = ::offerPendingInputs,
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
                val completedRecoveryId = activeRecoveryInputId() ?: "current"
                if (completion.fromCurrentDraft) { recoveryInputId = null; recoveryOpening = null; scope.launch {
                    try {
                        val session = accountSession ?: return@launch
                        session.articleDraftStore.transaction(session.pubkey) { data ->
                            data.copy(composers = data.composers - completedRecoveryId, handoffs = data.handoffs - completedRecoveryId)
                        }
                    } catch (e: Exception) { matometrError = "公開は完了しましたが、復旧用入力の整理に失敗しました: ${e.message}" }
                } }
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

        if (showPendingInputs && pendingInputs.isNotEmpty()) PendingPostInputDialog(
            inputs = pendingInputs,
            busy = pendingInputBusy || accountSession?.inputFrozen?.value == true,
            error = pendingInputError,
            onApply = { resolvePendingInput(it, apply = true) },
            onDiscard = { resolvePendingInput(it, apply = false) },
            onDismiss = { showPendingInputs = false },
        )

        publishFailureDialogRelays?.let { failedRelays ->
            PublishFailureDialog(
                failedRelays = failedRelays,
                onDismiss = { publishFailureDialogRelays = null },
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
private fun appNavigationRailItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor = Color.Transparent,
)

private const val TopLevelExitWindowMillis = 2_000L
private const val StatusPageRoute = "status-page"
private const val ProfileDrawerWidthFraction = 0.92f
private val ProfileDrawerWideMaxWidth = 480.dp
