package com.nostr.torinos.ui.chat

import com.nostr.torinos.ui.components.dismissKeyboardOnListTouch
import com.nostr.torinos.ui.components.keyboardAvoidingPadding
import com.nostr.torinos.ui.components.ActionDropdownMenu
import com.nostr.torinos.ui.components.ActionDropdownMenuItem
import com.nostr.torinos.ui.components.ActionDropdownMenuDivider
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import com.nostr.torinos.chat.groupEditOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.ContentPaste
import com.nostr.torinos.ui.components.AppMessageComposer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.chat.BuzzLink
import com.nostr.torinos.chat.ChatTimelineItem
import com.nostr.torinos.chat.chatClockTime
import com.nostr.torinos.chat.chatDayLabel
import com.nostr.torinos.chat.chatLocalDate
import com.nostr.torinos.chat.chatTimeline
import kotlinx.datetime.TimeZone
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.chat.ChannelRole
import com.nostr.torinos.chat.GroupMembership
import com.nostr.torinos.chat.GroupMessage
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.groupPostTitle
import com.nostr.torinos.chat.parseBuzzLink
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.AppFloatingActionButton
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.profile.AvatarCircle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.nostr.torinos.chat.buzzMediaHost
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.ui.components.QuickReactionContent
import com.nostr.torinos.ui.components.recordReactionUse
import com.nostr.torinos.ui.components.toReactionOption
import com.nostr.torinos.chat.toReactionOption
import com.nostr.torinos.ui.components.LocalEmojiImageHeaders
import com.nostr.torinos.ui.components.StandardEmojiPickerSheet
import com.nostr.torinos.ui.components.DefaultReactionPickerMode
import com.nostr.torinos.ui.components.CommunityEmojiPickerSource

/** 画面から別の画面へ移るための入口。 */
class GroupNavigation(
    val onBack: () -> Unit,
    val onUserClick: (String) -> Unit,
    /** チャット（[GroupRoomMode.Chat]）を開く。 */
    val onOpenGroup: (GroupRef) -> Unit,
    /** スレッドを開く。 */
    val onOpenThread: (GroupRef, String) -> Unit,
    /** チャットとして開いたグループがフォーラムだった（Buzz のリンクなど）。フォーラムの画面に置き換える。 */
    val onReplaceWithForum: (GroupRef) -> Unit = {},
)

/** チャット画面（設計書 4.6）。 */
@Composable
fun GroupChatScreen(ref: GroupRef, navigation: GroupNavigation) =
    GroupRoomScreen(ref, GroupRoomMode.Chat, navigation)

/** スレッドの画面（設計書 4.7）。 */
@Composable
fun GroupThreadScreen(ref: GroupRef, rootId: String, navigation: GroupNavigation) =
    GroupRoomScreen(ref, GroupRoomMode.Thread(rootId), navigation)

/** フォーラムの投稿一覧（設計書 4.8）。 */
@Composable
fun GroupForumScreen(ref: GroupRef, navigation: GroupNavigation) =
    GroupRoomScreen(ref, GroupRoomMode.Forum, navigation)

