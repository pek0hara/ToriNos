package com.nostr.torinos.ui.chat

import com.nostr.torinos.ui.components.dismissKeyboardOnListTouch
import com.nostr.torinos.ui.components.keyboardAvoidingPadding
import com.nostr.torinos.ui.components.ActionDropdownMenu
import com.nostr.torinos.ui.components.ActionDropdownMenuItem
import com.nostr.torinos.ui.components.ActionDropdownMenuDivider
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Info
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
import com.nostr.torinos.ui.post.imagePasteMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.ContentPaste
import com.nostr.torinos.ui.components.AppMessageComposer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.ui.platform.LocalClipboard
import com.nostr.torinos.ui.settings.setPlainText
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.nostr.torinos.ui.components.BotBadge
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
    /**
     * スレッドの画面から、そのスレッドがあるチャンネル（フォーラム）へ移る。
     * 1つ前の画面がそのチャンネルなら戻るだけにする（同じチャンネルを積み重ねない）。
     */
    val onOpenParent: (GroupRef) -> Unit = onOpenGroup,
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
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    /** 取り返しのつかない管理の操作の確認（docs/buzz-admin-design.md 7章）。 */
    var confirm by remember { mutableStateOf<AdminConfirm?>(null) }
    var input by remember(ref, mode) { mutableStateOf("") }
    /** メンションの候補から選んだ人（Buzz）。送るときに本文に `@表示名` が残っている人だけを宛先にする。 */
    var mentionPicks by remember(ref, mode) { mutableStateOf(emptyList<com.nostr.torinos.chat.MentionCandidate>()) }
    /** メンションの候補を一度出したら、メンバー全員のプロフィールも見る（名前と画像を出すため）。 */
    var mentionMembersWanted by remember(ref, mode) { mutableStateOf(false) }
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
    val mentionMembers = remember(state.members, state.metadata, state.admins) { memberListOrder(state, viewModel.ownPubkey) }
    val authors = remember(state.messages, state.threadRoot, state.metadata?.participants, state.typingAuthors, state.flavor, mentionMembersWanted, mentionMembers) {
        // 投稿の下に出す最新の返信と、スレッドへの返信の枠の元の投稿も、名前とアイコンを出す。
        val shown = (state.messages + listOfNotNull(state.threadRoot)).flatMap { message ->
            listOfNotNull(message, message.latestThreadReply, message.threadNotice?.root)
        }
        // Buzz の本文の `@表示名` は、メンションされた人の名前と照らし合わせてリンクにする。
        val mentioned = if (state.flavor == GroupFlavor.Buzz) shown.flatMap { com.nostr.torinos.chat.buzzProfileTargets(it.event) } else emptyList()
        shown.map { it.author }.toSet() + state.metadata?.participants.orEmpty() + state.typingAuthors + mentioned +
            (if (mentionMembersWanted) mentionMembers else emptyList())
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
        onCopy = { copyScope.launch { clipboard.setPlainText(it.body) } },
        onCopyLink = { copyScope.launch { clipboard.setPlainText(viewModel.messageLink(it)) } },
        onEdit = { if (viewModel.canEdit(it)) viewModel.startEditing(it) },
        canEdit = viewModel::canEdit,
        onDelete = viewModel::delete,
        canAdminDelete = viewModel::canAdminDelete,
        onAdminDelete = { message -> confirm = AdminConfirm.DeleteMessage(message, profiles[message.author]?.bestName ?: message.author.take(8)) },
        onOpenThread = { navigation.onOpenThread(ref, it.event.id) },
        onUserClick = navigation.onUserClick,
        onNeedProfiles = viewModel::ensureProfiles,
        onBuzzLink = onBuzzLink,
        channelName = { id -> if (id == state.ref.groupId) state.metadata?.name else state.linkedChannelNames[id] },
        imageAuth = viewModel::imageAuthorization,
        onUnlockImages = viewModel::unlockImages,
        imagesUnlocked = state.imagesUnlocked,
        presence = state.presence,
    )

    // 役割が bot の人と、エージェントの発言（40002）を出した人。参加中のタイムラインの bot と同じ決め方。
    val botPubkeys = remember(state.roles, state.messages) {
        state.roles.filterValues { it == ChannelRole.Bot }.keys +
            state.messages.filter { it.event.kind == com.nostr.torinos.chat.BUZZ_AGENT_MESSAGE_KIND }.map { it.author }
    }
    CompositionLocalProvider(LocalEmojiImageHeaders provides emojiImageHeaders, LocalChatBots provides botPubkeys) {
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
                        // 見出しを押すとグループの情報（説明・メンバー）を出す。スレッドの画面では、そのチャンネルへ移る。
                        val opensParent = mode is GroupRoomMode.Thread && !isDirect
                        Column(
                            modifier = if (opensParent) {
                                Modifier.clickable(onClickLabel = "チャンネルを開く") { navigation.onOpenParent(ref) }
                            } else {
                                Modifier.clickable(onClickLabel = "チャンネルの情報") { infoOpen = true }
                            },
                        ) {
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
                                            opensParent -> state.title + " ›"
                                            isDirect -> "DM · " + (state.relayName ?: ref.relayUrl.substringAfter("://"))
                                            else -> listOfNotNull(
                                                state.members?.takeIf { it.isNotEmpty() }?.let { "${it.size}人" },
                                                state.relayName ?: ref.relayUrl.substringAfter("://"),
                                            ).joinToString(" · ")
                                        },
                                    )
                                },
                                // スレッドの画面のチャンネル名は押して移れるので、少し大きくしてリンクの色にする。
                                style = if (opensParent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                                color = if (opensParent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                // スレッドの画面では見出しがチャンネルへの移動になるので、グループの情報はメニューから開く。
                                if (mode is GroupRoomMode.Thread) {
                                    ActionDropdownMenuItem("チャンネルの情報", Icons.Outlined.Info, onClick = { menuOpen = false; infoOpen = true })
                                }
                                if (state.canEdit && mode !is GroupRoomMode.Thread) {
                                    ActionDropdownMenuItem("チャンネルを編集", Icons.Outlined.Edit, onClick = { menuOpen = false; viewModel.openEdit() })
                                }
                                if (link != null) {
                                    ActionDropdownMenuItem("チャンネルのリンクをコピー", Icons.Outlined.Link,
                                        onClick = { menuOpen = false; copyScope.launch { clipboard.setPlainText(link) } })
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
                                            if (state.isLeaving) "退出しています…" else "チャンネルから退出",
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
                    isLoading = state.isLoading,
                    isLoadingOlder = state.isLoadingOlder,
                    canLoadOlder = state.canLoadOlder && state.messages.isNotEmpty(),
                    onLoadOlder = viewModel::loadOlder,
                    canWrite = state.composer == GroupComposerState.Writable,
                    actions = rowActions,
                    // Buzz のチャンネルでは発言を押すとスレッド（返信）を開く。
                    onMessageClick = if (state.flavor == GroupFlavor.Buzz) {
                        { message -> navigation.onOpenThread(ref, message.openThreadRootId) }
                    } else {
                        null
                    },
                    sentEventId = state.sentEventId,
                    onSentShown = viewModel::consumeSent,
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
                    collapsedAuthors = state.collapsedAuthors,
                    sentEventId = state.sentEventId,
                    onSentShown = viewModel::consumeSent,
                    modifier = Modifier.weight(1f),
                )
                GroupRoomMode.Forum -> ForumList(
                    posts = state.messages,
                    profiles = profiles,
                    isLoadingOlder = state.isLoadingOlder,
                    canLoadOlder = state.canLoadOlder && state.messages.isNotEmpty(),
                    onLoadOlder = viewModel::loadOlder,
                    onOpen = { navigation.onOpenThread(ref, it.event.id) },
                    sentEventId = state.sentEventId,
                    onSentShown = viewModel::consumeSent,
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
                    onSend = {
                        val mentions = com.nostr.torinos.chat.mentionedPubkeys(input, mentionPicks)
                        viewModel.send(input, mentions = mentions) { input = ""; mentionPicks = emptyList() }
                    },
                    onCancelReply = { viewModel.setReplyTo(null); viewModel.startEditing(null); input = ""; mentionPicks = emptyList() },
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
                    mention = if (state.flavor == GroupFlavor.Buzz && state.editing == null) {
                        MentionSupport(
                            candidates = { query ->
                                com.nostr.torinos.chat.mentionCandidates(mentionMembers, { profiles[it]?.bestName }, query, viewModel.ownPubkey)
                            },
                            profiles = profiles,
                            onShown = {
                                if (!mentionMembersWanted) {
                                    mentionMembersWanted = true
                                    viewModel.ensureMemberProfiles()
                                }
                            },
                            onPicked = { picked -> mentionPicks = mentionPicks + picked },
                            picked = mentionPicks,
                        )
                    } else {
                        null
                    },
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
            onCopyLink = { copyScope.launch { clipboard.setPlainText(it) } },
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
    /** 本文の `buzz://channel/…` に出すチャンネル名（同じコミュニティのチャンネル ID → 名前）。 */
    val channelName: (String) -> String? = { null },
    val imageAuth: suspend (String) -> ImageAuth,
    val onUnlockImages: () -> Unit,
    val imagesUnlocked: Boolean,
    /** 在席が分かっている人（Buzz）。 */
    val presence: Map<String, com.nostr.torinos.chat.PresenceStatus> = emptyMap(),
)

/** 行の中の本文（[TimelinePost]）に渡す、画像とリンクの操作。 */
private fun MessageRowActions.toTimelineBodyActions() = TimelineBodyActions(
    imageAuth = imageAuth,
    imagesUnlocked = imagesUnlocked,
    onUnlockImages = onUnlockImages,
    onBuzzLink = { link, _ -> onBuzzLink(link) },
    onUserClick = onUserClick,
    channelName = { _, id -> channelName(id) },
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
                text = "メンバーだけが読めるチャンネルです",
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
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    onLoadOlder: () -> Unit,
    canWrite: Boolean,
    actions: MessageRowActions,
    onMessageClick: ((GroupMessage) -> Unit)?,
    sentEventId: String?,
    onSentShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 日付区切りと、直前の発言にまとめて表示するかを、一覧が変わったときに1回だけ計算する。下から積むので新しい順にする。
    val rows = remember(messages) { messageTimeline(messages).asReversed() }
    val today = rememberToday()
    LoadOlderEffect(listState, itemCount = rows.size, canLoadOlder = canLoadOlder, onLoadOlder = onLoadOlder)
    ScrollToSentPost(listState, sentEventId, onSentShown) { id ->
        rows.indexOfFirst { it is ChatTimelineItem.Message && it.message.event.id == id }
    }
    // 最初の答え（EOSE）を待つ間は「まだメッセージがありません」を出さず、発言の形を出す。
    if (messages.isEmpty() && isLoading) {
        ChatMessageSkeleton(modifier = modifier.dismissKeyboardOnListTouch())
        return
    }
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
                is ChatTimelineItem.Message -> if (item.message.threadNotice != null) {
                    ThreadNoticeRow(
                        message = item.message,
                        profiles = profiles,
                        flavor = flavor,
                        actions = actions,
                        onClick = { onMessageClick?.invoke(item.message) },
                    )
                } else MessageRow(
                    message = item.message,
                    profile = profiles[item.message.author],
                    replyProfile = item.message.replyTo?.let { profiles[it.author] },
                    grouped = item.grouped,
                    isOwn = item.message.event.pubkey == ownPubkey,
                    canWrite = canWrite,
                    flavor = flavor,
                    actions = actions,
                    onClick = onMessageClick?.let { open -> { open(item.message) } },
                    profiles = profiles,
                    collapse = BodyCollapse.Channel,
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
        // スレッドへの返信の行や、返信の付いた発言（スレッドの先頭）は、前後の同じ人の発言とまとめない
        // （返信のプレビューを挟むので、アイコンと名前を出し直さないと誰の発言か分かりにくい）。
        author = { if (it.threadNotice != null || it.threadReplyCount > 0) "thread:${it.event.id}" else it.author },
        createdAt = { it.event.createdAt },
        startsNewGroup = { it.replyTo != null || it.replyToId != null || it.sentFromThreadLabel != null || it.threadNotice != null },
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

/**
 * 送ったばかりの投稿（[sentId]）が一覧に入ったら、その行が見えるところまでスクロールする。
 * 一覧を上の方まで読んでいても、送った投稿を確かめられるように。入らないまま時間が過ぎたらあきらめる。
 */
@Composable
private fun ScrollToSentPost(listState: LazyListState, sentId: String?, onShown: () -> Unit, indexOf: (String) -> Int) {
    val currentIndexOf by rememberUpdatedState(indexOf)
    val shown by rememberUpdatedState(onShown)
    LaunchedEffect(sentId) {
        val id = sentId ?: return@LaunchedEffect
        val index = kotlinx.coroutines.withTimeoutOrNull(3_000) { snapshotFlow { currentIndexOf(id) }.first { it >= 0 } }
        if (index != null) listState.animateScrollToItem(index)
        shown()
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

/**
 * 開いたときは一番下（最新の返信）を見せる。返信や画像が後から届いて下に伸びた分も、
 * 利用者が一覧を指で動かすまでは一番下へ追う。
 *
 * 配置ごとの [LazyListState.layoutInfo] を見て、まだ下へ進めるなら最後の行の末尾へ移る。
 * 行を足した直後の scrollToItem は前の行数で丸められるが、次の配置でまた進めるので最後には一番下に着く。
 */
@Composable
private fun FollowBottomUntilDragged(listState: LazyListState) {
    var follow by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .first { it is androidx.compose.foundation.interaction.DragInteraction.Start }
        follow = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                if (follow && listState.canScrollForward && info.totalItemsCount > 0) {
                    listState.scrollUnlessPreempted { scrollToItem(info.totalItemsCount - 1, Int.MAX_VALUE) }
                }
            }
    }
}

/**
 * チャンネルの画面の「スレッドへの返信」の投稿。最新の返信を送った時刻の位置に、元の投稿をそのまま出し、
 * その下に最新の返信を「╰」を付けて字下げして出す（タイムラインタブの会話の行と同じ形）。
 * 押すとスレッドを開く。リアクションなどの操作はスレッドの画面で行う。
 */
@Composable
private fun ThreadNoticeRow(
    message: GroupMessage,
    profiles: Map<String, NostrProfile>,
    flavor: GroupFlavor,
    actions: MessageRowActions,
    onClick: () -> Unit,
) {
    val notice = message.threadNotice ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyActions = remember(actions) { actions.toTimelineBodyActions() }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .clickable(onClickLabel = "スレッドを開く", onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("スレッドに新しい返信", style = MaterialTheme.typography.labelMedium, color = muted)
            }
            val root = notice.root
            if (root != null) {
                TimelinePost(root, flavor, "", profiles, bodyActions, avatarSize = 40, collapse = BodyCollapse.Channel)
            } else {
                Text("元の投稿を読み込み中…", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(start = 48.dp))
            }
            ThreadReplySummary(count = notice.count, onOpen = null, modifier = Modifier.padding(start = 48.dp)) {
                TimelinePost(message, flavor, "", profiles, bodyActions, avatarSize = 28, collapse = BodyCollapse.ReplyPreview)
            }
        }
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
    collapsedAuthors: Set<String>,
    sentEventId: String?,
    onSentShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = rememberToday()
    val rows = remember(replies) { messageTimeline(replies) }
    // 押して開いたミュート中の人の発言。
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    fun isCollapsed(message: GroupMessage) = message.author in collapsedAuthors && message.event.id !in expanded
    fun expand(message: GroupMessage) { expanded = expanded + message.event.id }
    val listState = rememberLazyListState()
    KeepBottomOnResize(listState)
    FollowBottomUntilDragged(listState)
    // 一覧の先頭は元の発言（読み込み中はくるくる）の1行。
    ScrollToSentPost(listState, sentEventId, onSentShown) { id ->
        rows.indexOfFirst { it is ChatTimelineItem.Message && it.message.event.id == id }.let { if (it < 0) -1 else it + 1 }
    }
    // 元の発言も返信もまだ届いていない間は、くるくるの代わりに発言の形を出す。
    if (root == null && replies.isEmpty()) {
        ChatMessageSkeleton(modifier = modifier.dismissKeyboardOnListTouch(), alignBottom = false)
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().dismissKeyboardOnListTouch(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (root != null) {
            item(key = "root") {
                val rootTime = chatDayLabel(chatLocalDate(root.event.createdAt), today) + " " + chatClockTime(root.event.createdAt)
                MessageRow(
                    root, profiles[root.author], null, grouped = false, isOwn = root.event.pubkey == ownPubkey, canWrite = canWrite, flavor = flavor, actions = actions,
                    showThreadLink = false, timeLabel = rootTime, profiles = profiles,
                    onExpandMuted = if (isCollapsed(root)) ({ expand(root) }) else null,
                )
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
                    profiles = profiles,
                    onExpandMuted = if (isCollapsed(item.message)) ({ expand(item.message) }) else null,
                )
            }
        }
    }
}

/** ミュート中の人の発言の本文の代わりに出す。押すと本文を開く。 */
@Composable
private fun MutedMessageBody(onExpand: () -> Unit) {
    Text(
        "ミュートしています",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .clickable(onClickLabel = "表示する", onClick = onExpand)
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun ForumList(
    posts: List<GroupMessage>,
    profiles: Map<String, NostrProfile>,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    onLoadOlder: () -> Unit,
    onOpen: (GroupMessage) -> Unit,
    sentEventId: String?,
    onSentShown: () -> Unit,
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
    ScrollToSentPost(listState, sentEventId, onSentShown) { id -> posts.indexOfFirst { it.event.id == id } }
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
    /** 発言を押したとき（Buzz のチャンネルではスレッドを開く）。null なら何もしない。 */
    onClick: (() -> Unit)? = null,
    /** 本文のメンションの名前に使う。 */
    profiles: Map<String, NostrProfile> = emptyMap(),
    /** ミュート中の人の発言を折りたたんでいるなら、開く操作。本文の代わりに「ミュートしています」を出す。 */
    onExpandMuted: (() -> Unit)? = null,
    /** 長い本文を畳む基準（チャンネルの画面は短め）。 */
    collapse: BodyCollapse = BodyCollapse.Feed,
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
                .combinedClickable(onClickLabel = if (onClick != null) "スレッドを開く" else null, onClick = { onClick?.invoke() }, onLongClick = { if (onExpandMuted == null) menuOpen = true })
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
                        if (isChatBot(message, profile)) BotBadge(Modifier.padding(start = 4.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onExpandMuted != null) {
                    MutedMessageBody(onExpand = onExpandMuted)
                    return@Column
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
                        collapsible = true,
                        collapse = collapse,
                        mentionNames = rememberMentionNames(message, profiles),
                        onMentionClick = actions.onUserClick,
                        profileName = { profiles[it]?.bestName },
                        channelName = actions.channelName,
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
                // 返信がすぐ付いたスレッドは、最新の返信をここに出す（離れていればチャンネルの下の方に別の枠で出る）。
                val latestReply = message.latestThreadReply
                if (showThreadLink && (latestReply != null || message.threadReplyCount > 0)) {
                    val bodyActions = remember(actions) { actions.toTimelineBodyActions() }
                    ThreadReplySummary(
                        count = message.threadReplyCount,
                        onOpen = { actions.onOpenThread(message) },
                        // プレビューを長押ししても、発言のメニューを開く。
                        onLongClick = { if (onExpandMuted == null) menuOpen = true },
                        modifier = Modifier.padding(top = if (latestReply != null) 6.dp else 0.dp),
                        preview = latestReply?.let { reply ->
                            { TimelinePost(reply, flavor, "", profiles, bodyActions, avatarSize = 28, collapse = BodyCollapse.ReplyPreview) }
                        },
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
    mention: MentionSupport? = null,
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
                aboveInput = mention?.let { support ->
                    { value, replace -> MentionSuggestions(support, value, replace) }
                },
                visualTransformation = mention?.picked?.takeIf { it.isNotEmpty() }?.let { picked ->
                    val color = MaterialTheme.colorScheme.primary
                    remember(picked, color) { MentionHighlight(picked, color) }
                } ?: androidx.compose.ui.text.input.VisualTransformation.None,
                // 長押しメニューからも画像を貼れるようにする（＋ボタンの「貼り付け」と同じ。編集中は添付できない）。
                textFieldModifier = Modifier.imagePasteMenuItem(
                    enabled = state.editing == null && !state.isUploading,
                    onPasteImage = onPasteImage,
                ),
            )
        }
        GroupComposerState.JoinToWrite -> ComposerNotice("このチャンネルに参加すると書き込めます", action = "参加する", onAction = onJoin)
        GroupComposerState.Pending -> ComposerNotice("参加の承認待ちです", action = null, onAction = {})
        GroupComposerState.ReadOnly -> ComposerNotice(
            if (state.metadata?.isClosed == true) "招待制のチャンネルです。招待リンクから参加してください" else "このチャンネルは閲覧のみです",
            action = null,
            onAction = {},
        )
        GroupComposerState.NoSigner -> ComposerNotice("書き込むには署名できるアカウントでログインしてください", action = null, onAction = {})
        GroupComposerState.Checking -> ComposerNotice("参加状況を確認しています…", action = null, onAction = {})
    }
}

/** Buzz のメンションの候補を出すための材料。 */
private class MentionSupport(
    val candidates: (query: String) -> List<com.nostr.torinos.chat.MentionCandidate>,
    val profiles: Map<String, NostrProfile>,
    /** 候補を出した。メンバーのプロフィールを読み始める。 */
    val onShown: () -> Unit,
    val onPicked: (com.nostr.torinos.chat.MentionCandidate) -> Unit,
    /** 選んだ人。入力欄の `@表示名` に色を付ける。 */
    val picked: List<com.nostr.torinos.chat.MentionCandidate> = emptyList(),
)

/** 選んだ人の `@表示名` を、本文のメンションと同じ色で示す。文字は変えないので位置の対応はそのまま。 */
private class MentionHighlight(
    private val picked: List<com.nostr.torinos.chat.MentionCandidate>,
    private val color: androidx.compose.ui.graphics.Color,
) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val ranges = com.nostr.torinos.chat.pickedMentionRanges(text.text, picked)
        if (ranges.isEmpty()) return androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        val styled = androidx.compose.ui.text.AnnotatedString.Builder(text).apply {
            ranges.forEach { addStyle(androidx.compose.ui.text.SpanStyle(color = color), it.first, it.last + 1) }
        }.toAnnotatedString()
        return androidx.compose.ui.text.input.TransformedText(styled, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is MentionHighlight && other.picked == picked && other.color == color
    override fun hashCode(): Int = picked.hashCode() * 31 + color.hashCode()
}

/** カーソルの直前が `@…` のとき、入力欄の上にメンバーの候補を出す。選ぶと `@表示名 ` に置き換える。 */
@Composable
private fun MentionSuggestions(
    support: MentionSupport,
    value: androidx.compose.ui.text.input.TextFieldValue,
    replace: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
) {
    val query = if (value.selection.collapsed) com.nostr.torinos.chat.activeMentionQuery(value.text, value.selection.start) else null
    if (query == null) return
    LaunchedEffect(Unit) { support.onShown() }
    val candidates = support.candidates(query.query)
    if (candidates.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            items(candidates, key = { it.pubkey }) { candidate ->
                val profile = support.profiles[candidate.pubkey]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "${candidate.name} をメンション") {
                            val inserted = com.nostr.torinos.chat.insertMention(value.text, query, value.selection.start, candidate.name)
                            support.onPicked(candidate)
                            replace(
                                androidx.compose.ui.text.input.TextFieldValue(
                                    inserted.text,
                                    androidx.compose.ui.text.TextRange(inserted.cursor),
                                ),
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ChatAvatar(pubkey = candidate.pubkey, name = candidate.name, pictureUrl = profile?.picture, size = 32)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(candidate.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val handle = profile?.name?.takeIf { it.isNotBlank() && it != candidate.name }
                        if (handle != null) {
                            Text(
                                "@$handle",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
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
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(Icons.Default.Add, contentDescription = "画像を添付")
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
                title = "チャンネルを編集",
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
                    com.nostr.torinos.ui.components.ReactorsMenu(
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
