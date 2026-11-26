package com.nostr.torinos.ui.chat

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import com.nostr.torinos.ui.components.GuestAccountButton
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import com.nostr.torinos.ui.settings.getPlainText
import com.nostr.torinos.ui.settings.setPlainText
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.account.InteractiveSigner
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.account.SignerAttention
import com.nostr.torinos.util.logException
import kotlin.coroutines.cancellation.CancellationException
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupNameError
import com.nostr.torinos.chat.MAX_DM_OTHERS
import com.nostr.torinos.chat.NewGroupType
import com.nostr.torinos.chat.NewGroupVisibility
import com.nostr.torinos.chat.PendingCreation
import com.nostr.torinos.chat.dmDisplayName
import com.nostr.torinos.chat.groupNameError
import com.nostr.torinos.network.ProfileRepository
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.flow.flowOf
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.HeaderSelectorButton
import com.nostr.torinos.ui.components.HeaderDropdownMenuItem
import com.nostr.torinos.ui.components.HeaderDropdownMenu
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.service.ServiceHeaderDivider

/** チャットタブ（NIP-29）の最初の画面。参加中のグループの一覧と参加シート。 */
@Composable
fun ChatTabScreen(
    ownPubkey: String?,
    ownProfile: NostrProfile?,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogin: () -> Unit,
    onOpenGroup: (GroupRef, GroupLayout) -> Unit,
    onGroupJoined: () -> Unit = {},
    /** 通常の投稿などから開いたグループへのリンク。 */
    pendingLink: String? = null,
    onPendingLinkConsumed: () -> Unit = {},
    onOpenThread: (GroupRef, String) -> Unit = { _, _ -> },
    /** NIP-17 の会話を開く（会話の鍵）。 */
    onOpenDm: (String) -> Unit = {},
    /** ほかの人のプロフィールを開く（タイムラインの本文のメンション）。 */
    onUserClick: ((String) -> Unit)? = null,
    /** 選んでいる Buzz コミュニティの中を検索する（コミュニティのリレー、docs/buzz-search-design.md）。 */
    onOpenSearch: (String) -> Unit = {},
) {
    val session = LocalAccountSession.current
    val viewModel = accountSessionViewModel(key = "chat-tab-${session?.sessionId}") { s -> ChatTabViewModel(s?.chat, s?.dm, s?.muteStore?.mutedPubkeys) }
    val state by viewModel.state.collectAsState()
    var selectorOpen by remember { mutableStateOf(false) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var newDmOpen by remember { mutableStateOf(false) }
    // アーカイブ済みは既定で閉じる。グループを開いて戻っても開閉を保つ。
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }
    // 「グループを探す」（未参加のグループの一覧）は、押したときだけ開く。
    var othersExpanded by rememberSaveable { mutableStateOf(false) }
    // グループの一覧の位置。未読の見出しが先頭に入ったときに戻すのに使う。
    val groupsListState = rememberLazyListState()
    val hadUnread = remember { booleanArrayOf(false) }
    // DM の見出しとアイコンは参加者のプロフィールから作る。
    val dmPeople = remember(state.joinedRows) {
        state.joinedRows.filter { it.layout == GroupLayout.Direct }.flatMap { it.metadata?.participants.orEmpty() }.toSet()
    }
    // DM の見出しと、プレビューに付ける送信者の名前に使う。
    val rowPeople = remember(dmPeople, state.joinedRows, state.archivedRows) {
        dmPeople + (state.joinedRows + state.archivedRows).flatMap { listOfNotNull(it.lastAuthor, it.threadRootAuthor) }
    }
    val dmProfiles by remember(rowPeople) {
        if (rowPeople.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(rowPeople)
    }.collectAsState(emptyMap())
    val openCreated: (GroupRef, GroupLayout) -> Unit = { ref, layout -> onGroupJoined(); onOpenGroup(ref, layout) }
    // チャット画面から戻ったときなど、一覧に入るたびに最新の発言を読み直す。
    LaunchedEffect(Unit) {
        viewModel.refreshJoined()
        viewModel.refreshDm()
        viewModel.syncBuzzCommunities()
    }
    // バックグラウンドから戻ったときも読み直す。タイムラインは購読せず読み切りなので、読み直さないと
    // バックグラウンド中の投稿が出ず、通信できずに読めなかったリレーもそのままになる。
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var wasStarted = true
        lifecycle.currentStateFlow.collect { lifecycleState ->
            val started = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (started && !wasStarted) viewModel.refreshJoined()
            wasStarted = started
        }
    }
    // DM アイコンでもう一度押したときに戻る選択。
    var beforeDirect by remember { mutableStateOf<ChatSelection>(ChatSelection.All) }
    var buzzLinkChoice by remember { mutableStateOf<Pair<String, List<ChatRelaySummary>>?>(null) }
    var linkMessage by remember { mutableStateOf<String?>(null) }
    // 切り替えメニューで長押ししたリレー。非表示にしてよいかを聞く。
    var hideRelayCandidate by remember { mutableStateOf<ChatRelaySummary?>(null) }
    hideRelayCandidate?.let { relay ->
        HideChatRelayDialog(
            relay = relay,
            onConfirm = { hideRelayCandidate = null; viewModel.hideRelay(relay.relayUrl) },
            onDismiss = { hideRelayCandidate = null },
        )
    }
    fun openBuzzLink(link: String, relayUrl: String) {
        when (val parsed = com.nostr.torinos.chat.parseBuzzLink(link)) {
            is com.nostr.torinos.chat.BuzzLink.Channel -> onOpenGroup(GroupRef(relayUrl, parsed.channelId), GroupLayout.Chat)
            is com.nostr.torinos.chat.BuzzLink.Message ->
                onOpenThread(GroupRef(relayUrl, parsed.channelId), parsed.threadRootId ?: parsed.messageId)
            null -> Unit
        }
    }
    val linkScope = rememberCoroutineScope()
    // 「グループ / タイムライン」は横スワイプでも切り替える。ページは [chatViewPages] の順。
    val viewPager = rememberPagerState(initialPage = chatViewPages.indexOf(state.view).coerceAtLeast(0)) { chatViewPages.size }
    // 保存していた表示の読み込みなど、外から表示が変わったらページを合わせる（スワイプ中は動かさない）。
    LaunchedEffect(viewPager, state.view) {
        val page = chatViewPages.indexOf(state.view)
        if (page >= 0 && !viewPager.isScrollInProgress && viewPager.settledPage != page) viewPager.scrollToPage(page)
    }
    // ページが止まったら表示を切り替える（タイムラインの読み込みは止まってから）。
    LaunchedEffect(viewPager) {
        snapshotFlow { viewPager.settledPage }.collect { page ->
            val view = chatViewPages.getOrNull(page) ?: return@collect
            if (viewModel.state.value.showsViewToggle && viewModel.state.value.view != view) viewModel.setView(view)
        }
    }
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        // 受け取り済みにするとこの effect は取り消されるので、続きは画面のスコープで行う。
        onPendingLinkConsumed()
        if (link.startsWith("buzz://", ignoreCase = true)) {
            linkScope.launch {
                val communities = viewModel.buzzCommunitiesForLink()
                when (communities.size) {
                    0 -> linkMessage = "Buzz コミュニティに参加していません。Buzz のリンクは、参加中のコミュニティで開きます"
                    1 -> openBuzzLink(link, communities.single().relayUrl)
                    else -> buzzLinkChoice = link to communities
                }
            }
        } else {
            viewModel.openJoinSheet(prefill = link)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                AppTopBar(
                    navigationIcon = {
                        if (ownPubkey != null) {
                            IconButton(onClick = onOpenProfile) {
                                ChatAvatar(pubkey = ownPubkey, name = ownProfile?.bestName, pictureUrl = ownProfile?.picture, size = 32)
                            }
                        } else {
                            GuestAccountButton(onLogin = onLogin, onOpenSettings = onOpenSettings)
                        }
                    },
                    title = {
                        Box {
                            val selectedRelay = (state.selection as? ChatSelection.Relay)?.let { selection ->
                                state.relays.firstOrNull { it.relayUrl == selection.relayUrl }
                            }
                            // ホームのフィードの切り替えと同じ形: 名前と補足の2行、全体を押して開く。
                            HeaderSelectorButton(onClickLabel = "グループの切り替え", onClick = { selectorOpen = true }) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    val label = when (val selection = state.selection) {
                                        ChatSelection.All -> "すべて"
                                        ChatSelection.Direct -> "DM"
                                        is ChatSelection.Relay -> selectedRelay?.label ?: selection.relayUrl.substringAfter("://")
                                    }
                                    Text(
                                        label,
                                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.25f,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val subtitle = when {
                                        state.selection == ChatSelection.All && state.showsDmList -> "DM ${state.dm.lists.conversations.size}"
                                        state.selection == ChatSelection.All -> "${state.relays.size}リレー"
                                        selectedRelay != null && selectedRelay.label != selectedRelay.host -> selectedRelay.host
                                        else -> null
                                    }
                                    if (subtitle != null) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            }
                            HeaderDropdownMenu(expanded = selectorOpen, onDismissRequest = { selectorOpen = false }) {
                                HeaderDropdownMenuItem(
                                    label = "すべて",
                                    selected = state.selection == ChatSelection.All,
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Groups,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    },
                                    onClick = { selectorOpen = false; viewModel.select(ChatSelection.All) },
                                )
                                state.relays.forEach { relay ->
                                    HeaderDropdownMenuItem(
                                        label = relay.label,
                                        supportingText = relay.host.takeIf { it != relay.label },
                                        leadingIcon = { ChatRelayIcon(relay.label, relay.flavor, size = 28.dp, iconUrl = relay.iconUrl) },
                                        badge = if (relay.flavor == com.nostr.torinos.chat.GroupFlavor.Buzz) "Buzz" else null,
                                        selected = (state.selection as? ChatSelection.Relay)?.relayUrl == relay.relayUrl,
                                        onClick = { selectorOpen = false; viewModel.select(ChatSelection.Relay(relay.relayUrl)) },
                                        onLongClick = if (state.hasSigner) {
                                            { selectorOpen = false; hideRelayCandidate = relay }
                                        } else null,
                                        onLongClickLabel = "このリレーを非表示",
                                    )
                                }
                                // 別の端末で参加した Buzz コミュニティを確かめている間（開けない）。
                                state.restoringRelays.forEach { relay ->
                                    HeaderDropdownMenuItem(
                                        label = relay.label,
                                        supportingText = relay.host.takeIf { it != relay.label },
                                        leadingIcon = { ChatRelayIcon(relay.label, relay.flavor, size = 28.dp, iconUrl = relay.iconUrl) },
                                        badge = "確認中",
                                        onClick = { selectorOpen = false },
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                HeaderDropdownMenuItem(
                                    label = "グループに参加…",
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = { selectorOpen = false; viewModel.openJoinSheet() },
                                )
                                // 選んでいる Buzz コミュニティの owner / admin（docs/buzz-admin-design.md 3.5）。
                                if (state.canManageSelectedCommunity) {
                                    HeaderDropdownMenuItem(
                                        label = "コミュニティの管理…",
                                        leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = { selectorOpen = false; viewModel.openCommunityAdmin() },
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        val searchRelay = (state.selection as? ChatSelection.Relay)?.relayUrl
                        if (state.selectsBuzzCommunity && state.hasSigner && searchRelay != null) {
                            IconButton(onClick = { onOpenSearch(searchRelay) }) {
                                Icon(Icons.Default.Search, contentDescription = "コミュニティ内を検索")
                            }
                        }
                        if (state.showsDmButton) {
                            val selected = state.selection == ChatSelection.Direct
                            IconButton(onClick = {
                                if (selected) {
                                    viewModel.select(beforeDirect)
                                } else {
                                    beforeDirect = state.selection
                                    viewModel.select(ChatSelection.Direct)
                                }
                            }) {
                                Box {
                                    Icon(
                                        Icons.Default.Mail,
                                        contentDescription = if (selected) "DM を閉じる" else "DM",
                                        tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    )
                                    if (state.dm.lists.unreadConversationCount > 0) {
                                        Box(
                                            Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                ServiceHeaderDivider()
                // タイムラインも、参加中の一覧を読み直している間と自分の読み込みの間は、グループと同じ線を出す。
                // 前の結果を出したまま差し替え、行が無いうちは一覧の側でスケルトンを出す。
                val loading = state.isLoading || (state.showsTimeline && state.timeline.isLoading)
                // 読み込み中の線はタブの上（ヘッダー側）に出す。タブの下に出すと選択中の下線と紛れる。
                if (state.showsViewToggle) {
                    // 線の分の高さは常に取っておき、出し入れでタブが上下に動かないようにする。
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxSize())
                    }
                    // 下線はスワイプの行き先に合わせて先に動かす。
                    ChatViewToggle(chatViewPages.getOrElse(viewPager.targetPage) { state.view }) { view ->
                        val page = chatViewPages.indexOf(view)
                        if (page >= 0) linkScope.launch { viewPager.animateScrollToPage(page) }
                    }
                } else if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
        floatingActionButton = {
            Box {
                // 暗号化 DM は DM の一覧の＋から直接（§8.4.5）。グループ側の＋は Buzz の DM・チャンネル・参加。
                AppFloatingActionButton(
                    onClick = {
                        when (state.fabAction) {
                            ChatFabAction.NewEncryptedDm -> newDmOpen = true
                            ChatFabAction.Menu -> createMenuOpen = true
                            ChatFabAction.Join -> viewModel.openJoinSheet()
                        }
                    },
                    icon = Icons.Default.Add,
                    contentDescription = when (state.fabAction) {
                        ChatFabAction.NewEncryptedDm -> "新しい DM"
                        ChatFabAction.Menu -> "新規作成・参加"
                        ChatFabAction.Join -> "グループに参加"
                    },
                )
                HeaderDropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                    if (state.canCreateDm) {
                        HeaderDropdownMenuItem(
                            label = "新しい DM",
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { createMenuOpen = false; viewModel.openCreateDm() },
                        )
                    }
                    if (state.canCreateChannel) {
                        HeaderDropdownMenuItem(
                            label = "新しいチャンネル",
                            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { createMenuOpen = false; viewModel.openCreateChannel(NewGroupType.Chat) },
                        )
                    }
                    if (state.canCreateForum) {
                        HeaderDropdownMenuItem(
                            label = "新しいフォーラム",
                            leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { createMenuOpen = false; viewModel.openCreateChannel(NewGroupType.Forum) },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    HeaderDropdownMenuItem(
                        label = "グループに参加…",
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { createMenuOpen = false; viewModel.openJoinSheet() },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val empty = state.joinedRows.isEmpty() && state.otherRows.isEmpty() && state.archivedRows.isEmpty() &&
                state.pendingCreations.isEmpty()
            // グループの一覧（参加中・探す・アーカイブ）。
            val groupsContent: @Composable BoxScope.() -> Unit = {
                when {
                    !state.hasLoaded && empty -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    // 前回グループがあったのに一覧（kind 10009）をまだ読めていない。読み直しているあいだは待つ。
                    // 前回の一覧が無く、読み込み中（参加中の一覧を読み直している、Buzz の最新の発言を読んでいる）。
                    // 行の形だけ先に出して待つ。
                    empty && state.selection == ChatSelection.All && (state.groupsExpected || (state.isLoading && state.hasGroups)) ->
                        Column(modifier = Modifier.fillMaxSize()) { GroupRowPlaceholders() }
                    empty && state.selection == ChatSelection.All -> ChatEmptyState(
                        onJoin = { viewModel.openJoinSheet() },
                        signedOut = !state.hasSigner,
                    )
                    else -> {
                    // 未読のまとまりが新しく先頭に入ったとき、一覧は前の先頭の行に留まるので、未読の行が画面の上に隠れる。
                    // 先頭を見ていたなら、入った未読の行が見えるように先頭へ戻す。
                    val hasUnread = state.joinedRows.any { it.unread }
                    if (hasUnread && !hadUnread[0] && groupsListState.firstVisibleItemIndex <= state.pendingCreations.size) {
                        groupsListState.requestScrollToItem(0)
                    }
                    hadUnread[0] = hasUnread
                    LazyColumn(state = groupsListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 88.dp)) {
                        items(state.pendingCreations, key = { "pc-" + it.ref.key }) { pending ->
                            PendingCreationBanner(
                                pending = pending,
                                busy = state.resumingCreation == pending.ref,
                                enabled = state.resumingCreation == null,
                                onResume = { viewModel.resumeCreation(pending, openCreated) },
                                onDiscard = { viewModel.discardCreation(pending) },
                            )
                        }
                        // 未読のグループは先頭にまとめ、下のまとまりからは外す。見出しは付けず、カードの色で未読と分かるようにする。
                        val (unreadRows, readRows) = state.joinedRows.partition { it.unread }
                        if (unreadRows.isNotEmpty()) {
                            items(unreadRows, key = { "j-" + it.ref.key }) { row ->
                                GroupRowItem(row, resolved = !state.isLoading, ownPubkey = ownPubkey, profiles = dmProfiles, onClick = { viewModel.markRead(row.ref); onOpenGroup(row.ref, row.layout) })
                            }
                        }
                        if (state.selection is ChatSelection.Relay) {
                            if (state.joinedRows.isEmpty()) {
                                if (state.isLoading) item(key = "joined-loading") { GroupRowPlaceholders(count = 3) }
                                else item(key = "joined-empty") { EmptyLine("参加中のグループはありません") }
                            }
                        }
                        if (state.selection == ChatSelection.All) {
                            // リレー・コミュニティごとにまとめる（行ごとにホスト名を出すと、グループ名が削られて読めない）。
                            val relayLabels = state.relays.associateBy { it.relayUrl }
                            groupRowsByRelay(readRows).forEach { section ->
                                val relay = relayLabels[section.relayUrl]
                                item(key = "s-" + section.relayUrl) {
                                    SectionHeader(
                                        (relay?.label ?: section.relayUrl.substringAfter("://")) +
                                            if (relay?.flavor == com.nostr.torinos.chat.GroupFlavor.Buzz) "  [Buzz]" else "",
                                    )
                                }
                                items(section.rows, key = { "j-" + it.ref.key }) { row ->
                                    GroupRowItem(row, resolved = !state.isLoading, ownPubkey = ownPubkey, profiles = dmProfiles, onClick = { viewModel.markRead(row.ref); onOpenGroup(row.ref, row.layout) })
                                }
                            }
                        } else {
                            val (directRows, groupRows) = readRows.partition { it.layout == GroupLayout.Direct }
                            items(groupRows, key = { "j-" + it.ref.key }) { row ->
                                GroupRowItem(row, resolved = !state.isLoading, ownPubkey = ownPubkey, profiles = dmProfiles, onClick = { viewModel.markRead(row.ref); onOpenGroup(row.ref, row.layout) })
                            }
                            if (directRows.isNotEmpty()) {
                                item(key = "dm-header") { SectionHeader("DM") }
                                items(directRows, key = { "j-" + it.ref.key }) { row ->
                                    GroupRowItem(row, resolved = !state.isLoading, ownPubkey = ownPubkey, profiles = dmProfiles, onClick = { viewModel.markRead(row.ref); onOpenGroup(row.ref, row.layout) })
                                }
                            }
                        }
                        if (state.selection is ChatSelection.Relay) {
                            item(key = "others-header") {
                                FindGroupsRow(
                                    count = state.otherRows.size.takeIf { !state.isLoading && !state.browseAuthNeeded },
                                    expanded = othersExpanded,
                                    onToggle = { othersExpanded = !othersExpanded },
                                )
                            }
                            if (othersExpanded && state.browseAuthNeeded) {
                                item(key = "others-auth") {
                                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(
                                            "このリレーはログインが必要です。公開鍵を伝えてログインすると、グループの一覧を読めます",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        TextButton(onClick = viewModel::allowBrowseAuth) { Text("ログインして読む") }
                                    }
                                }
                            } else if (othersExpanded && state.otherRows.isEmpty() && !state.isLoading) {
                                item(key = "others-empty") { EmptyLine("公開されているグループはありません") }
                            }
                            if (othersExpanded) {
                                items(state.otherRows, key = { "o-" + it.ref.key }) { row ->
                                    GroupRowItem(row, resolved = true, onClick = { viewModel.markRead(row.ref); onOpenGroup(row.ref, row.layout) })
                                }
                            }
                        }
                        if (state.archivedRows.isNotEmpty()) {
                            item(key = "archived-header") {
                                ArchivedSectionHeader(
                                    count = state.archivedRows.size,
                                    expanded = archivedExpanded,
                                    onToggle = { archivedExpanded = !archivedExpanded },
                                )
                            }
                            if (archivedExpanded) {
                                items(state.archivedRows, key = { "a-" + it.ref.key }) { row ->
                                    GroupRowItem(
                                        row,
                                        resolved = true,
                                        ownPubkey = ownPubkey,
                                        profiles = dmProfiles,
                                        onClick = { viewModel.markRead(row.ref); onOpenGroup(row.ref, row.layout) },
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
            val timelineContent: @Composable () -> Unit = {
                // 本文の `buzz://channel/…` をチャンネル名で出すため、一覧で分かっているチャンネルの名前を引けるようにする。
                val channelNames = remember(state.joinedRows, state.otherRows, state.archivedRows, state.timeline.rows) {
                    (state.joinedRows + state.otherRows + state.archivedRows + state.timeline.rows.map { it.group })
                        .mapNotNull { row -> row.metadata?.name?.takeIf { it.isNotBlank() }?.let { row.ref to it } }
                        .toMap()
                }
                ChatTimelineContent(
                    state = state.timeline,
                    // 参加中の一覧を読み直している間は、そのあとタイムラインも読み直す。
                    loading = state.isLoading || state.timeline.isLoading,
                    ownPubkey = ownPubkey,
                    onOpen = { row ->
                        if (row.entry.opensThread) onOpenThread(row.entry.ref, row.entry.conversation.rootId)
                        else onOpenGroup(row.entry.ref, row.group.layout)
                    },
                    onLoadOlder = viewModel::loadOlderTimeline,
                    onHideBotsChange = if (state.selectsBuzzCommunity) viewModel::setHideBots else null,
                    actions = TimelineBodyActions(
                        imageAuth = viewModel::timelineImageAuth,
                        imagesUnlocked = state.timeline.imagesUnlocked,
                        onUnlockImages = viewModel::unlockTimelineImages,
                        onBuzzLink = { link, relayUrl -> openBuzzLink(link, relayUrl) },
                        onUserClick = onUserClick,
                        channelName = { relayUrl, channelId ->
                            channelNames[com.nostr.torinos.chat.GroupRef(relayUrl, channelId)]
                        },
                    ),
                )
            }
            when {
                state.showsDmList -> DmListContent(
                    state = state.dm,
                    ownPubkey = ownPubkey,
                    onOpen = { conversation -> viewModel.markDmRead(conversation.key); onOpenDm(conversation.key) },
                    onJoinGroup = { viewModel.openJoinSheet() },
                    showJoinHint = !state.hasGroups,
                )
                // 「グループ / タイムライン」のタブがあるときは横スワイプでも切り替える。
                state.showsViewToggle -> HorizontalPager(
                    state = viewPager,
                    modifier = Modifier.fillMaxSize(),
                    key = { chatViewPages[it] },
                ) { page ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (chatViewPages[page]) {
                            ChatListView.Groups -> groupsContent()
                            ChatListView.Timeline -> timelineContent()
                        }
                    }
                }
                else -> groupsContent()
            }
        }
    }

    if (newDmOpen) {
        val follows by (session?.followRepository?.followedPubkeys ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) })
            .collectAsState()
        NewDmSheet(
            ownPubkey = ownPubkey,
            follows = follows,
            onDismiss = { newDmOpen = false },
            onPick = { pubkey ->
                newDmOpen = false
                onOpenDm(com.nostr.torinos.dm.dmConversationKey(setOf(pubkey)))
            },
        )
    }

    buzzLinkChoice?.let { (link, communities) ->
        AlertDialog(
            onDismissRequest = { buzzLinkChoice = null },
            title = { Text("どのコミュニティで開きますか") },
            text = {
                Column {
                    communities.forEach { community ->
                        TextButton(onClick = { buzzLinkChoice = null; openBuzzLink(link, community.relayUrl) }) {
                            Text(community.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { buzzLinkChoice = null }) { Text("キャンセル") } },
        )
    }
    linkMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { linkMessage = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { linkMessage = null }) { Text("OK") } },
        )
    }

    state.creationMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCreationMessage,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissCreationMessage) { Text("OK") } },
        )
    }

    when (val create = state.create) {
        CreateSheetState.Closed -> Unit
        is CreateSheetState.Channel -> CreateChannelSheet(
            state = create,
            onDismiss = viewModel::closeCreateSheet,
            onChange = viewModel::updateCreateChannel,
            onTarget = viewModel::setCreateTarget,
            onSubmit = { viewModel.submitCreateChannel(openCreated) },
        )
        is CreateSheetState.Dm -> CreateDmSheet(
            state = create,
            onDismiss = viewModel::closeCreateSheet,
            onTarget = viewModel::setCreateTarget,
            onQueryChange = viewModel::onDmQueryChange,
            onToggle = viewModel::toggleDmRecipient,
            onSubmit = { viewModel.submitDm { ref -> openCreated(ref, GroupLayout.Direct) } },
        )
    }

    state.communityAdmin?.let { sheet ->
        CommunityAdminSheetView(
            sheet = sheet,
            ownPubkey = ownPubkey,
            onDismiss = viewModel::closeCommunityAdmin,
            onTab = viewModel::setCommunityAdminTab,
            onAction = viewModel::communityAction,
            onDismissError = viewModel::dismissCommunityAdminError,
            onTtl = viewModel::setInviteTtl,
            onUses = viewModel::setInviteMaxUses,
            onMint = viewModel::mintInvite,
        )
    }

    state.removedCommunities.firstOrNull()?.let { removed ->
        val name = removed.name?.takeIf { it.isNotBlank() } ?: removed.relayUrl.substringAfter("://")
        AlertDialog(
            onDismissRequest = { viewModel.dismissRemovedCommunity(removed.relayUrl) },
            title = { Text("「$name」から外されました") },
            text = {
                Text("このコミュニティのメンバーではなくなったため、一覧から外しました。招待リンクをもらえば、また参加できます。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRemovedCommunity(removed.relayUrl) }) { Text("OK") }
            },
        )
    }

    if (state.join != JoinSheetState.Closed) {
        JoinGroupSheet(
            state = state.join,
            hasSigner = state.hasSigner,
            onDismiss = viewModel::closeJoinSheet,
            onInputChange = viewModel::onJoinInputChange,
            onSubmit = viewModel::submitJoinInput,
            onBack = { viewModel.openJoinSheet() },
            onPreview = { ref -> viewModel.closeJoinSheet(); onOpenGroup(ref, viewModel.layoutOf(ref)) },
            onJoin = { viewModel.confirmJoin(onJoined = { ref -> onGroupJoined(); onOpenGroup(ref, viewModel.layoutOf(ref)) }) },
            onAuthenticate = viewModel::authenticateAndReload,
            onBuzzConsent = { agreed, age -> viewModel.updateBuzzConsent(agreed, age) },
            onBuzzJoin = { viewModel.confirmBuzz(onJoined = { onGroupJoined() }) },
            publicGroups = state.publicGroups,
            publicGroupsLoading = state.publicGroupsLoading,
            joinedRefs = state.joinedRefs,
            onPickPublicGroup = viewModel::pickPublicGroup,
            accountName = ownPubkey?.let { pubkey ->
                ownProfile?.bestName?.takeIf { it.isNotBlank() } ?: runCatching { com.nostr.torinos.crypto.hexToNpub(pubkey) }.getOrDefault(pubkey).let { it.take(12) + "…" + it.takeLast(6) }
            },
        )
    }
}

/** 「アーカイブ済み（n）」の見出し。タップで開閉する（既定は閉じる）。 */
@Composable
private fun ArchivedSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "アーカイブ済みを閉じる" else "アーカイブ済みを開く", onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "アーカイブ済み（$count）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 未参加のグループの一覧を開く1行（Slack の「チャンネルを追加する」と同じ位置づけ）。 */
@Composable
private fun FindGroupsRow(count: Int?, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(onClickLabel = if (expanded) "閉じる" else "開く", onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = "グループを探す" + (count?.takeIf { it > 0 }?.let { "（$it）" } ?: ""),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChatEmptyState(onJoin: () -> Unit, signedOut: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("グループに参加していません", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "招待リンクやグループのアドレスを貼ると、グループ（NIP-29・Buzz）に参加できます",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (signedOut) {
            Spacer(Modifier.height(8.dp))
            Text(
                "ログインすると、DM もここで使えます",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onJoin) { Text("招待リンクで参加") }
    }
}

/** 読み込み中の仮の行。[GroupRowItem] と同じ大きさで、ゆっくり明滅させる。 */
@Composable
private fun GroupRowPlaceholders(count: Int = 6) {
    val transition = rememberInfiniteTransition(label = "group-row-placeholder")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), repeatMode = RepeatMode.Reverse),
        label = "group-row-placeholder-alpha",
    )
    val color = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = Modifier.fillMaxWidth().alpha(alpha)) {
        repeat(count) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // 見出しと本文の行の高さに合わせる。幅は行ごとに少し変えて、表らしさを消す。
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.45f else 0.6f).height(14.dp).padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(if (index % 3 == 0) 0.85f else 0.7f).height(12.dp).padding(vertical = 1.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
            }
        }
    }
}

@Composable
internal fun GroupIcon(
    picture: String?,
    locked: Boolean,
    size: Int = 40,
    channel: Boolean = true,
    forum: Boolean = false,
    shape: Shape = CircleShape,
    background: Color? = null,
) {
    Box(
        modifier = Modifier.size(size.dp).clip(shape).background(background ?: MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // Buzz と同じく、画像の無いチャンネルは「#」、非公開は鍵。フォーラムは吹き出し、複数人の DM などはチャンネルではないので人のマーク。
        when {
            locked -> Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size((size / 2).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            channel && forum -> Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size((size / 2).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // 文字の「#」は行の高さとフォントの余白で下にずれるので、ほかと同じくアイコンで描く。
            channel -> Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size((size / 2).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size((size / 2).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!picture.isNullOrBlank()) {
            NetworkImage(url = picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, maxDecodeSizePx = 160)
        }
    }
}

@Composable
private fun GroupRowItem(
    row: ChatGroupRow,
    resolved: Boolean,
    ownPubkey: String? = null,
    profiles: Map<String, NostrProfile> = emptyMap(),
    onClick: () -> Unit,
) {
    val participants = row.metadata?.participants.orEmpty()
    val direct = row.layout == GroupLayout.Direct
    val title = if (direct) dmDisplayName(participants, ownPubkey.orEmpty()) { profiles[it]?.bestName } else row.title
    // 読み込みが終わってもグループ情報も発言も無い行は、控えめに出す。
    val unresolved = resolved && row.isUnresolved
    // 行はカードにする。未読のカードは背景を薄い青にして、見出しが無くても未読と分かるようにする。
    val cardColor = if (row.unread) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f).compositeOver(MaterialTheme.colorScheme.surface)
    }
    val textColor = if (row.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val peer = participants.firstOrNull { it != ownPubkey } ?: participants.firstOrNull()
        // 種類のアイコンは小さくし、見出しの行にそろえる。
        Box(modifier = Modifier.padding(top = 1.dp)) {
            if (direct && peer != null && participants.count { it != ownPubkey } <= 1) {
                ChatAvatar(pubkey = peer, name = profiles[peer]?.bestName, pictureUrl = profiles[peer]?.picture, size = GROUP_ROW_ICON_SIZE)
            } else {
                GroupIcon(
                    row.metadata?.picture,
                    locked = !direct && row.metadata?.isPrivate == true,
                    size = GROUP_ROW_ICON_SIZE,
                    channel = !direct,
                    forum = row.layout == GroupLayout.Forum,
                    shape = RoundedCornerShape(7.dp),
                    background = if (row.unread) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else null,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (row.unread) FontWeight.Bold else FontWeight.SemiBold,
                color = if (unresolved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 最新の発言がスレッドの返信なら、元の投稿を上に出し、返信は「╰」を付けてその下に出す（Google Chat と同じ形）。
            // 送信者の名前は出さず、小さいアイコンで示す。返信は2行まで出す。
            val rootPreview = row.threadRootPreview.takeIf { !row.pending }
            if (rootPreview != null) {
                PreviewLine(rootPreview, author = row.threadRootAuthor, profiles = profiles, color = textColor, maxLines = 1)
                Spacer(Modifier.height(4.dp))
            }
            val preview = row.preview.takeIf { !row.pending }
            if (preview != null) {
                PreviewLine(
                    preview,
                    author = row.lastAuthor,
                    profiles = profiles,
                    color = textColor,
                    maxLines = if (rootPreview != null) 2 else 1,
                    reply = rootPreview != null,
                )
            } else {
                val subtitle = when {
                    row.pending -> "承認待ち"
                    row.metadata?.about?.isNotBlank() == true -> row.metadata.about
                    unresolved -> "グループ情報を読めません"
                    !resolved && row.metadata == null -> "読み込み中…"
                    else -> "まだ発言がありません"
                }
                Text(
                    text = subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 時刻と未読の件数は右上に固定する。
        if (row.lastMessage != null || row.unread) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                row.lastMessage?.let {
                    Text(
                        com.nostr.torinos.chat.chatListTime(it.createdAt, kotlin.time.Clock.System.now().epochSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (row.unread) {
                    Spacer(Modifier.height(6.dp))
                    UnreadBadge(row.unreadCount)
                }
            }
        }
    }
}

private const val GROUP_ROW_ICON_SIZE = 24
private const val PREVIEW_AVATAR_SIZE = 16

/** 一覧のプレビューの1行。先頭に送信者の小さいアイコンを付け、URL はドメイン名だけにする。 */
@Composable
private fun PreviewLine(
    text: String,
    author: String?,
    profiles: Map<String, NostrProfile>,
    color: Color,
    maxLines: Int,
    reply: Boolean = false,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        val short = com.nostr.torinos.chat.shortenPreviewLinks(text)
        buildAnnotatedString {
            append(short.text)
            short.links.forEach { addStyle(SpanStyle(color = linkColor), it.first, it.last + 1) }
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        if (reply) {
            Text("╰", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        if (author != null) {
            ChatAvatar(
                pubkey = author,
                name = profiles[author]?.bestName,
                pictureUrl = profiles[author]?.picture,
                size = PREVIEW_AVATAR_SIZE,
                modifier = Modifier.padding(top = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 未読の件数のバッジ。件数が分からなければ点だけ。 */
@Composable
internal fun UnreadBadge(count: Int?) {
    if (count == null || count <= 0) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        return
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            com.nostr.torinos.chat.unreadBadgeText(count),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoinGroupSheet(
    state: JoinSheetState,
    hasSigner: Boolean,
    onDismiss: () -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onPreview: (GroupRef) -> Unit,
    onJoin: () -> Unit,
    onAuthenticate: () -> Unit,
    onBuzzConsent: (Boolean?, Boolean?) -> Unit,
    onBuzzJoin: () -> Unit,
    publicGroups: List<com.nostr.torinos.chat.PublicGroupEntry> = emptyList(),
    publicGroupsLoading: Boolean = false,
    joinedRefs: Set<GroupRef> = emptySet(),
    onPickPublicGroup: (GroupRef) -> Unit = {},
    /** 参加に使うアカウントの表示名（Buzz の招待で見せる）。 */
    accountName: String? = null,
) {
    // シートの暗幕で画面上部の解錠の帯が押せないため、参加ボタンの中で解錠・再接続を済ませる。
    val gate = rememberJoinSignerGate()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            when (state) {
                JoinSheetState.Closed -> Unit
                is JoinSheetState.Buzz -> BuzzJoinContent(state, hasSigner, accountName, gate, onBack, onBuzzConsent, { gate.proceed(onBuzzJoin) })
                is JoinSheetState.Input -> {
                    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
                    val pasteScope = rememberCoroutineScope()
                    // 入力のたびに、何として読めたか(種類と行き先)を見せる。
                    val recognized = remember(state.text) {
                        state.text.takeIf { it.isNotBlank() }?.let { com.nostr.torinos.chat.describeGroupInput(com.nostr.torinos.chat.parseGroupInput(it)) }
                    }
                    val paste: () -> Unit = {
                        pasteScope.launch {
                            val pasted = clipboard.getPlainText()?.trim().orEmpty()
                            if (pasted.isNotEmpty()) {
                                onInputChange(pasted)
                                onSubmit()
                            }
                        }
                    }
                    JoinSheetHeader()
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("グループ名・招待リンク・アドレス", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        isError = state.error != null,
                        supportingText = when {
                            state.error != null -> { { Text(state.error) } }
                            recognized != null -> { { Text("✓ $recognized", color = MaterialTheme.colorScheme.primary) } }
                            else -> null
                        },
                        singleLine = true,
                        // 押したときだけクリップボードを読み、そのまま確認まで進める。
                        trailingIcon = {
                            when {
                                state.isResolving -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                state.text.isNotEmpty() -> IconButton(onClick = { onInputChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "消す")
                                }
                                else -> IconButton(onClick = paste) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "貼り付け")
                                }
                            }
                        },
                    )
                    if (recognized != null || state.isResolving) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onSubmit,
                            enabled = state.text.isNotBlank() && !state.isResolving,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            if (state.isResolving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("次へ")
                        }
                    }
                    // リンクやアドレスとして読めない入力は、公開グループの検索語にする。
                    if (recognized == null) {
                        if (state.text.isBlank()) {
                            Spacer(Modifier.height(8.dp))
                            JoinInputKinds(enabled = !state.isResolving, onPaste = paste)
                        }
                        PublicGroupResults(
                            results = remember(publicGroups, state.text, joinedRefs) {
                                com.nostr.torinos.chat.searchPublicGroups(publicGroups, state.text, joinedRefs)
                            },
                            loading = publicGroupsLoading,
                            query = state.text,
                            enabled = !state.isResolving,
                            onPick = onPickPublicGroup,
                            onPaste = paste,
                        )
                    }
                }
                is JoinSheetState.Confirm -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GroupIcon(state.metadata?.picture, locked = state.metadata?.isPrivate == true, size = 48, forum = state.metadata?.layout == GroupLayout.Forum)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.metadata?.displayName ?: state.ref.groupId, style = MaterialTheme.typography.titleMedium)
                            Text(
                                state.relayName?.let { "$it (${state.ref.relayUrl.substringAfter("://")})" } ?: state.ref.relayUrl.substringAfter("://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val meta = state.metadata
                    val flags = buildList {
                        if (meta == null && state.needsAuth) add("このリレーはログインが必要なため、グループ情報を読めません")
                        else if (meta == null) add("グループ情報を読めませんでした")
                        if (meta?.isPrivate == true) add("メンバーだけが読めます")
                        if (meta?.isRestricted == true) add("メンバーだけが書けます")
                        if (meta?.isClosed == true) add(if (state.inviteCode != null) "招待制（招待コードあり）" else "招待制です")
                    }
                    flags.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                    meta?.about?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    (gate.error ?: state.error)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (hasSigner) gate.hint?.let { JoinSignerHint(it) }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = onBack) { Text("戻る") }
                        if (state.needsAuth && hasSigner) {
                            OutlinedButton(onClick = onAuthenticate, enabled = !state.isJoining) { Text("ログインして確認") }
                        } else if (meta?.isPrivate != true) {
                            OutlinedButton(onClick = { onPreview(state.ref) }) { Text("中を見る") }
                        }
                        val canJoin = hasSigner && !(meta?.isClosed == true && state.inviteCode == null)
                        Button(onClick = { gate.proceed(onJoin) }, enabled = canJoin && !state.isJoining && !gate.busy) {
                            if (state.isJoining || gate.busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(gate.prefix + "参加する")
                        }
                    }
                    if (hasSigner) {
                        Text(
                            "参加すると、このリレーに公開鍵を伝えてログインすることがあります",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "参加するには署名できるアカウントでログインしてください",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal fun chatRelativeTime(epochSeconds: Long): String {
    val now = kotlin.time.Clock.System.now().epochSeconds
    val diff = (now - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60L -> "たった今"
        diff < 3600L -> "${diff / 60L}分前"
        diff < 86400L -> "${diff / 3600L}時間前"
        diff < 86400L * 30L -> "${diff / 86400L}日前"
        else -> "${diff / (86400L * 30L)}ヶ月前"
    }
}

/**
 * 参加の操作の前に、署名器の解錠・再接続を済ませる。
 * 参加シートの暗幕の下では画面上部の帯（SignerStatusBanner）が押せないため、参加ボタン自体を「解錠して参加」にする。
 */
@Stable
internal class JoinSignerGate(
    val attention: SignerAttention?,
    val busy: Boolean,
    val error: String?,
    val proceed: (() -> Unit) -> Unit,
) {
    /** 参加ボタンの頭に付ける言葉。 */
    val prefix: String
        get() = when (attention) {
            SignerAttention.Locked -> "解錠して"
            is SignerAttention.Disconnected -> "再接続して"
            else -> ""
        }

    /** ボタンの上に出す説明。 */
    val hint: String?
        get() = when (attention) {
            SignerAttention.Locked -> "参加の前に、パスキーで鍵を解錠します。"
            is SignerAttention.Disconnected -> "署名器に接続できません（${attention.reason}）。参加の前に接続し直します。"
            is SignerAttention.ApprovalRequired -> "署名器で、この操作を承認してください。"
            null -> null
        }
}

@Composable
private fun rememberJoinSignerGate(): JoinSignerGate {
    val signer = LocalAccountSession.current?.signer as? InteractiveSigner
    val attention = signer?.attention?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var busy by remember(signer) { mutableStateOf(false) }
    var error by remember(signer) { mutableStateOf<String?>(null) }
    return JoinSignerGate(attention, busy, error) proceed@{ action ->
        if (busy) return@proceed
        error = null
        val needs = attention
        if (signer == null || (needs != SignerAttention.Locked && needs !is SignerAttention.Disconnected)) {
            action()
            return@proceed
        }
        busy = true
        // パスキーの呼び出しは、ボタンを押したこの操作の中で始める（WebAuthn は利用者の操作を要る）。
        scope.launch {
            val ready = try {
                if (needs == SignerAttention.Locked) signer.unlock() else signer.reconnect()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logException("JoinSignerGate", e, "Signer unlock before join failed")
                error = e.message ?: if (needs == SignerAttention.Locked) "解錠できませんでした" else "接続できませんでした"
                false
            } finally {
                busy = false
            }
            if (ready) action()
        }
    }
}

@Composable
private fun JoinSignerHint(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BuzzJoinContent(
    state: JoinSheetState.Buzz,
    hasSigner: Boolean,
    accountName: String?,
    gate: JoinSignerGate,
    onBack: () -> Unit,
    onConsent: (Boolean?, Boolean?) -> Unit,
    onJoin: () -> Unit,
) {
    val host = state.relayUrl.substringAfter("://")
    Text(state.name?.takeIf { it.isNotBlank() } ?: host, style = MaterialTheme.typography.titleMedium)
    Text(
        host + "  [Buzz]" + if (state.inviteOnly) " [招待制]" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (state.code != null) "招待リンクで参加します。参加すると、このコミュニティにあなたの公開鍵を伝えてログインします。"
        else "このコミュニティに参加済みかを、公開鍵を伝えてログインして確かめます。",
        style = MaterialTheme.typography.bodySmall,
    )
    if (hasSigner && accountName != null) {
        Text(
            "参加するアカウント: $accountName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val policy = state.policy
    if (state.code != null && policy != null) {
        Spacer(Modifier.height(8.dp))
        val terms = listOfNotNull(policy.termsMarkdown, policy.privacyMarkdown).joinToString("\n\n---\n\n")
        if (terms.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    MarkdownMessageBody(
                        text = terms,
                        imageAuth = { ImageAuth.NotNeeded },
                        onUnlockImages = {},
                        onBuzzLink = {},
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.agreed, onCheckedChange = { onConsent(it, null) })
            Text("利用規約とプライバシーポリシーに同意する", style = MaterialTheme.typography.bodySmall)
        }
        if (policy.ageAttestationRequired) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.ageConfirmed, onCheckedChange = { onConsent(null, it) })
                Text("18歳以上です", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    (gate.error ?: state.error)?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (hasSigner) gate.hint?.let { JoinSignerHint(it) }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("戻る") }
        Button(onClick = onJoin, enabled = hasSigner && state.canSubmit && !gate.busy) {
            if (state.isJoining || gate.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    // 解錠が要るときは「解錠して参加」。同意はチェックで済んでいるので言葉を重ねない。
                    gate.prefix + when {
                        state.code == null -> "接続"
                        policy != null && gate.prefix.isEmpty() -> "同意して参加"
                        else -> "参加する"
                    },
                )
            }
        }
    }
    if (!hasSigner) {
        Text("参加するには署名できるアカウントでログインしてください", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** 終わっていない作成（応答の取りこぼしなど）。同じ ID でやり直すか、端末の記録を消す。 */
@Composable
private fun PendingCreationBanner(
    pending: PendingCreation,
    busy: Boolean,
    enabled: Boolean,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)) {
            Text("「${pending.name}」の作成が終わっていません", style = MaterialTheme.typography.bodyMedium)
            Text(
                "破棄しても、リレーに作られたものは消えません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDiscard, enabled = enabled) { Text("破棄") }
                TextButton(onClick = onResume, enabled = enabled) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("再開")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateChannelSheet(
    state: CreateSheetState.Channel,
    onDismiss: () -> Unit,
    onChange: ((CreateSheetState.Channel) -> CreateSheetState.Channel) -> Unit,
    onTarget: (ChatRelaySummary) -> Unit,
    onSubmit: () -> Unit,
) {
    val buzz = state.target.flavor == GroupFlavor.Buzz
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            val forum = state.type == NewGroupType.Forum
            ChatSheetHeader(
                icon = if (forum) Icons.Outlined.Forum else if (buzz) Icons.Default.Tag else Icons.Default.Groups,
                title = if (forum) "新しいフォーラム" else if (buzz) "新しいチャンネル" else "新しいグループ",
                subtitle = when {
                    forum -> "タイトル付きの投稿にコメントを付けていく形式です"
                    buzz -> "コミュニティのメンバーと話す場所を作ります"
                    else -> "このリレーに NIP-29 のグループを作ります"
                },
            )
            CreateTargetCard(state.targets, state.target, enabled = !state.isSubmitting, onTarget = onTarget)
            ChatSheetLabel("名前と説明")
            val nameError = state.name.takeIf { it.isNotEmpty() }?.let(::groupNameError)
            OutlinedTextField(
                value = state.name,
                onValueChange = { text -> onChange { it.copy(name = text.replace("\n", "")) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                label = { Text("名前") },
                placeholder = { Text("release-notes") },
                leadingIcon = { Icon(if (forum) Icons.Outlined.Forum else Icons.Default.Tag, contentDescription = null) },
                singleLine = true,
                enabled = !state.isSubmitting,
                isError = nameError == GroupNameError.TooLong,
                supportingText = if (nameError == GroupNameError.TooLong) { { Text("名前は255文字までです") } } else null,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.about,
                onValueChange = { text -> onChange { it.copy(about = text) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                label = { Text("説明（任意）") },
                placeholder = { Text("どんな話をする場所か") },
                minLines = 2,
                maxLines = 4,
                enabled = !state.isSubmitting,
            )
            ChatSheetLabel("公開範囲")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ChoiceCard(
                    icon = Icons.Outlined.Public,
                    title = "公開",
                    description = if (buzz) "コミュニティの誰でも見つけて参加できます" else "誰でも読めて、参加を申し込めます",
                    selected = state.visibility == NewGroupVisibility.Open,
                    enabled = !state.isSubmitting,
                    onClick = { onChange { it.copy(visibility = NewGroupVisibility.Open) } },
                )
                ChoiceCard(
                    icon = Icons.Outlined.Lock,
                    title = "非公開",
                    description = "メンバーだけが読み書きできます。参加には招待が必要です",
                    selected = state.visibility == NewGroupVisibility.Private,
                    enabled = !state.isSubmitting,
                    onClick = { onChange { it.copy(visibility = NewGroupVisibility.Private) } },
                )
            }
            if (!buzz) {
                ChatSheetNote(
                    "作成すると、このリレーに公開鍵を伝えてログインすることがあります。リレーによっては作成を受け付けません",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            state.error?.let { ChatSheetError(it, modifier = Modifier.padding(top = 12.dp)) }
            ChatSheetActions(
                confirmLabel = "作成",
                confirmIcon = Icons.Default.Add,
                enabled = state.canSubmit,
                busy = state.isSubmitting,
                onCancel = onDismiss,
                onConfirm = onSubmit,
            )
        }
    }
}

/** DM の宛先の候補を、名前の部分一致か公開鍵（hex）の前方一致で絞り込み、名前順に並べる。選択済みは除く。 */
internal fun filterDmCandidates(
    candidates: List<String>,
    picked: List<String>,
    query: String,
    nameOf: (String) -> String?,
    limit: Int = 30,
): List<String> {
    val needle = query.trim().lowercase()
    return candidates.asSequence()
        .filter { it !in picked }
        .filter { needle.isEmpty() || nameOf(it)?.lowercase()?.contains(needle) == true || it.startsWith(needle) }
        .sortedBy { nameOf(it)?.lowercase() ?: "\uFFFF$it" }
        .take(limit)
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateDmSheet(
    state: CreateSheetState.Dm,
    onDismiss: () -> Unit,
    onTarget: (ChatRelaySummary) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val people = remember(state.candidates, state.picked, state.typedPubkey) {
        (state.candidates + state.picked + listOfNotNull(state.typedPubkey)).toSet()
    }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(emptyMap())
    val shown = remember(state.candidates, state.picked, state.query, state.typedPubkey, profiles) {
        state.typedPubkey?.takeIf { it !in state.picked }?.let(::listOf)
            ?: filterDmCandidates(state.candidates, state.picked, state.query, nameOf = { profiles[it]?.bestName })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            ChatSheetHeader(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "新しい DM",
                subtitle = "このコミュニティのメンバーと、少人数で話します",
            )
            CreateTargetCard(state.targets, state.target, enabled = !state.isSubmitting, onTarget = onTarget)
            ChatSheetLabel(if (state.picked.isEmpty()) "宛先" else "宛先（${state.picked.size}/${MAX_DM_OTHERS}人）")
            if (state.picked.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.picked.forEach { pubkey ->
                        InputChip(
                            selected = true,
                            onClick = { onToggle(pubkey) },
                            enabled = !state.isSubmitting,
                            label = { Text(profiles[pubkey]?.bestName ?: (pubkey.take(8) + "…"), maxLines = 1) },
                            avatar = {
                                ChatAvatar(pubkey = pubkey, name = profiles[pubkey]?.bestName, pictureUrl = profiles[pubkey]?.picture, size = 22)
                            },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "外す", modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("名前、npub で探す") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                enabled = !state.isSubmitting && state.canPickMore,
                supportingText = if (!state.canPickMore) { { Text("宛先は${MAX_DM_OTHERS}人までです") } } else null,
            )
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (shown.isEmpty()) {
                    Text(
                        if (state.query.isBlank() && state.candidates.isEmpty()) "候補がありません。npub を貼り付けて指定できます" else if (state.query.isBlank()) "" else "見つかりません。npub を貼り付けて指定できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                shown.forEach { pubkey ->
                    val profile = profiles[pubkey]
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.isSubmitting && state.canPickMore, role = Role.Button) { onToggle(pubkey) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            profile?.nip05?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "宛先に加える",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            state.error?.let { ChatSheetError(it, modifier = Modifier.padding(top = 12.dp)) }
            ChatSheetActions(
                confirmLabel = "開く",
                confirmIcon = Icons.AutoMirrored.Filled.Chat,
                enabled = state.canSubmit,
                busy = state.isSubmitting,
                onCancel = onDismiss,
                onConfirm = onSubmit,
            )
        }
    }
}

/** コミュニティの管理（docs/buzz-admin-design.md 3.5）。メンバーの役割の変更・外す、招待リンクの発行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityAdminSheetView(
    sheet: CommunityAdminSheet,
    ownPubkey: String?,
    onDismiss: () -> Unit,
    onTab: (CommunityAdminTab) -> Unit,
    onAction: (com.nostr.torinos.chat.CommunityMember, com.nostr.torinos.chat.CommunityAction) -> Unit,
    onDismissError: () -> Unit,
    onTtl: (Long) -> Unit,
    onUses: (Int?) -> Unit,
    onMint: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    val people = remember(sheet.members) { sheet.members.orEmpty().map { it.pubkey }.toSet() }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(emptyMap())
    val ordered = remember(sheet.members, profiles, ownPubkey) {
        com.nostr.torinos.chat.orderCommunityMembers(sheet.members.orEmpty(), ownPubkey) { profiles[it]?.bestName }
    }
    var confirmRemove by remember { mutableStateOf<com.nostr.torinos.chat.CommunityMember?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("コミュニティの管理", style = MaterialTheme.typography.titleMedium)
            Text(sheet.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sheet.tab == CommunityAdminTab.Members, onClick = { onTab(CommunityAdminTab.Members) }, label = { Text("メンバー") })
                FilterChip(selected = sheet.tab == CommunityAdminTab.Invite, onClick = { onTab(CommunityAdminTab.Invite) }, label = { Text("招待") })
            }
        }
        when (sheet.tab) {
            CommunityAdminTab.Members -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") {
                    Text(
                        if (sheet.members != null) "メンバー ${sheet.members.size}人" else "メンバー",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    if (sheet.isLoading && sheet.members == null) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 20.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                    sheet.error?.let { error ->
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = onDismissError) { Icon(Icons.Default.Close, contentDescription = "閉じる", modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                items(ordered, key = { it.pubkey }) { member ->
                    val profile = profiles[member.pubkey]
                    val name = profile?.bestName ?: member.pubkey.take(8)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(pubkey = member.pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 36)
                        Spacer(Modifier.width(12.dp))
                        // 名前と役割をまとめて残りの幅に入れ、右端の「︙」の位置をそろえる。
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (member.pubkey == ownPubkey) {
                                Spacer(Modifier.width(6.dp))
                                Text("（自分）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            com.nostr.torinos.chat.communityRoleLabel(member.role)?.let { label ->
                                Spacer(Modifier.width(6.dp))
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        val actions = com.nostr.torinos.chat.allowedCommunityActions(sheet.viewerRole, member, self = member.pubkey == ownPubkey)
                        when {
                            member.pubkey in sheet.busy -> CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                            actions.isNotEmpty() -> Box {
                                var open by remember { mutableStateOf(false) }
                                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "$name の管理") }
                                com.nostr.torinos.ui.components.ActionDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    actions.forEach { action ->
                                        when (action) {
                                            com.nostr.torinos.chat.CommunityAction.Promote -> com.nostr.torinos.ui.components.ActionDropdownMenuItem(
                                                "管理者にする", Icons.Outlined.AdminPanelSettings, onClick = { open = false; onAction(member, action) },
                                            )
                                            com.nostr.torinos.chat.CommunityAction.Demote -> com.nostr.torinos.ui.components.ActionDropdownMenuItem(
                                                "メンバーにする", Icons.Outlined.AdminPanelSettings, onClick = { open = false; onAction(member, action) },
                                            )
                                            com.nostr.torinos.chat.CommunityAction.Remove -> {
                                                if (actions.size > 1) com.nostr.torinos.ui.components.ActionDropdownMenuDivider()
                                                com.nostr.torinos.ui.components.ActionDropdownMenuItem(
                                                    "コミュニティから外す", Icons.Outlined.PersonRemove, destructive = true,
                                                    onClick = { open = false; confirmRemove = member },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            CommunityAdminTab.Invite -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("有効期限", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    INVITE_TTL_CHOICES.forEach { (seconds, label) ->
                        FilterChip(selected = sheet.inviteTtlSeconds == seconds, onClick = { onTtl(seconds) }, label = { Text(label) }, enabled = !sheet.isMinting)
                    }
                }
                Text("使える回数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    INVITE_USES_CHOICES.forEach { (uses, label) ->
                        FilterChip(selected = sheet.inviteMaxUses == uses, onClick = { onUses(uses) }, label = { Text(label) }, enabled = !sheet.isMinting)
                    }
                }
                Text(
                    "招待リンクを知っている人は誰でもこのコミュニティに参加できます。作ったリンクはこの画面を閉じると表示されなくなります（リンクは期限まで使えます）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sheet.inviteError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onMint, enabled = !sheet.isMinting, modifier = Modifier.align(Alignment.End)) {
                    if (sheet.isMinting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("招待リンクを作る")
                }
                if (sheet.invites.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("作った招待", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "ToriNos のリンクは、ToriNos アプリを入れた端末で開くと参加の画面に進みます。Buzz アプリを使う人には Buzz のリンクを渡してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sheet.invites.forEach { invite ->
                        val toriNosLink = remember(invite) { com.nostr.torinos.chat.toriNosInviteLinkFor(invite) }
                        Column {
                            Text(com.nostr.torinos.chat.inviteSummary(invite), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOfNotNull(toriNosLink?.let { "ToriNos" to it }, "Buzz" to invite.url).forEach { (label, url) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$label  ", style = MaterialTheme.typography.labelMedium)
                                    Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { copyScope.launch { clipboard.setPlainText(url) }; copied = url }) {
                                        Text(if (copied == url) "コピー済み" else "コピー")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmRemove?.let { member ->
        val name = profiles[member.pubkey]?.bestName ?: member.pubkey.take(8)
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("$name をコミュニティから外しますか？") },
            text = { Text("このコミュニティにログインできなくなります。もう一度参加するには新しい招待が必要です。") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; onAction(member, com.nostr.torinos.chat.CommunityAction.Remove) }) {
                    Text("外す", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("キャンセル") } },
        )
    }
}