@Composable
private fun GroupRoomScreen(ref: GroupRef, mode: GroupRoomMode, navigation: GroupNavigation) {
    val session = LocalAccountSession.current
    val modeKey = when (mode) {
        GroupRoomMode.Chat -> "chat"
        GroupRoomMode.Forum -> "forum"
        is GroupRoomMode.Thread -> "thread-${mode.rootId}"
    }
    val viewModel = accountSessionViewModel(key = "group-${session?.sessionId}-${ref.key}-$modeKey") { s ->
        GroupChatViewModel(ref, s?.chat, s?.muteStore?.mutedPubkeys, mode)
    }
    val state by viewModel.state.collectAsState()
    // 前面に出ている間だけ、自分の在席を送り、ほかの人の在席を読む（Buzz）。
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(viewModel, lifecycleState) {
        viewModel.setScreenVisible(lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose { viewModel.setScreenVisible(false) }
    }
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    /** 取り返しのつかない管理の操作の確認（docs/buzz-admin-design.md 7章）。 */
    var confirm by remember { mutableStateOf<AdminConfirm?>(null) }
    var input by remember(ref, mode) { mutableStateOf("") }
    var composingPost by remember { mutableStateOf(false) }
    val pickImage = com.nostr.torinos.ui.components.rememberOptimizedImagePickerLauncher { picked ->
        picked?.let { viewModel.attach(it.uploadBytes, it.mimeType, it.previewBytes) }
    }
    val snackbarScope = rememberCoroutineScope()
    val pasteImage = com.nostr.torinos.ui.components.rememberClipboardImageReader { image ->
        if (image != null) {
            viewModel.attach(image.uploadBytes, image.mimeType, image.previewBytes)
        } else {
            snackbarScope.launch { snackbar.showSnackbar("クリップボードに貼り付けできる画像がありません") }
        }
    }

    LaunchedEffect(state.notice) {
        val message = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeNotice()
    }
    LaunchedEffect(state.metadata?.layout) {
        if (mode == GroupRoomMode.Chat && state.metadata?.layout == com.nostr.torinos.chat.GroupLayout.Forum) {
            navigation.onReplaceWithForum(ref)
        }
    }
    LaunchedEffect(state.editing) {
        state.editing?.let { input = it.body }
    }

    // DM では見出しを参加者の名前から作るので、参加者のプロフィールも見る。
    val isDirect = state.metadata?.layout == com.nostr.torinos.chat.GroupLayout.Direct
    val authors = remember(state.messages, state.threadRoot, state.metadata?.participants, state.typingAuthors) {
        (state.messages + listOfNotNull(state.threadRoot)).map { it.author }.toSet() + state.metadata?.participants.orEmpty() + state.typingAuthors
    }
    val profiles by remember(authors) {
        if (authors.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(authors)
    }.collectAsState(initial = emptyMap())

    state.editSheet?.let { sheet ->
        GroupEditDialog(sheet, onChange = viewModel::updateEdit, onDismiss = viewModel::closeEdit, onSave = viewModel::saveEdit)
    }

    val onBuzzLink: (String) -> Unit = { href ->
        when (val link = parseBuzzLink(href)) {
            is BuzzLink.Channel -> navigation.onOpenGroup(GroupRef(ref.relayUrl, link.channelId))
            is BuzzLink.Message -> navigation.onOpenThread(GroupRef(ref.relayUrl, link.channelId), link.threadRootId ?: link.messageId)
            null -> Unit
        }
    }
    // ---- 絵文字リアクション（docs/buzz-emoji-reaction-design.md） ----
    val emojiPreferences = com.nostr.torinos.ui.components.rememberEmojiPreferences()
    val isSendable: (ReactionOption) -> Boolean = remember(state.flavor) {
        { option -> com.nostr.torinos.chat.isGroupReactionSendable(option, state.flavor) }
    }
    val quickReactions = remember(state.communityRecent, emojiPreferences, isSendable) {
        com.nostr.torinos.chat.groupQuickReactionOptions(
            communityRecent = state.communityRecent,
            accountRecent = emojiPreferences.recent.map { it.toReactionOption(emojiPreferences::setAddressOf) },
            isSendable = isSendable,
        )
    }
    val palette = state.communityEmojis.palette?.byShortcode.orEmpty()
    var pickerTarget by remember { mutableStateOf<GroupMessage?>(null) }
    var reactionMenuOpen by remember { mutableStateOf(false) }
    val react: (GroupMessage, ReactionOption) -> Unit = { message, option ->
        // フィードと同じく、選んだときに「最近使った」に記録する。コミュニティの絵文字はアカウント全体には入れない。
        if (viewModel.react(message, option) == GroupReactionResult.Added &&
            com.nostr.torinos.chat.recordsInAccountRecent(option, emojiPreferences.available)
        ) {
            session.recordReactionUse(option)
        }
    }
    // タグの無い `:shortcode:` のリアクション（Buzz のエージェント）を画像にするため、コミュニティの絵文字を読む。
    val needsPalette = remember(state.messages, state.threadRoot) {
        (state.messages + listOfNotNull(state.threadRoot)).any { com.nostr.torinos.chat.hasTagLessShortcodeReaction(it.reactions) }
    }
    LaunchedEffect(needsPalette, state.flavor) {
        if (needsPalette && state.flavor == GroupFlavor.Buzz) viewModel.ensureCommunityEmojis()
    }
    val emojiImageHeaders = rememberCommunityEmojiHeaders(
        relayUrl = ref.relayUrl,
        active = reactionMenuOpen || pickerTarget != null,
        imageAuth = viewModel::imageAuthorization,
        imagesUnlocked = state.imagesUnlocked,
    )
    pickerTarget?.let { target ->
        CompositionLocalProvider(LocalEmojiImageHeaders provides emojiImageHeaders) {
            StandardEmojiPickerSheet(
                onDismiss = { pickerTarget = null },
                defaultReactionMode = DefaultReactionPickerMode.Offer,
                community = if (state.flavor == GroupFlavor.Buzz) {
                    val emojis = state.communityEmojis
                    CommunityEmojiPickerSource(
                        emojis = emojis.palette?.emojis.orEmpty(),
                        isLoading = emojis.isLoading,
                        failed = emojis.failed,
                        truncated = emojis.palette?.truncated == true,
                        onRetry = viewModel::ensureCommunityEmojis,
                    )
                } else {
                    null
                },
                isSendable = isSendable,
                onSelect = { option ->
                    pickerTarget = null
                    // 開いている間に更新されたメッセージ（集計）で判定する。
                    val current = (state.messages + listOfNotNull(state.threadRoot)).firstOrNull { it.event.id == target.event.id } ?: target
                    react(current, option)
                },
            )
        }
    }

    val rowActions = MessageRowActions(
        onReply = { message ->
            if (mode == GroupRoomMode.Chat && state.flavor == GroupFlavor.Buzz) navigation.onOpenThread(ref, message.event.id)
            else viewModel.setReplyTo(message)
        },
        onReact = react,
        onOpenEmojiPicker = { message ->
            pickerTarget = message
            viewModel.ensureCommunityEmojis()
        },
        onReactionMenuChanged = { reactionMenuOpen = it },
        quickReactions = quickReactions,
        palette = palette,
        reactingMessageIds = state.reactingMessageIds,
        ownEmojiSetAddress = emojiPreferences::setAddressOf,
        onCopy = { clipboard.setText(AnnotatedString(it.body)) },
        onCopyLink = { clipboard.setText(AnnotatedString(viewModel.messageLink(it))) },
        onEdit = { if (viewModel.canEdit(it)) viewModel.startEditing(it) },
        canEdit = viewModel::canEdit,
        onDelete = viewModel::delete,
        canAdminDelete = viewModel::canAdminDelete,
        onAdminDelete = { message -> confirm = AdminConfirm.DeleteMessage(message, profiles[message.author]?.bestName ?: message.author.take(8)) },
        onOpenThread = { navigation.onOpenThread(ref, it.event.id) },
        onUserClick = navigation.onUserClick,
        onNeedProfiles = viewModel::ensureProfiles,
        onBuzzLink = onBuzzLink,
        imageAuth = viewModel::imageAuthorization,
        onUnlockImages = viewModel::unlockImages,
        imagesUnlocked = state.imagesUnlocked,
        presence = state.presence,
    )

    CompositionLocalProvider(LocalEmojiImageHeaders provides emojiImageHeaders) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (mode == GroupRoomMode.Forum && state.composer == GroupComposerState.Writable) {
                AppFloatingActionButton(onClick = { composingPost = true }, icon = Icons.Default.Add, contentDescription = "新しい投稿")
            }
        },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                AppTopBar(
                    navigationIcon = {
                        IconButton(onClick = navigation.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                    },
                    title = {
                        // 見出しを押すとグループの情報（説明・メンバー）を出す。
                        Column(modifier = Modifier.clickable(onClickLabel = "グループの情報") { infoOpen = true }) {
                            val title = when (mode) {
                                is GroupRoomMode.Thread -> state.threadRoot?.let { root ->
                                    if (root.event.kind == com.nostr.torinos.chat.BUZZ_FORUM_POST_KIND || root.event.kind == com.nostr.torinos.chat.GROUP_THREAD_KIND) {
                                        groupPostTitle(root.event)
                                    } else {
                                        "スレッド"
                                    }
                                } ?: "スレッド"
                                else -> if (isDirect) {
                                    com.nostr.torinos.chat.dmDisplayName(state.metadata?.participants.orEmpty(), viewModel.ownPubkey.orEmpty()) { profiles[it]?.bestName }
                                } else {
                                    state.title
                                }
                            }
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val peerPresence = if (isDirect) dmPeerPresence(state, viewModel.ownPubkey) else null
                            Text(
                                androidx.compose.ui.text.buildAnnotatedString {
                                    // DM の相手の在席。印だけ在席の色にする（文字は見出しの色のまま）。
                                    peerPresence?.let { (color, label) ->
                                        withStyle(androidx.compose.ui.text.SpanStyle(color = color)) { append("● ") }
                                        append("$label · ")
                                    }
                                    append(
                                        when {
                                            mode is GroupRoomMode.Thread && !isDirect -> state.title
                                            isDirect -> "DM · " + (state.relayName ?: ref.relayUrl.substringAfter("://"))
                                            else -> listOfNotNull(
                                                state.members?.takeIf { it.isNotEmpty() }?.let { "${it.size}人" },
                                                state.relayName ?: ref.relayUrl.substringAfter("://"),
                                            ).joinToString(" · ")
                                        },
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        // DM にはメニューの項目が無い。
                        if (!isDirect) Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "メニュー") }
                            ActionDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                // DM はリンクで開けず、退出もできない（隠す操作は Buzz のアプリで行う）。
                                val link = if (isDirect) null else viewModel.shareLink()
                                if (state.canEdit && mode !is GroupRoomMode.Thread) {
                                    ActionDropdownMenuItem("グループを編集", Icons.Outlined.Edit, onClick = { menuOpen = false; viewModel.openEdit() })
                                }
                                if (link != null) {
                                    ActionDropdownMenuItem("グループのリンクをコピー", Icons.Outlined.Link,
                                        onClick = { menuOpen = false; clipboard.setText(AnnotatedString(link)) })
                                }
                                // Buzz の owner / admin の操作（docs/buzz-admin-design.md 3.2）。スレッドの画面には出さない。
                                val authority = state.authority
                                if (mode !is GroupRoomMode.Thread && (authority.canArchive || authority.canUnarchive || authority.canDelete)) {
                                    ActionDropdownMenuDivider()
                                    if (authority.canArchive) {
                                        ActionDropdownMenuItem("アーカイブする", Icons.Outlined.Archive, enabled = !state.isAdminBusy,
                                            onClick = { menuOpen = false; confirm = AdminConfirm.Archive })
                                    }
                                    if (authority.canUnarchive) {
                                        ActionDropdownMenuItem("アーカイブを解除する", Icons.Outlined.Unarchive, enabled = !state.isAdminBusy,
                                            onClick = { menuOpen = false; viewModel.setArchived(false) })
                                    }
                                    if (authority.canDelete) {
                                        ActionDropdownMenuItem("チャンネルを削除", Icons.Outlined.DeleteForever, destructive = true, enabled = !state.isAdminBusy,
                                            onClick = { menuOpen = false; confirm = AdminConfirm.DeleteChannel(state.title) })
                                    }
                                }
                                if (!isDirect && mode != GroupRoomMode.Forum && (state.isMember || state.isPending)) {
                                    if (link != null) ActionDropdownMenuDivider()
                                    if (authority.isLastOwner) {
                                        // Buzz は最後の owner の退出を断る。ToriNos からは owner を渡せないので案内だけ出す。
                                        ActionDropdownMenuItem(
                                            "退出するには先にほかの人をオーナーにしてください",
                                            Icons.AutoMirrored.Outlined.ExitToApp,
                                            enabled = false,
                                            onClick = {},
                                        )
                                    } else {
                                        ActionDropdownMenuItem(
                                            if (state.isLeaving) "退出しています…" else "グループから退出",
                                            Icons.AutoMirrored.Outlined.ExitToApp,
                                            destructive = true,
                                            onClick = { menuOpen = false; if (!state.isLeaving) viewModel.leave(onLeft = navigation.onBack) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()
                if (state.isLoading || state.isLeaving || state.isAdminBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        },
    ) { padding ->
        // キーボードの分は Android だけ持ち上げる(iOS は画面全体が縮むので二重になる)。
        Column(modifier = Modifier.fillMaxSize().padding(padding).keyboardAvoidingPadding()) {
            GroupBanners(state = state, onAllowAuth = viewModel::allowAuth, onJoin = { viewModel.join() })
            when (mode) {
                GroupRoomMode.Chat -> MessageList(
                    messages = state.messages,
                    profiles = profiles,
                    ownPubkey = session?.pubkey,
                    flavor = state.flavor,
                    isLoadingOlder = state.isLoadingOlder,
                    canLoadOlder = state.canLoadOlder && state.messages.isNotEmpty(),
                    onLoadOlder = viewModel::loadOlder,
                    canWrite = state.composer == GroupComposerState.Writable,
                    actions = rowActions,
                    modifier = Modifier.weight(1f),
                )
                is GroupRoomMode.Thread -> ThreadList(
                    root = state.threadRoot,
                    replies = state.messages,
                    profiles = profiles,
                    ownPubkey = session?.pubkey,
                    flavor = state.flavor,
                    canWrite = state.composer == GroupComposerState.Writable,
                    actions = rowActions,
                    modifier = Modifier.weight(1f),
                )
                GroupRoomMode.Forum -> ForumList(
                    posts = state.messages,
                    profiles = profiles,
                    isLoadingOlder = state.isLoadingOlder,
                    canLoadOlder = state.canLoadOlder && state.messages.isNotEmpty(),
                    onLoadOlder = viewModel::loadOlder,
                    onOpen = { navigation.onOpenThread(ref, it.event.id) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (mode != GroupRoomMode.Forum) {
                TypingIndicator(state.typingAuthors.map { profiles[it]?.bestName ?: it.take(8) })
            }
            if (mode != GroupRoomMode.Forum || state.composer != GroupComposerState.Writable) {
                GroupComposer(
                    state = state,
                    input = input,
                    onInputChange = {
                        input = it
                        viewModel.onInputEdited(it)
                    },
                    onSend = { viewModel.send(input) { input = "" } },
                    onCancelReply = { viewModel.setReplyTo(null); viewModel.startEditing(null); input = "" },
                    onJoin = { viewModel.join() },
                    replyName = state.replyTo?.let { profiles[it.author]?.bestName ?: it.author.take(8) },
                    placeholder = when (mode) {
                        is GroupRoomMode.Thread -> "スレッドに返信"
                        else -> "メッセージを入力…"
                    },
                    showComposer = mode != GroupRoomMode.Forum,
                    onAttach = pickImage,
                    onPasteImage = pasteImage,
                    onRemoveAttachment = viewModel::removeAttachment,
                )
            }
        }
    }
    }

    // メンバー一覧を開いている間は、一覧の人の在席も見る（Buzz）。
    LaunchedEffect(viewModel, infoOpen) { viewModel.setMemberListOpen(infoOpen) }
    if (infoOpen) {
        GroupInfoSheet(
            state = state,
            title = if (isDirect) {
                com.nostr.torinos.chat.dmDisplayName(state.metadata?.participants.orEmpty(), viewModel.ownPubkey.orEmpty()) { profiles[it]?.bestName }
            } else {
                state.title
            },
            ownPubkey = session?.pubkey,
            shareLink = if (isDirect) null else viewModel.shareLink(),
            onCopyLink = { clipboard.setText(AnnotatedString(it)) },
            onEdit = { infoOpen = false; viewModel.openEdit() },
            onUserClick = { infoOpen = false; navigation.onUserClick(it) },
            onDismiss = { infoOpen = false },
            onChangeRole = viewModel::changeRole,
            onRemoveMember = { pubkey, name -> confirm = AdminConfirm.RemoveMember(pubkey, name) },
            onAddMembers = viewModel::openAddMembers,
            onDismissMemberError = viewModel::dismissMemberError,
        )
    }

    state.addMembers?.let { sheet ->
        AddMembersSheet(
            sheet = sheet,
            roles = state.authority.addableRoles,
            onDismiss = viewModel::closeAddMembers,
            onQueryChange = viewModel::onAddQueryChange,
            onToggle = viewModel::toggleAddCandidate,
            onRole = viewModel::setAddRole,
            onSubmit = viewModel::submitAddMembers,
        )
    }

    confirm?.let { pending ->
        AdminConfirmDialog(
            confirm = pending,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (pending) {
                    is AdminConfirm.RemoveMember -> viewModel.removeMember(pending.pubkey)
                    AdminConfirm.Archive -> viewModel.setArchived(true)
                    is AdminConfirm.DeleteChannel -> viewModel.deleteChannel(onDeleted = navigation.onBack)
                    is AdminConfirm.DeleteMessage -> viewModel.adminDelete(pending.message)
                }
            },
        )
    }

    if (composingPost) {
        NewPostDialog(
            isSending = state.isSending,
            onDismiss = { composingPost = false },
            onSubmit = { title, body -> viewModel.send(body, title) { composingPost = false } },
        )
    }
}

internal class MessageRowActions(
    val onReply: (GroupMessage) -> Unit,
    /** リアクションを付ける（同じものを自分が付けていれば取り消す）。 */
    val onReact: (GroupMessage, ReactionOption) -> Unit,
    val onOpenEmojiPicker: (GroupMessage) -> Unit,
    /** 長押しメニューの開閉（開いている間だけ、コミュニティの絵文字の画像の署名を用意する）。 */
    val onReactionMenuChanged: (Boolean) -> Unit,
    /** 長押しメニューに並べる「最近使った」。 */
    val quickReactions: List<ReactionOption>,
    /** コミュニティの絵文字（小文字の shortcode → URL）。タグの無い `:shortcode:` の画像に使う。 */
    val palette: Map<String, String>,
    /** リアクションを送っているメッセージ。その間は付け外しを止める。 */
    val reactingMessageIds: Set<String>,
    /** 自分の絵文字セットにある絵文字なら、そのセット（チップの長押しで開く）。 */
    val ownEmojiSetAddress: (com.nostr.torinos.emoji.CustomEmoji) -> com.nostr.torinos.emoji.EmojiSetAddress?,
    val onCopy: (GroupMessage) -> Unit,
    val onCopyLink: (GroupMessage) -> Unit,
    val onEdit: (GroupMessage) -> Unit,
    val canEdit: (GroupMessage) -> Boolean,
    val onDelete: (GroupMessage) -> Unit,
    /** Buzz の owner / admin が他人の発言を消せるか（kind 9005）。 */
    val canAdminDelete: (GroupMessage) -> Boolean = { false },
    val onAdminDelete: (GroupMessage) -> Unit = {},
    val onOpenThread: (GroupMessage) -> Unit,
    val onUserClick: (String) -> Unit,
    /** リアクションした人の一覧を開いたとき、その人たちのプロフィールを読む。 */
    val onNeedProfiles: (Set<String>) -> Unit = {},
    val onBuzzLink: (String) -> Unit,
    val imageAuth: suspend (String) -> ImageAuth,
    val onUnlockImages: () -> Unit,
    val imagesUnlocked: Boolean,
    /** 在席が分かっている人（Buzz）。 */
    val presence: Map<String, com.nostr.torinos.chat.PresenceStatus> = emptyMap(),
)

@Composable
private fun GroupBanners(state: GroupChatUiState, onAllowAuth: () -> Unit, onJoin: () -> Unit) {
    val host = state.ref.relayUrl.substringAfter("://")
    when {
        state.authConsentNeeded -> Banner(
            text = "$host はログインが必要です。公開鍵を伝えてログインしますか？",
            action = if (state.hasSigner) "ログイン" else null,
            onAction = onAllowAuth,
        )
        state.authFailure != null -> Banner(text = "ログインできませんでした: ${state.authFailure}", action = null, onAction = {})
        state.readRestricted || (state.metadata?.isPrivate == true && state.membershipChecked && !state.isMember) ->
            Banner(
                text = "メンバーだけが読めるグループです",
                action = if (state.composer == GroupComposerState.JoinToWrite) "参加する" else null,
                onAction = onJoin,
            )
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<GroupMessage>,
    profiles: Map<String, NostrProfile>,
    ownPubkey: String?,
    flavor: GroupFlavor,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    onLoadOlder: () -> Unit,
    canWrite: Boolean,
    actions: MessageRowActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 日付区切りと、直前の発言にまとめて表示するかを、一覧が変わったときに1回だけ計算する。下から積むので新しい順にする。
    val rows = remember(messages) { messageTimeline(messages).asReversed() }
    val today = rememberToday()
    LoadOlderEffect(listState, itemCount = rows.size, canLoadOlder = canLoadOlder, onLoadOlder = onLoadOlder)
    if (messages.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().dismissKeyboardOnListTouch(), contentAlignment = Alignment.Center) {
            Text("まだメッセージがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(state = listState, reverseLayout = true, modifier = modifier.fillMaxSize().dismissKeyboardOnListTouch(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows, key = { it.key }, contentType = { if (it is ChatTimelineItem.Day) "day" else "message" }) { item ->
            when (item) {
                is ChatTimelineItem.Day -> DayDivider(chatDayLabel(item.date, today))
                is ChatTimelineItem.Message -> MessageRow(
                    message = item.message,
                    profile = profiles[item.message.author],
                    replyProfile = item.message.replyTo?.let { profiles[it.author] },
                    grouped = item.grouped,
                    isOwn = item.message.event.pubkey == ownPubkey,
                    canWrite = canWrite,
                    flavor = flavor,
                    actions = actions,
                )
            }
        }
        if (isLoadingOlder) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** 発言の並びに日付区切りを入れ、同じ人の連投をまとめる。返信（引用）は前とまとめない。 */
private fun messageTimeline(messages: List<GroupMessage>): List<ChatTimelineItem<GroupMessage>> =
    chatTimeline(
        messages,
        TimeZone.currentSystemDefault(),
        author = { it.author },
        createdAt = { it.event.createdAt },
        startsNewGroup = { it.replyTo != null || it.replyToId != null || it.sentFromThreadLabel != null },
    )

private val ChatTimelineItem<GroupMessage>.key: String
    get() = when (this) {
        is ChatTimelineItem.Day -> "day-$date"
        is ChatTimelineItem.Message -> message.event.id
    }

/** 今日の日付。画面を開いている間に日付が変わっても、1分以内に追従する。 */
@Composable
private fun rememberToday(): kotlinx.datetime.LocalDate {
    var today by remember { mutableStateOf(chatLocalDate(kotlin.time.Clock.System.now().epochSeconds)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            today = chatLocalDate(kotlin.time.Clock.System.now().epochSeconds)
        }
    }
    return today
}

/** 日付の区切り線。 */
@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/** 一覧の終わり近くまでスクロールしたら、さらに古いものを読む。件数は常に最新の値で判定する。 */
@Composable
private fun LoadOlderEffect(listState: LazyListState, itemCount: Int, canLoadOlder: Boolean, onLoadOlder: () -> Unit) {
    val count by rememberUpdatedState(itemCount)
    val allowed by rememberUpdatedState(canLoadOlder)
    val load by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to count }
            .distinctUntilChanged()
            .collect { (last, size) -> if (allowed && size > 0 && last >= size - 3) load() }
    }
}

@Composable
private fun ThreadList(
    root: GroupMessage?,
    replies: List<GroupMessage>,
    profiles: Map<String, NostrProfile>,
    ownPubkey: String?,
    flavor: GroupFlavor,
    canWrite: Boolean,
    actions: MessageRowActions,
    modifier: Modifier = Modifier,
) {
    val today = rememberToday()
    val rows = remember(replies) { messageTimeline(replies) }
    LazyColumn(modifier = modifier.fillMaxSize().dismissKeyboardOnListTouch(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (root != null) {
            item(key = "root") {
                val rootTime = chatDayLabel(chatLocalDate(root.event.createdAt), today) + " " + chatClockTime(root.event.createdAt)
                MessageRow(root, profiles[root.author], null, grouped = false, isOwn = root.event.pubkey == ownPubkey, canWrite = canWrite, flavor = flavor, actions = actions, showThreadLink = false, timeLabel = rootTime)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "返信 ${replies.size}件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            item(key = "root-loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
        items(rows, key = { it.key }, contentType = { if (it is ChatTimelineItem.Day) "day" else "message" }) { item ->
            when (item) {
                is ChatTimelineItem.Day -> DayDivider(chatDayLabel(item.date, today))
                is ChatTimelineItem.Message -> MessageRow(
                    item.message, profiles[item.message.author], null, grouped = item.grouped,
                    isOwn = item.message.event.pubkey == ownPubkey, canWrite = canWrite, flavor = flavor, actions = actions, showThreadLink = false,
                )
            }
        }
    }
}

@Composable
private fun ForumList(
    posts: List<GroupMessage>,
    profiles: Map<String, NostrProfile>,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    onLoadOlder: () -> Unit,
    onOpen: (GroupMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (posts.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("まだ投稿がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val listState = rememberLazyListState()
    LoadOlderEffect(listState, itemCount = posts.size, canLoadOlder = canLoadOlder, onLoadOlder = onLoadOlder)
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(posts, key = { it.event.id }) { post ->
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(post) }.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(groupPostTitle(post.event).ifBlank { "（無題）" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${profiles[post.author]?.bestName ?: post.author.take(8)} · ${chatRelativeTime(post.event.createdAt)} · 💬 ${post.threadReplyCount}" +
                        (post.reactions.sumOf { it.count }.takeIf { it > 0 }?.let { " · 👍 $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val excerpt = post.body.lineSequence().drop(1).joinToString(" ").trim()
                if (excerpt.isNotEmpty()) {
                    Text(excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider()
        }
        if (isLoadingOlder) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: GroupMessage,
    profile: NostrProfile?,
    replyProfile: NostrProfile?,
    grouped: Boolean,
    isOwn: Boolean,
    canWrite: Boolean,
    flavor: GroupFlavor,
    actions: MessageRowActions,
    showThreadLink: Boolean = true,
    timeLabel: String = chatClockTime(message.event.createdAt),
) {
    var menuOpen by remember { mutableStateOf(false) }
    if (menuOpen && canWrite) {
        DisposableEffect(Unit) {
            actions.onReactionMenuChanged(true)
            onDispose { actions.onReactionMenuChanged(false) }
        }
    }
    val author = message.author
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                .padding(start = 12.dp, end = 12.dp, top = if (grouped) 2.dp else 10.dp, bottom = 2.dp),
        ) {
            Box(modifier = Modifier.width(40.dp)) {
                if (!grouped) {
                    Box(modifier = Modifier.combinedClickable(onClick = { actions.onUserClick(author) })) {
                        GroupAvatar(author = author, name = profile?.bestName, pictureUrl = profile?.picture, actions = actions)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (!grouped) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.bestName ?: author.take(8),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (message.event.kind == com.nostr.torinos.chat.BUZZ_AGENT_MESSAGE_KIND) {
                            Spacer(Modifier.width(4.dp))
                            Text("エージェント", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                message.sentFromThreadLabel?.let { label ->
                    Text(
                        "スレッドから送信" + if (label.isNotBlank()) ": $label" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (message.replyToId != null) {
                    val quoted = message.replyTo
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.width(3.dp).height(16.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (quoted != null) {
                                "${replyProfile?.bestName ?: quoted.author.take(8)}: ${quoted.body.lineSequence().firstOrNull().orEmpty()}"
                            } else {
                                "返信先のメッセージ"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (flavor == GroupFlavor.Buzz) {
                    MarkdownMessageBody(
                        text = message.body,
                        imageAuth = actions.imageAuth,
                        onUnlockImages = actions.onUnlockImages,
                        onBuzzLink = actions.onBuzzLink,
                        imagesUnlocked = actions.imagesUnlocked,
                        emojis = message.emojis,
                    )
                } else {
                    StandardMessageBody(message, onUserClick = actions.onUserClick)
                }
                // 本文の最初のリンクのプレビュー。通常の投稿と同じ部品と表示設定を使う(オフならタップで表示)。
                val previewUrl = remember(message.body, flavor) { com.nostr.torinos.chat.groupLinkPreviewUrl(message.body, flavor) }
                if (previewUrl != null) {
                    val showImagePreviews by com.nostr.torinos.network.DisplayPreferencesStore.showImagePreviews.collectAsState()
                    val showXPreviews by com.nostr.torinos.network.DisplayPreferencesStore.showXPreviews.collectAsState()
                    com.nostr.torinos.ui.components.LinkPreviewCard(
                        url = previewUrl,
                        showXPreview = showXPreviews,
                        showImagePreview = showImagePreviews,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )
                }
                if (message.edited) {
                    Text("（編集済み）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.reactions.isNotEmpty()) {
                    GroupReactionChips(message, canWrite, actions)
                }
                if (showThreadLink && message.threadReplyCount > 0) {
                    Text(
                        "💬 返信 ${message.threadReplyCount}件 ▸",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp).clickable { actions.onOpenThread(message) },
                    )
                }
            }
        }
        ActionDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            // 絵文字の並びは中身が幅を決める（フィードのリアクションのメニューと同じ）。
            modifier = if (canWrite) Modifier else Modifier.widthIn(min = 200.dp, max = 280.dp),
        ) {
            if (canWrite) {
                val selectedKeys = remember(message.reactions, actions.palette) {
                    message.reactions.filter { it.reactedByMe }.map { it.toReactionOption(actions.palette).key }.toSet()
                }
                QuickReactionContent(
                    options = actions.quickReactions,
                    selectedReactionKeys = selectedKeys,
                    onDismiss = { menuOpen = false },
                    onSelect = { option -> menuOpen = false; actions.onReact(message, option) },
                    onOpenStandardEmojiPicker = { menuOpen = false; actions.onOpenEmojiPicker(message) },
                )
                ActionDropdownMenuDivider()
                ActionDropdownMenuItem("返信", Icons.AutoMirrored.Outlined.Reply, onClick = { menuOpen = false; actions.onReply(message) })
            }
            ActionDropdownMenuItem("テキストをコピー", Icons.Outlined.ContentCopy, onClick = { menuOpen = false; actions.onCopy(message) })
            ActionDropdownMenuItem("リンクをコピー", Icons.Outlined.Link, onClick = { menuOpen = false; actions.onCopyLink(message) })
            if (canWrite && actions.canEdit(message)) {
                ActionDropdownMenuItem("編集", Icons.Outlined.Edit, onClick = { menuOpen = false; actions.onEdit(message) })
            }
            if (isOwn) {
                ActionDropdownMenuDivider()
                ActionDropdownMenuItem("削除", Icons.Outlined.Delete, destructive = true, onClick = { menuOpen = false; actions.onDelete(message) })
            } else if (actions.canAdminDelete(message)) {
                ActionDropdownMenuDivider()
                ActionDropdownMenuItem("削除（管理者）", Icons.Outlined.Delete, destructive = true, onClick = { menuOpen = false; actions.onAdminDelete(message) })
            }
        }
    }
}

/**
 * 標準の NIP-29 のメッセージ本文。画像の URL は本文から外し、通常の投稿と同じく下に並べる
 * （表示設定で画像のプレビューを切っていれば、押して出す）。押すと全画面で開く。
 */
@Composable
internal fun StandardMessageBody(message: GroupMessage, onUserClick: (String) -> Unit) {
    val parsed = remember(message.event.id, message.body, message.event.tags) {
        com.nostr.torinos.model.parseNoteContent(message.event.copy(content = message.body))
    }
    val showImagePreviews by com.nostr.torinos.network.DisplayPreferencesStore.showImagePreviews.collectAsState()
    var revealed by remember(message.event.id) { mutableStateOf(false) }
    var expanded by remember(message.event.id) { mutableStateOf<com.nostr.torinos.ui.components.ExpandedImageState?>(null) }
    // 外すのは画像の URL だけ（チャットは nostr: の引用や動画を別に出さないので、本文に残す）。
    val text = remember(parsed, message.body) {
        if (parsed.images.isEmpty()) {
            message.body
        } else {
            parsed.images.fold(com.nostr.torinos.model.stripImageUrls(message.body)) { t, image -> t.replace(image.url, "") }.trim()
        }
    }
    if (text.isNotBlank()) {
        LinkedText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            customEmojis = message.emojis,
            onProfileClick = onUserClick,
        )
    }
    if (parsed.images.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.widthIn(max = 480.dp)) {
            if (showImagePreviews || revealed) {
                com.nostr.torinos.ui.components.ImagePreviewGrid(
                    images = parsed.images,
                    singleImageMaxHeight = 320.dp,
                    onImageClick = { urls, index -> expanded = com.nostr.torinos.ui.components.ExpandedImageState(urls, index) },
                )
            } else {
                com.nostr.torinos.ui.components.PreviewLoadPlaceholder(
                    label = "画像 ${parsed.images.size}枚",
                    onReveal = { revealed = true },
                )
            }
        }
    }
    expanded?.let { state ->
        com.nostr.torinos.ui.components.ExpandedImageDialog(
            imageUrls = state.urls,
            initialIndex = state.initialIndex,
            onDismiss = { expanded = null },
        )
    }
}

/**
 * Buzz のアイコンはコミュニティの `/media/` にあり、読むのに Blossom の認証が要る。メッセージの画像と同じ認証で読む。
 * 認証の許可が要る(外部の署名器で未許可)ときや失敗したときは、頭文字のアイコンにする。
 */
@Composable
private fun GroupAvatar(author: String, name: String?, pictureUrl: String?, actions: MessageRowActions) {
    var headers by remember(pictureUrl) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(pictureUrl, actions.imagesUnlocked) {
        headers = pictureUrl?.takeIf { it.isNotBlank() }?.let { url ->
            when (val auth = actions.imageAuth(url)) {
                ImageAuth.NotNeeded -> emptyMap()
                is ImageAuth.Header -> mapOf("Authorization" to auth.authorization)
                ImageAuth.NeedsConsent, ImageAuth.Failed -> null
            }
        }
    }
    Box {
        AvatarCircle(
            pubkey = author,
            name = name,
            pictureUrl = pictureUrl.takeIf { headers != null },
            size = 36,
            headers = headers.orEmpty(),
        )
        actions.presence[author]?.let { PresenceBadge(it, modifier = Modifier.align(Alignment.BottomEnd)) }
    }
}

/** アバターの右下の在席の印。オンラインは緑、離席中は黄色。オフラインは何も付けない（docs/buzz-presence-design.md §2.3）。 */
@Composable
private fun PresenceBadge(status: com.nostr.torinos.chat.PresenceStatus, modifier: Modifier = Modifier) {
    val (color, label) = when (status) {
        com.nostr.torinos.chat.PresenceStatus.Online -> PresenceOnlineColor to "オンライン"
        com.nostr.torinos.chat.PresenceStatus.Away -> PresenceAwayColor to "離席中"
        com.nostr.torinos.chat.PresenceStatus.Offline -> return
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color)
            .semantics { contentDescription = label },
    )
}

private val PresenceOnlineColor = androidx.compose.ui.graphics.Color(0xFF2E9E5B)
private val PresenceAwayColor = androidx.compose.ui.graphics.Color(0xFFE0A100)

/** DM の相手（1人のとき）の在席の色と文言。オンライン・離席中のときだけ（オフライン・不明は null）。 */
private fun dmPeerPresence(state: GroupChatUiState, ownPubkey: String?): Pair<androidx.compose.ui.graphics.Color, String>? {
    val peer = state.metadata?.participants.orEmpty().filter { it != ownPubkey }.singleOrNull() ?: return null
    return when (state.presence[peer]) {
        com.nostr.torinos.chat.PresenceStatus.Online -> PresenceOnlineColor to "オンライン"
        com.nostr.torinos.chat.PresenceStatus.Away -> PresenceAwayColor to "離席中"
        else -> null
    }
}

/** 入力中の人（Buzz）。誰もいなければ何も出さない。 */
@Composable
private fun TypingIndicator(names: List<String>) {
    val label = com.nostr.torinos.chat.typingLabel(names) ?: return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun GroupComposer(
    state: GroupChatUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
    onJoin: () -> Unit,
    replyName: String?,
    placeholder: String,
    showComposer: Boolean,
    onAttach: () -> Unit,
    onPasteImage: () -> Unit,
    onRemoveAttachment: (com.nostr.torinos.chat.MessageAttachment) -> Unit,
) {
    when (state.composer) {
        GroupComposerState.Writable -> if (showComposer) Column {
            val banner = when {
                state.editing != null -> "編集中: ${state.editing.body.lineSequence().firstOrNull().orEmpty()}"
                state.replyTo != null -> "${replyName.orEmpty()} に返信: ${state.replyTo.body.lineSequence().firstOrNull().orEmpty()}"
                else -> null
            }
            // 返信・編集・添付の案内は入力欄の上に置く(チャンネルの投稿先表示と同じ並び)。
            if (banner != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(banner, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancelReply) { Icon(Icons.Default.Close, contentDescription = "やめる") }
                }
            }
            if (state.attachments.isNotEmpty() || state.isUploading) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.attachments.forEach { attachment ->
                        AttachmentThumbnail(attachment = attachment, onRemove = { onRemoveAttachment(attachment) })
                    }
                    // アップロード中は、同じ大きさの枠に進み具合を出す。
                    if (state.isUploading) {
                        Box(
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            AppMessageComposer(
                text = input,
                onTextChange = onInputChange,
                onSend = onSend,
                placeholder = placeholder,
                isSending = state.isSending,
                canSend = (input.isNotBlank() || state.attachments.isNotEmpty()) && !state.isUploading,
                leadingContent = if (state.editing == null) {
                    { AttachImageButton(enabled = !state.isUploading, onPick = onAttach, onPaste = onPasteImage) }
                } else {
                    null
                },
            )
        }
        GroupComposerState.JoinToWrite -> ComposerNotice("このグループに参加すると書き込めます", action = "参加する", onAction = onJoin)
        GroupComposerState.Pending -> ComposerNotice("参加の承認待ちです", action = null, onAction = {})
        GroupComposerState.ReadOnly -> ComposerNotice(
            if (state.metadata?.isClosed == true) "招待制のグループです。招待リンクから参加してください" else "このグループは閲覧のみです",
            action = null,
            onAction = {},
        )
        GroupComposerState.NoSigner -> ComposerNotice("書き込むには署名できるアカウントでログインしてください", action = null, onAction = {})
        GroupComposerState.Checking -> ComposerNotice("参加状況を確認しています…", action = null, onAction = {})
    }
}

/** 送る前の添付の縮小画像。右上の × で外す。縮小画像が無ければ文字で出す。 */
@Composable
private fun AttachmentThumbnail(attachment: com.nostr.torinos.chat.MessageAttachment, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val preview = attachment.previewBytes
        if (preview != null) {
            com.nostr.torinos.ui.components.PreviewImage(
                data = preview,
                contentDescription = "添付する画像",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                maxDecodeSizePx = 256,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("🖼", modifier = Modifier.align(Alignment.Center))
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .clickable(onClickLabel = "添付を外す", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = "添付を外す", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * 画像の添付。投稿画面と同じく「写真から選択」と「クリップボードから貼り付け」を選ぶ。
 * Web はクリップボードの画像を読めず、ブラウザはタップ中にしかファイル選択を開かないので、すぐ写真を選ぶ。
 */
@Composable
private fun AttachImageButton(enabled: Boolean, onPick: () -> Unit, onPaste: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (com.nostr.torinos.crypto.isWebPlatform) onPick() else open = true },
            enabled = enabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.Image, contentDescription = "画像を添付")
        }
        com.nostr.torinos.ui.components.HeaderDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.nostr.torinos.ui.components.HeaderDropdownMenuItem(
                label = "写真から選択",
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onPick() },
            )
            com.nostr.torinos.ui.components.HeaderDropdownMenuItem(
                label = "クリップボードから貼り付け",
                leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onPaste() },
            )
        }
    }
}

@Composable
private fun ComposerNotice(text: String, action: String?, onAction: () -> Unit) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun NewPostDialog(isSending: Boolean, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しい投稿") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("タイトル") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("本文") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(title, body) }, enabled = (title.isNotBlank() || body.isNotBlank()) && !isSending) {
                if (isSending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("投稿")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}


/** グループ情報の編集（名前・説明、Buzz では公開範囲も）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditDialog(
    sheet: GroupEditSheet,
    onChange: ((com.nostr.torinos.chat.GroupEdit) -> com.nostr.torinos.chat.GroupEdit) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val edit = sheet.edit
    val wasPrivate = sheet.current.isPrivate
    val private = edit.visibility == com.nostr.torinos.chat.NewGroupVisibility.Private
    val enabled = !sheet.isSaving
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (enabled) onDismiss() },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            com.nostr.torinos.ui.components.SheetHeader(
                title = "グループを編集",
                onCancel = onDismiss,
                onConfirm = onSave,
                cancelEnabled = enabled,
                confirmEnabled = sheet.canSave,
                isConfirming = sheet.isSaving,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 見出し: アイコンと、いま編集中の名前。
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    GroupIcon(sheet.current.picture, locked = private, size = 64, forum = sheet.current.layout == com.nostr.torinos.chat.GroupLayout.Forum)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        com.nostr.torinos.chat.canonicalGroupName(edit.name).ifBlank { sheet.current.displayName },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                EditSectionTitle("基本情報")
                val nameError = com.nostr.torinos.chat.groupNameError(edit.name)
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = { text -> onChange { it.copy(name = text.replace("\n", "")) } },
                    label = { Text("名前") },
                    leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                    singleLine = true,
                    enabled = enabled,
                    isError = nameError != null,
                    supportingText = when (nameError) {
                        com.nostr.torinos.chat.GroupNameError.Empty -> { { Text("名前を入れてください") } }
                        com.nostr.torinos.chat.GroupNameError.TooLong -> { { Text("名前は255文字までです") } }
                        null -> null
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.about,
                    onValueChange = { text -> onChange { it.copy(about = text) } },
                    label = { Text("説明（任意）") },
                    placeholder = { Text("どんな話をする場所か") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (sheet.canChangeVisibility) {
                    EditSectionTitle("公開範囲")
                    EditOptionCard(
                        icon = Icons.Outlined.Public,
                        title = "公開",
                        description = "コミュニティの誰でも見つけて、読んで、参加できます",
                        selected = !private,
                        enabled = enabled,
                        onClick = { onChange { it.copy(visibility = com.nostr.torinos.chat.NewGroupVisibility.Open) } },
                    )
                    EditOptionCard(
                        icon = Icons.Outlined.Lock,
                        title = "非公開",
                        description = "メンバーだけが読み書きできます。参加には招待が必要です",
                        selected = private,
                        enabled = enabled,
                        onClick = { onChange { it.copy(visibility = com.nostr.torinos.chat.NewGroupVisibility.Private) } },
                    )
                    if (wasPrivate && !private) {
                        EditNotice("公開にすると、コミュニティの誰でもこれまでの発言を読めるようになります", warning = true)
                    }

                    EditSectionTitle("参加した人の役割")
                    EditOptionCard(
                        icon = Icons.Outlined.Person,
                        title = "メンバー",
                        description = "参加するとすぐに書き込めます",
                        selected = edit.joinRole == com.nostr.torinos.chat.GroupJoinRole.Member,
                        enabled = enabled,
                        onClick = { onChange { it.copy(joinRole = com.nostr.torinos.chat.GroupJoinRole.Member) } },
                    )
                    EditOptionCard(
                        icon = Icons.Outlined.Campaign,
                        title = "ゲスト（お知らせ向け）",
                        description = "参加した人は読むだけです。書けるのは管理者が加えたメンバーだけになります",
                        selected = edit.joinRole == com.nostr.torinos.chat.GroupJoinRole.Guest,
                        enabled = enabled,
                        onClick = { onChange { it.copy(joinRole = com.nostr.torinos.chat.GroupJoinRole.Guest) } },
                    )
                    if (edit.joinRole != groupEditOf(sheet.current).joinRole) {
                        EditNotice("今のメンバーの役割は変わりません", warning = false)
                    }

                    if (sheet.canSetUnlisted && !private) {
                        EditSectionTitle("見つけやすさ")
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable(enabled = enabled) { onChange { it.copy(unlisted = !it.unlisted) } }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                EditOptionIcon(Icons.Outlined.VisibilityOff, highlighted = edit.unlisted)
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("一覧に出さない", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        "リンクを知っている人だけが見つけて参加できます。読める人を制限するものではありません",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                androidx.compose.material3.Switch(
                                    checked = edit.unlisted,
                                    onCheckedChange = { checked -> onChange { it.copy(unlisted = checked) } },
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                }
                sheet.error?.let { EditNotice(it, warning = true) }
            }
        }
    }
}

@Composable
private fun EditSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EditOptionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 選択肢のカード。選ばれているものは枠と背景を強め、右にチェックを出す。 */
@Composable
private fun EditOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditOptionIcon(icon, highlighted = selected)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "選択中", tint = colors.primary)
            }
        }
    }
}

/** 注意や補足の帯。 */
@Composable
private fun EditNotice(text: String, warning: Boolean) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (warning) colors.errorContainer else colors.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (warning) colors.onErrorContainer else colors.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (warning) colors.onErrorContainer else colors.onSecondaryContainer)
        }
    }
}

/** グループの情報（見出しを押して開く）。説明、リレー、メンバーを出す。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoSheet(
    state: GroupChatUiState,
    title: String,
    ownPubkey: String?,
    shareLink: String?,
    onCopyLink: (String) -> Unit,
    onEdit: () -> Unit,
    onUserClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onChangeRole: (String, ChannelRole) -> Unit = { _, _ -> },
    onRemoveMember: (String, String) -> Unit = { _, _ -> },
    onAddMembers: () -> Unit = {},
    onDismissMemberError: () -> Unit = {},
) {
    val metadata = state.metadata
    val isDirect = metadata?.layout == com.nostr.torinos.chat.GroupLayout.Direct
    // DM は参加者、それ以外はメンバー一覧。管理者を先に、自分を最後に並べる。
    val members = remember(state.members, state.admins, metadata?.participants, ownPubkey) { memberListOrder(state, ownPubkey) }
    val profiles by remember(members) {
        if (members.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(members.toSet())
    }.collectAsState(initial = emptyMap())
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GroupIcon(metadata?.picture, locked = !isDirect && metadata?.isPrivate == true, size = 72, channel = !isDirect, forum = metadata?.layout == com.nostr.torinos.chat.GroupLayout.Forum)
                    Spacer(Modifier.height(12.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.relayName ?: state.ref.relayUrl.substringAfter("://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val flags = buildList {
                        if (isDirect) add("DM")
                        if (metadata?.isPrivate == true) add("メンバーだけが読める")
                        if (metadata?.isRestricted == true) add("メンバーだけが書ける")
                        if (metadata?.isClosed == true) add("招待制")
                        if (metadata?.isArchived == true) add("アーカイブ済み")
                        if (metadata?.isUnlisted == true) add("一覧に出さない")
                        if (metadata?.joinRole != null && metadata.joinRole != "member" && !metadata.isPrivate) add("参加するとゲスト")
                    }
                    if (flags.isNotEmpty()) {
                        Text(flags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    metadata?.about?.takeIf { it.isNotBlank() }?.let { about ->
                        Spacer(Modifier.height(12.dp))
                        LinkedText(text = about, style = MaterialTheme.typography.bodyMedium, onProfileClick = onUserClick, modifier = Modifier.fillMaxWidth())
                    }
                    if (shareLink != null || state.canEdit) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (shareLink != null) {
                                TextButton(onClick = { onCopyLink(shareLink) }) {
                                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("リンクをコピー")
                                }
                            }
                            if (state.canEdit) {
                                TextButton(onClick = onEdit) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("編集")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            members.isNotEmpty() -> "メンバー ${members.size}人"
                            else -> "メンバー"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    if (state.authority.canAdd) {
                        TextButton(onClick = onAddMembers) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("追加")
                        }
                    }
                }
                state.memberError?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissMemberError) { Icon(Icons.Default.Close, contentDescription = "閉じる", modifier = Modifier.size(18.dp)) }
                    }
                }
                if (members.isEmpty()) {
                    Text(
                        "このリレーはメンバーの一覧を公開していません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            items(members, key = { it }) { pubkey ->
                val profile = profiles[pubkey]
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onUserClick(pubkey) }.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val presence = state.presence[pubkey]
                    Box {
                        ChatAvatar(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 36)
                        presence?.let { PresenceBadge(it, modifier = Modifier.align(Alignment.BottomEnd)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile?.bestName ?: pubkey.take(8),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (pubkey == ownPubkey) {
                            Spacer(Modifier.width(6.dp))
                            Text("（自分）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Buzz は 39002 の役割、標準の NIP-29 は 39001 の管理者。
                        val roleLabel = if (state.flavor == GroupFlavor.Buzz && state.roles.isNotEmpty()) {
                            com.nostr.torinos.chat.channelRoleLabel(state.roles[pubkey])
                        } else if (pubkey in state.admins) {
                            "管理者"
                        } else {
                            null
                        }
                        if (roleLabel != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(roleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (ownPubkey != null) {
                        MemberAdminMenu(
                            state = state,
                            ownPubkey = ownPubkey,
                            pubkey = pubkey,
                            name = profile?.bestName ?: pubkey.take(8),
                            onChangeRole = onChangeRole,
                            onRemove = onRemoveMember,
                        )
                    }
                }
            }
        }
    }
}

/** 取り返しのつかない管理の操作の確認。 */
internal sealed interface AdminConfirm {
    data class RemoveMember(val pubkey: String, val name: String) : AdminConfirm
    data object Archive : AdminConfirm
    data class DeleteChannel(val name: String) : AdminConfirm
    data class DeleteMessage(val message: GroupMessage, val authorName: String) : AdminConfirm
}

@Composable
private fun AdminConfirmDialog(confirm: AdminConfirm, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, body, action) = when (confirm) {
        is AdminConfirm.RemoveMember -> Triple("${confirm.name} をチャンネルから外しますか？", "外した人は、もう一度参加するか加えられるまで書き込めません。", "外す")
        AdminConfirm.Archive -> Triple("アーカイブしますか？", "一覧の「アーカイブ済み」に移り、新しい投稿ができなくなります。あとで解除できます。", "アーカイブ")
        is AdminConfirm.DeleteChannel -> Triple("チャンネル「${confirm.name}」を削除しますか？", "メッセージはすべて読めなくなり、元に戻せません。", "削除")
        is AdminConfirm.DeleteMessage -> Triple("${confirm.authorName} の発言を削除しますか？", "チャンネルの全員から見えなくなります。", "削除")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(action, color = if (confirm == AdminConfirm.Archive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** メンバーの行の「︙」（役割の変更と外す）。できることが無ければ何も出さない。 */
@Composable
private fun MemberAdminMenu(
    state: GroupChatUiState,
    ownPubkey: String,
    pubkey: String,
    name: String,
    onChangeRole: (String, ChannelRole) -> Unit,
    onRemove: (String, String) -> Unit,
) {
    val canChange = com.nostr.torinos.chat.canChangeRole(state.authority, state.roles, ownPubkey, pubkey)
    val canRemove = com.nostr.torinos.chat.canRemoveMember(state.authority, state.roles, ownPubkey, pubkey)
    if (!canChange && !canRemove) return
    if (pubkey in state.busyMembers) {
        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "$name の管理") }
        ActionDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canChange) {
                com.nostr.torinos.chat.roleChoicesFor(state.authority, state.roles.getValue(pubkey)).forEach { role ->
                    val label = when (role) {
                        ChannelRole.Admin -> "管理者にする"
                        ChannelRole.Guest -> "ゲストにする（読むだけ）"
                        else -> "メンバーにする"
                    }
                    ActionDropdownMenuItem(label, Icons.Outlined.AdminPanelSettings, onClick = { open = false; onChangeRole(pubkey, role) })
                }
            }
            if (canRemove) {
                if (canChange) ActionDropdownMenuDivider()
                ActionDropdownMenuItem("チャンネルから外す", Icons.Outlined.PersonRemove, destructive = true, onClick = { open = false; onRemove(pubkey, name) })
            }
        }
    }
}

/** チャンネルにメンバーを加えるシート（docs/buzz-admin-design.md 3.1）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddMembersSheet(
    sheet: AddMembersSheet,
    roles: List<ChannelRole>,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRole: (ChannelRole) -> Unit,
    onSubmit: () -> Unit,
) {
    val people = remember(sheet.candidates, sheet.picked, sheet.typedPubkey) {
        (sheet.candidates + sheet.picked + listOfNotNull(sheet.typedPubkey)).toSet()
    }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(initial = emptyMap())
    val shown = remember(sheet.candidates, sheet.picked, sheet.query, sheet.typedPubkey, profiles) {
        sheet.typedPubkey?.takeIf { it !in sheet.picked }?.let(::listOf)
            ?: filterDmCandidates(sheet.candidates, sheet.picked, sheet.query, nameOf = { profiles[it]?.bestName })
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("メンバーを追加", style = MaterialTheme.typography.titleMedium)
            if (sheet.picked.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sheet.picked.forEach { pubkey ->
                        androidx.compose.material3.InputChip(
                            selected = true,
                            onClick = { onToggle(pubkey) },
                            enabled = !sheet.isSubmitting,
                            label = { Text(profiles[pubkey]?.bestName ?: (pubkey.take(8) + "…"), maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "外す", modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = sheet.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名前、npub で探す") },
                singleLine = true,
                enabled = !sheet.isSubmitting,
            )
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                when {
                    sheet.isLoadingCandidates && shown.isEmpty() ->
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                    shown.isEmpty() -> Text(
                        if (sheet.query.isBlank()) "候補がありません。npub を貼り付けて指定できます" else "見つかりません。npub を貼り付けて指定できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                shown.forEach { pubkey ->
                    val profile = profiles[pubkey]
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !sheet.isSubmitting) { onToggle(pubkey) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 32)
                        Spacer(Modifier.width(10.dp))
                        Text(profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text("役割", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roles.map { it to (com.nostr.torinos.chat.channelRoleLabel(it) ?: "メンバー") }.forEach { (role, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = sheet.role == role,
                        onClick = { onRole(role) },
                        label = { Text(label) },
                        enabled = !sheet.isSubmitting,
                    )
                }
            }
            Text(
                "コミュニティのメンバーをこのチャンネルに加えます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss, enabled = !sheet.isSubmitting) { Text("キャンセル") }
                Button(onClick = onSubmit, enabled = sheet.canSubmit) {
                    if (sheet.isSubmitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("追加")
                }
            }
        }
    }
}

/**
 * リアクションのチップ。タップで付け外し（フィードと同じ）、最後の［＋］で長押しメニューと同じ並びを出す。
 * 送っている間は付け外しを止める。長押しでリアクションした人を出す（自分の絵文字セットの絵文字はそこからセットを開ける）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupReactionChips(message: GroupMessage, canWrite: Boolean, actions: MessageRowActions) {
    val reactionEmojis = remember(message.reactions, actions.palette) {
        message.reactions.mapNotNull { r ->
            com.nostr.torinos.chat.reactionImageUrl(r, actions.palette)?.let { com.nostr.torinos.chat.groupReactionKey(r.content, r.emojiUrl) to it }
        }.toMap()
    }
    val reactionImages = rememberEmojiImages(reactionEmojis, actions.imageAuth, actions.imagesUnlocked)
    val busy = message.event.id in actions.reactingMessageIds
    val openCustomEmoji = com.nostr.torinos.ui.components.LocalCustomEmojiNavigator.current
    var quickMenuOpen by remember { mutableStateOf(false) }
    /** 長押しでリアクションした人を出しているチップ（[com.nostr.torinos.chat.groupReactionKey]）。 */
    var reactorsOpenFor by remember { mutableStateOf<String?>(null) }
    if (quickMenuOpen) {
        DisposableEffect(Unit) {
            actions.onReactionMenuChanged(true)
            onDispose { actions.onReactionMenuChanged(false) }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        message.reactions.forEach { reaction ->
            val chipKey = com.nostr.torinos.chat.groupReactionKey(reaction.content, reaction.emojiUrl)
            val image = reactionImages[chipKey]
            val option = reaction.toReactionOption(actions.palette)
            val custom = option as? ReactionOption.Custom
            val ownSet = custom?.let { actions.ownEmojiSetAddress(com.nostr.torinos.emoji.CustomEmoji(it.shortcode, it.imageUrl)) }
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (reaction.reactedByMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    // 書き込めないときも、長押しでリアクションした人は見られるようにする。
                    modifier = Modifier.combinedClickable(
                        onClick = { if (canWrite && !busy) actions.onReact(message, option) },
                        onLongClick = { reactorsOpenFor = chipKey },
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        if (image != null) {
                            com.nostr.torinos.ui.components.NetworkImage(
                                url = image.url,
                                contentDescription = reaction.content,
                                contentScale = ContentScale.Fit,
                                maxDecodeSizePx = 96,
                                headers = image.headers,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = when {
                                image != null -> "${reaction.count}"
                                reaction.content == "+" -> "👍 ${reaction.count}"
                                else -> "${reaction.content} ${reaction.count}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (reactorsOpenFor == chipKey) {
                    ReactorsMenu(
                        reactors = reaction.reactors,
                        onDismiss = { reactorsOpenFor = null },
                        onUserClick = { pubkey -> reactorsOpenFor = null; actions.onUserClick(pubkey) },
                        onNeedProfiles = actions.onNeedProfiles,
                        onOpenEmojiSet = ownSet?.let { set ->
                            {
                                reactorsOpenFor = null
                                openCustomEmoji(com.nostr.torinos.ui.components.CustomEmojiOpenRequest.of(custom.shortcode, custom.imageUrl, set))
                            }
                        },
                    )
                }
            }
        }
        if (canWrite) {
            // 高さは同じ行のリアクションのチップに合わせる（チップは絵文字の画像や文字で高さが変わる）。
            // 自身の高さ（上下の余白はチップと同じ2dp）はチップより高くならないようにする。
            Box(modifier = Modifier.fillMaxRowHeight()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxHeight().combinedClickable(enabled = !busy, onClick = { quickMenuOpen = true }),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.AddReaction,
                            contentDescription = "リアクションを追加",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ActionDropdownMenu(expanded = quickMenuOpen, onDismissRequest = { quickMenuOpen = false }, modifier = Modifier) {
                    val selectedKeys = remember(message.reactions, actions.palette) {
                        message.reactions.filter { it.reactedByMe }.map { it.toReactionOption(actions.palette).key }.toSet()
                    }
                    QuickReactionContent(
                        options = actions.quickReactions,
                        selectedReactionKeys = selectedKeys,
                        onDismiss = { quickMenuOpen = false },
                        onSelect = { option -> quickMenuOpen = false; actions.onReact(message, option) },
                        onOpenStandardEmojiPicker = { quickMenuOpen = false; actions.onOpenEmojiPicker(message) },
                    )
                }
            }
        }
    }
}

/**
 * リアクションのチップの長押しで出す、そのリアクションをした人の一覧。タップでその人のプロフィールを開く。
 * 自分の絵文字セットの絵文字なら、先頭にセットを開く項目を出す（以前の長押しの動き）。
 */
@Composable
private fun ReactorsMenu(
    reactors: List<String>,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit,
    onNeedProfiles: (Set<String>) -> Unit,
    onOpenEmojiSet: (() -> Unit)?,
) {
    val pubkeys = remember(reactors) { reactors.toSet() }
    LaunchedEffect(pubkeys) { onNeedProfiles(pubkeys) }
    val profiles by remember(pubkeys) {
        if (pubkeys.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(pubkeys)
    }.collectAsState(initial = emptyMap())
    ActionDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (onOpenEmojiSet != null) {
            ActionDropdownMenuItem(label = "絵文字セットを開く", icon = Icons.Outlined.EmojiEmotions, onClick = onOpenEmojiSet)
            HorizontalDivider()
        }
        Text(
            "リアクションした人 ${reactors.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        // 多いときはこの中だけスクロールする（メニュー自体の高さは画面に収まる）。
        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            reactors.forEach { pubkey ->
                val profile = profiles[pubkey]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onUserClick(pubkey) }.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    ChatAvatar(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 28)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        profile?.bestName ?: (pubkey.take(8) + "…"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * コミュニティ（Buzz）の `/media/` の絵文字の画像に付けるヘッダー。[active] の間だけ署名を用意し、期限（55秒）の前に作り直す。
 * 用意できていない間は null を返し、その絵文字は `:shortcode:` の文字で出る。ほかの URL はそのまま読む。
 */
@Composable
private fun rememberCommunityEmojiHeaders(
    relayUrl: String,
    active: Boolean,
    imageAuth: suspend (String) -> ImageAuth,
    imagesUnlocked: Boolean,
): (String) -> Map<String, String>? {
    var headers by remember(relayUrl) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(relayUrl, active, imagesUnlocked) {
        if (!active) return@LaunchedEffect
        val probe = "https://" + relayUrl.substringAfter("://").substringBefore('/') + "/media/probe"
        while (true) {
            headers = when (val auth = imageAuth(probe)) {
                ImageAuth.NotNeeded -> emptyMap()
                is ImageAuth.Header -> mapOf("Authorization" to auth.authorization)
                ImageAuth.NeedsConsent, ImageAuth.Failed -> null
            }
            kotlinx.coroutines.delay(45_000)
        }
    }
    return remember(relayUrl, headers) {
        { url -> if (buzzMediaHost(url, setOf(relayUrl)) == null) emptyMap() else headers }
    }
}
