package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.BUZZ_AGENT_MESSAGE_KIND
import com.nostr.torinos.chat.CommunityAction
import com.nostr.torinos.chat.CommunityMember
import com.nostr.torinos.chat.CommunityRole
import com.nostr.torinos.chat.CommunityRosterLoad
import com.nostr.torinos.chat.InviteMintResult
import com.nostr.torinos.chat.InviteRequest
import com.nostr.torinos.chat.MintedInvite
import com.nostr.torinos.chat.adminRejectionMessage
import com.nostr.torinos.chat.allowedCommunityActions
import com.nostr.torinos.chat.buildCommunityRemove
import com.nostr.torinos.chat.buildCommunityRole
import com.nostr.torinos.chat.managesCommunity
import com.nostr.torinos.chat.BUZZ_FORUM_POST_KIND
import com.nostr.torinos.chat.BuzzJoinPolicy
import com.nostr.torinos.chat.CachedChatRow
import com.nostr.torinos.chat.ChatListCache
import com.nostr.torinos.chat.ChatListSnapshot
import com.nostr.torinos.chat.BuzzJoinResult
import com.nostr.torinos.chat.BuzzMembershipCheck
import com.nostr.torinos.chat.BUZZ_DM_VISIBILITY_KIND
import com.nostr.torinos.chat.BuzzDmOutcome
import com.nostr.torinos.chat.CreateRejection
import com.nostr.torinos.chat.GroupCreateResult
import com.nostr.torinos.chat.MAX_DM_OTHERS
import com.nostr.torinos.chat.NewGroupSpec
import com.nostr.torinos.chat.NewGroupType
import com.nostr.torinos.chat.NewGroupVisibility
import com.nostr.torinos.chat.PendingCreation
import com.nostr.torinos.chat.canonicalGroupName
import com.nostr.torinos.chat.groupNameError
import com.nostr.torinos.chat.hiddenDmIds
import com.nostr.torinos.chat.newBuzzChannelId
import com.nostr.torinos.chat.newStandardGroupId
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.chat.ChatAccount
import com.nostr.torinos.chat.UNREAD_BADGE_MAX
import com.nostr.torinos.chat.countUnreadMessages
import com.nostr.torinos.chat.groupMessageAuthor
import com.nostr.torinos.chat.GROUP_CHAT_KIND
import com.nostr.torinos.chat.GROUP_MEMBERS_KIND
import com.nostr.torinos.chat.GROUP_METADATA_KIND
import com.nostr.torinos.chat.GROUP_THREAD_KIND
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.chat.GroupInput
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupMetadata
import com.nostr.torinos.chat.GroupPublishOutcome
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.JoinedGroup
import com.nostr.torinos.chat.buildGroupJoinRequest
import com.nostr.torinos.chat.classifyJoinRejection
import com.nostr.torinos.chat.JoinRejection
import com.nostr.torinos.chat.groupFlavorOf
import com.nostr.torinos.chat.groupMessageBody
import com.nostr.torinos.chat.groupPostTitle
import com.nostr.torinos.chat.latestGroupMetadata
import com.nostr.torinos.chat.parseGroupInput
import com.nostr.torinos.chat.PublicGroupEntry
import com.nostr.torinos.chat.publicGroupEntries
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.network.RelayInformation
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.fetchFiniteEvents
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** 一覧の切り替え。 */
sealed interface ChatSelection {
    data object All : ChatSelection
    data class Relay(val relayUrl: String) : ChatSelection
    /** NIP-17 の DM（グループがある人がヘッダーのアイコンで選ぶ、docs/dm-feature-design.md §8.4.6）。 */
    data object Direct : ChatSelection
}

data class ChatGroupRow(
    val ref: GroupRef,
    val metadata: GroupMetadata?,
    val joined: Boolean,
    val pending: Boolean,
    val lastMessage: NostrEvent?,
    val flavor: GroupFlavor = GroupFlavor.Standard,
    /** 最後に開いた時刻より新しい発言がある。 */
    val unread: Boolean = false,
    /** 未読の件数（自分以外の発言）。数えられていなければ null で、点だけ出す。 */
    val unreadCount: Int? = null,
    /** 最新の発言の送信者（Buzz でリレーが署名したものは本人）。 */
    val lastAuthor: String? = null,
    /** Buzz で最新の発言が編集されていれば、その本文。 */
    val editedBody: String? = null,
    /** 参加中の一覧（kind 10009）に保存された名前。グループ情報を読めないときの見出しに使う。 */
    val listedName: String? = null,
    /** [lastMessage] が Buzz のスレッドの返信のときの、スレッドのルート（読めていれば）。 */
    val threadRoot: NostrEvent? = null,
    val threadRootEditedBody: String? = null,
    val threadRootAuthor: String? = null,
) {
    val title: String
        get() = metadata?.displayName?.takeIf { metadata.name.isNotBlank() }
            ?: listedName?.takeIf { it.isNotBlank() }
            ?: shortGroupId(ref.groupId)

    /** グループ情報も発言も読めていない（リレーが NIP-29 に対応していない、グループが無くなった など）。 */
    val isUnresolved: Boolean get() = metadata == null && lastMessage == null && !pending
    val layout: GroupLayout get() = metadata?.layout ?: GroupLayout.Chat
    /** Buzz でアーカイブ済みのチャンネル。一覧では末尾の「アーカイブ済み」にまとめる。 */
    val isArchived: Boolean get() = metadata?.isArchived == true
    val lastActivity: Long get() = lastMessage?.createdAt ?: 0L
    val preview: String? get() = lastMessage?.let { previewOf(it, editedBody) }

    /** スレッドの返信なら、その上に出すルートの1行（Google Chat のように「元の投稿 + 返信」で出す）。 */
    val threadRootPreview: String? get() = threadRoot?.let { previewOf(it, threadRootEditedBody) }

    private fun previewOf(event: NostrEvent, edited: String?): String? = when (event.kind) {
        BUZZ_FORUM_POST_KIND, GROUP_THREAD_KIND -> groupPostTitle(event)
        else -> {
            val body = edited ?: groupMessageBody(event)
            // Buzz の本文は Markdown なので、記号や画像の書式を外して読める1行にする。
            if (flavor == GroupFlavor.Buzz) {
                com.nostr.torinos.chat.chatListPreview(body)
            } else {
                body.lineSequence().firstOrNull { it.isNotBlank() }
            }
        }
    }
}

data class ChatRelaySummary(
    val relayUrl: String,
    val name: String?,
    val flavor: GroupFlavor = GroupFlavor.Standard,
    /** NIP-11 の icon（Buzz は data URL でコミュニティの絵が入っている）。 */
    val icon: String? = null,
) {
    val host: String get() = relayUrl.substringAfter("://").trimEnd('/')
    // Buzz の NIP-11 の name はどのコミュニティも「Buzz Relay」なので、公式アプリと同じく
    // アドレスの先頭（png.communities.buzz.xyz の png）をコミュニティ名にする。
    val label: String get() = (if (flavor == GroupFlavor.Buzz) buzzCommunityName(host) else null)
        ?: name?.takeIf { it.isNotBlank() }
        ?: host
    val iconUrl: String? get() = com.nostr.torinos.ui.relay.relayIconUrl(relayUrl, icon)
}

/** Buzz コミュニティのアドレスの先頭のラベル。サブドメインが無ければ null。 */
internal fun buzzCommunityName(host: String): String? {
    val labels = host.substringBefore('/').substringBefore(':').split('.')
    return labels.first().takeIf { labels.size >= 3 && it.isNotBlank() }
}

/** 長い ID（64桁の16進など）は先頭だけを見出しにする。 */
internal fun shortGroupId(groupId: String): String = if (groupId.length > 16) groupId.take(8) + "…" else groupId

/** 「すべて」の一覧の、リレー・コミュニティごとのまとまり。 */
data class ChatRowSection(val relayUrl: String, val rows: List<ChatGroupRow>)

/**
 * 新しい順に並んだ行を、リレー・コミュニティごとにまとめる。まとまりは最新の発言が新しい順、
 * 中の行は元の順のまま。
 */
/** アーカイブ済みでない行とアーカイブ済みの行に分ける（どちらも元の順を保つ）。 */
internal fun splitArchivedRows(rows: List<ChatGroupRow>): Pair<List<ChatGroupRow>, List<ChatGroupRow>> =
    rows.partition { !it.isArchived }

internal fun groupRowsByRelay(rows: List<ChatGroupRow>): List<ChatRowSection> =
    rows.groupBy { it.ref.relayUrl }.map { (relayUrl, group) -> ChatRowSection(relayUrl, group) }

/** 参加シートの状態。 */
sealed interface JoinSheetState {
    data object Closed : JoinSheetState
    data class Input(val text: String = "", val error: String? = null, val isResolving: Boolean = false) : JoinSheetState
    data class Confirm(
        val ref: GroupRef,
        val inviteCode: String?,
        val metadata: GroupMetadata?,
        val relayName: String?,
        val isJoining: Boolean = false,
        val error: String? = null,
        /** リレーが NIP-42 の認証を求めていて、グループ情報を読めなかった。 */
        val needsAuth: Boolean = false,
        val relayPubkey: String? = null,
    ) : JoinSheetState

    /** Buzz コミュニティへの参加（招待コードがあれば規約と招待、無ければ参加済みかの確認）。 */
    data class Buzz(
        val relayUrl: String,
        val name: String?,
        val code: String?,
        val policy: BuzzJoinPolicy? = null,
        /** NIP-11 の `supported_nips` に 43（コミュニティのメンバー制）がある。 */
        val inviteOnly: Boolean = false,
        val agreed: Boolean = false,
        val ageConfirmed: Boolean = false,
        val isJoining: Boolean = false,
        val error: String? = null,
    ) : JoinSheetState {
        val canSubmit: Boolean
            get() = !isJoining && (policy == null || code == null || (agreed && (!policy.ageAttestationRequired || ageConfirmed)))
    }
}

/** 作成シート（docs/chat-create-design.md）。 */
sealed interface CreateSheetState {
    data object Closed : CreateSheetState

    /** 新しいチャンネル（標準の NIP-29 ではグループ）。 */
    data class Channel(
        /** 作成先に選べるリレー・コミュニティ。 */
        val targets: List<ChatRelaySummary>,
        val target: ChatRelaySummary,
        val name: String = "",
        val about: String = "",
        val type: NewGroupType = NewGroupType.Chat,
        val visibility: NewGroupVisibility = NewGroupVisibility.Open,
        val isSubmitting: Boolean = false,
        val error: String? = null,
    ) : CreateSheetState {
        val canSubmit: Boolean get() = !isSubmitting && groupNameError(name) == null
    }

    /** 新しい DM（Buzz のみ）。 */
    data class Dm(
        val targets: List<ChatRelaySummary>,
        val target: ChatRelaySummary,
        val query: String = "",
        val picked: List<String> = emptyList(),
        /** そのコミュニティで見かけた人。名前での絞り込みは画面側で行う。 */
        val candidates: List<String> = emptyList(),
        /** 入力が公開鍵（npub / hex）として読めたとき、その人。 */
        val typedPubkey: String? = null,
        val isSubmitting: Boolean = false,
        val error: String? = null,
    ) : CreateSheetState {
        val canSubmit: Boolean get() = !isSubmitting && picked.isNotEmpty()
        val canPickMore: Boolean get() = picked.size < MAX_DM_OTHERS
    }
}

/** 作成が拒否されたときの案内。 */
internal fun createRejectionMessage(kind: CreateRejection, reason: String): String = when (kind) {
    CreateRejection.NameTaken -> "この名前は既に使われています"
    CreateRejection.InvalidName -> "この名前は使えません"
    CreateRejection.NotAllowed -> "作成する権限がありません: $reason"
    CreateRejection.IdTaken, CreateRejection.Other -> "作成できませんでした: $reason"
}

/** 一覧の表示（docs/chat-joined-timeline-design.md 1章）。 */
enum class ChatListView { Groups, Timeline }

/** タイムラインの1行。[group] はグループの見出し・アイコンに使う。 */
data class ChatTimelineRow(val entry: com.nostr.torinos.chat.JoinedTimelineEntry, val group: ChatGroupRow)

data class ChatTimelineState(
    val rows: List<ChatTimelineRow> = emptyList(),
    val isLoading: Boolean = false,
    /** 1回でも読み終えた。 */
    val loaded: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val canLoadOlder: Boolean = false,
    /** 答えなかったリレーの名前。 */
    val failedRelays: List<String> = emptyList(),
    /** Buzz の画像を読むための署名を許可している（外部署名器では押して許可する）。 */
    val imagesUnlocked: Boolean = false,
    /** bot（エージェント）の投稿を出さない（docs/chat-joined-timeline-design.md 2.1）。アカウントごとに保存する。 */
    val hideBots: Boolean = false,
    /** bot を隠すと出なくなる投稿の数（読めている範囲）。 */
    val hiddenBotPosts: Int = 0,
)

data class ChatTabUiState(
    val selection: ChatSelection = ChatSelection.All,
    val view: ChatListView = ChatListView.Groups,
    val timeline: ChatTimelineState = ChatTimelineState(),
    val relays: List<ChatRelaySummary> = emptyList(),
    /** kind 10009 から復元を確かめている Buzz コミュニティ（確認中。開けない）。 */
    val restoringRelays: List<ChatRelaySummary> = emptyList(),
    val joinedRows: List<ChatGroupRow> = emptyList(),
    /** 個別のリレーを選んだときの、未参加のグループ。 */
    val otherRows: List<ChatGroupRow> = emptyList(),
    /** Buzz でアーカイブ済みのチャンネル（参加中が先、続けて未参加）。参加中・そのほかの一覧には入れない。 */
    val archivedRows: List<ChatGroupRow> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val hasSigner: Boolean = true,
    val join: JoinSheetState = JoinSheetState.Closed,
    /** 選んだリレーが NIP-42 の認証を求めていて、グループの一覧を読めなかった（まだ許可していない）。 */
    val browseAuthNeeded: Boolean = false,
    /** 参加シートで探せる公開グループ（リレー設定に登録しているリレーと、参加中のリレー）。 */
    val publicGroups: List<PublicGroupEntry> = emptyList(),
    val publicGroupsLoading: Boolean = false,
    /** 参加中（承認待ちを含む）のグループ。公開グループの検索結果から外す。 */
    val joinedRefs: Set<GroupRef> = emptySet(),
    val create: CreateSheetState = CreateSheetState.Closed,
    /** 終わっていない作成（応答の取りこぼしなど）。一覧の上に帯で出す。 */
    val pendingCreations: List<PendingCreation> = emptyList(),
    /** やり直し中の作成。 */
    val resumingCreation: GroupRef? = null,
    val creationMessage: String? = null,
    /** NIP-17 の DM の受信箱。ログインしていなければ無い。 */
    val hasDm: Boolean = false,
    val dm: com.nostr.torinos.dm.DmInboxState = com.nostr.torinos.dm.DmInboxState(),
    /** 前回グループがあった（kind 10009 をまだ読めていなくても、グループのある人の形を保つ）。 */
    val groupsExpected: Boolean = false,
    /** Buzz コミュニティごとの自分の役割（kind 13534）。読めていないコミュニティは入らない。 */
    val communityRoles: Map<String, CommunityRole> = emptyMap(),
    /** コミュニティの管理のシート（docs/buzz-admin-design.md 3.5）。 */
    val communityAdmin: CommunityAdminSheet? = null,
) {
    /** 選んでいる Buzz コミュニティで、自分が owner / admin。ヘッダーのメニューに「コミュニティの管理…」を出す。 */
    val canManageSelectedCommunity: Boolean
        get() = (selection as? ChatSelection.Relay)?.relayUrl?.let { url ->
            relays.any { it.relayUrl == url && it.flavor == GroupFlavor.Buzz } && managesCommunity(communityRoles[url])
        } == true

    /** 参加中（承認待ち・作成中を含む）のグループか Buzz コミュニティがある。無い人は DM が主役（§8.4.1）。 */
    val hasGroups: Boolean get() = relays.isNotEmpty() || pendingCreations.isNotEmpty() || groupsExpected

    /** 一覧に NIP-17 の会話を出す（グループが無い人の「すべて」と、「DM」の選択）。 */
    val showsDmList: Boolean
        get() = hasDm && (selection == ChatSelection.Direct || (selection == ChatSelection.All && !hasGroups))

    /** 「グループ / タイムライン」の切り替えを出す。リレー・コミュニティを選んでいるときだけ（「すべて」と DM では出さない）。 */
    val showsViewToggle: Boolean get() = hasSigner && selection is ChatSelection.Relay

    /** タイムラインを出す。 */
    val showsTimeline: Boolean get() = showsViewToggle && view == ChatListView.Timeline

    /** 選んでいるのが Buzz のコミュニティ（bot の切り替えを出す）。 */
    val selectsBuzzCommunity: Boolean
        get() = (selection as? ChatSelection.Relay)?.relayUrl?.let { url ->
            relays.any { it.relayUrl == url && it.flavor == GroupFlavor.Buzz }
        } == true

    /**
     * ヘッダーの DM アイコンを出す（グループがある人の「すべて」だけ）。
     * リレー・コミュニティを選んでいる間は出さない。DM を開いている間は閉じるために残す。
     */
    val showsDmButton: Boolean
        get() = hasDm && hasGroups && (selection == ChatSelection.All || selection == ChatSelection.Direct)

    /** 作成先にできるリレー・コミュニティがある。 */
    val canCreateChannel: Boolean get() = hasSigner && relays.isNotEmpty()
    val canCreateDm: Boolean get() = hasSigner && relays.any { it.flavor == GroupFlavor.Buzz }
    /** フォーラムは Buzz だけ（標準の NIP-29 には種類の指定が無い）。 */
    val canCreateForum: Boolean get() = hasSigner && relays.any { it.flavor == GroupFlavor.Buzz }

    /**
     * ＋ボタンの動き。暗号化 DM（NIP-17）は DM の一覧を出しているときの＋から、
     * Buzz の DM・チャンネル・参加はグループ側の＋から（混ぜない）。
     */
    val fabAction: ChatFabAction
        get() = when {
            showsDmList -> ChatFabAction.NewEncryptedDm
            canCreateChannel -> ChatFabAction.Menu
            else -> ChatFabAction.Join
        }
}

enum class CommunityAdminTab { Members, Invite }

/** 招待の有効期限の選択肢（秒）。 */
internal val INVITE_TTL_CHOICES = listOf(3_600L to "1時間", 86_400L to "1日", 604_800L to "7日", 2_592_000L to "30日")
/** 招待の回数の選択肢（null は無制限）。 */
internal val INVITE_USES_CHOICES = listOf<Pair<Int?, String>>(1 to "1回", 10 to "10回", null to "無制限")

/** コミュニティの管理のシート。作った招待はこのシートの間だけ持つ（保存しない）。 */
data class CommunityAdminSheet(
    val relayUrl: String,
    val label: String,
    val tab: CommunityAdminTab = CommunityAdminTab.Members,
    /** null は読み込み中か、読めなかった（[error]）。 */
    val members: List<CommunityMember>? = null,
    val isLoading: Boolean = true,
    val viewerRole: CommunityRole? = null,
    /** 変更を送っている人。 */
    val busy: Set<String> = emptySet(),
    val error: String? = null,
    val inviteTtlSeconds: Long = 604_800L,
    val inviteMaxUses: Int? = 1,
    val isMinting: Boolean = false,
    val inviteError: String? = null,
    val invites: List<MintedInvite> = emptyList(),
)

/** チャットタブの＋ボタンを押したときの動き。 */
enum class ChatFabAction {
    /** NIP-17 の新しい DM の宛先選びを直接開く。 */
    NewEncryptedDm,

    /** Buzz の DM・新しいチャンネル・参加のメニューを開く。 */
    Menu,

    /** 参加のシートを開く（作成先が無い）。 */
    Join,
}

/** チャットタブの一覧と参加シート。 */
class ChatTabViewModel(
    private val account: ChatAccount?,
    private val dm: com.nostr.torinos.dm.DmInbox? = null,
    private val mutedPubkeys: kotlinx.coroutines.flow.StateFlow<Set<String>>? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ChatTabUiState(hasSigner = account != null, hasDm = dm != null))
    val state: StateFlow<ChatTabUiState> = _state.asStateFlow()

    private val metadataByRef = mutableMapOf<GroupRef, GroupMetadata>()
    private val lastMessageByRef = mutableMapOf<GroupRef, NostrEvent>()
    private val relayInfo = mutableMapOf<String, RelayInformation?>()
    /** Buzz コミュニティごとの、見えるチャンネルと参加中のチャンネル。 */
    private val buzzChannels = mutableMapOf<String, List<GroupRef>>()
    private val buzzJoined = mutableMapOf<String, Set<GroupRef>>()
    /** Buzz コミュニティごとの、参加中のチャンネルのメンバー（DM の宛先の候補）。 */
    private val buzzPeople = mutableMapOf<String, Set<String>>()
    private val lastRead = mutableMapOf<String, Long>()
    /** 既読位置より新しい発言の数（[UNREAD_BADGE_MAX] + 1 件まで数える）。 */
    private val unreadCounts = mutableMapOf<GroupRef, Int>()
    /** Buzz の最新の発言への編集（対象の ID → 本文）。 */
    private val editedBodies = mutableMapOf<String, String>()
    /** Buzz のスレッドのルート（ID → イベント）。一覧の最新の発言が返信のとき、その上に出す。 */
    private val threadRoots = mutableMapOf<String, NostrEvent>()
    private var browsedRefs: List<GroupRef> = emptyList()
    private var joined: List<JoinedGroup> = emptyList()
    private var pending: Set<GroupRef> = emptySet()
    private var buzz: Set<String> = emptySet()
    private var restoringBuzz: Set<String> = emptySet()
    /** この端末で非表示にしたリレー・コミュニティ（[com.nostr.torinos.chat.ChatHiddenRelaysStore]）。 */
    private var hiddenRelays: Set<String> = emptySet()
    // init の collect から refreshJoined() が呼ばれるので、init より前に宣言する。
    private var refreshJob: Job? = null
    private var timelineLoader: com.nostr.torinos.chat.JoinedTimelineLoader? = null
    private var timelineJob: Job? = null
    /** プロフィールを読みに行った作者（同じ人を何度も頼まない）。 */
    private val timelineAuthors = mutableSetOf<String>()
    /** 保存した選択を戻す前に利用者が選び直したら、戻さない。 */
    private var selectionTouched = false

    private data class AccountSnapshot(
        val joined: List<JoinedGroup>,
        val pending: Set<GroupRef>,
        val buzz: Set<String>,
        val loaded: Boolean,
    )

    init {
        if (dm != null) {
            // 外部 signer では、ここで初めて受信を始める（§8.4.4）。始めていれば読み直す。
            dm.refreshIfStarted()
            dm.start()
            launch { dm.state.collect { inbox -> _state.update { it.copy(dm = inbox) } } }
        }
        if (account != null) {
            account.start()
            launch { restoreSnapshot(account) }
            launch { restoreSelection(account) }
            launch { restoreView(account) }
            mutedPubkeys?.let { flow -> launch { flow.collect { publishTimeline() } } }
            launch { account.loadPendingCreations() }
            launch { account.pendingCreations.collect { pending -> _state.update { it.copy(pendingCreations = pending) } } }
            launch { account.groupsExpected.collect { expected -> _state.update { it.copy(groupsExpected = expected) } } }
            // 削除したチャンネルを一覧から外す（docs/buzz-admin-design.md 4.3）。
            launch { account.deletedChannels.collect { publish() } }
            // 別の端末で参加した Buzz コミュニティの確認中の表示（docs/buzz-community-sync-design.md 4.1）。
            launch {
                account.restoringBuzz.collect { restoring ->
                    restoringBuzz = restoring
                    restoring.forEach { url -> launch { info(url); publish() } }
                    publish()
                }
            }
            // 非表示のリレーは一覧から外し、読み込みもしない。表示に戻したら読み直す。
            launch {
                com.nostr.torinos.chat.ChatHiddenRelaysStore.hidden(account.pubkey).collect { hidden ->
                    val shown = hiddenRelays - hidden
                    hiddenRelays = hidden
                    val selected = (_state.value.selection as? ChatSelection.Relay)?.relayUrl
                    if (selected != null && com.nostr.torinos.chat.isHiddenRelay(selected, hidden)) select(ChatSelection.All)
                    publish()
                    if (shown.isNotEmpty()) refreshJoined()
                }
            }
            launch {
                combine(account.joinedGroups, account.pendingGroups, account.buzzCommunities, account.loaded, ::AccountSnapshot)
                    .collect { snapshot ->
                        val changed = snapshot.joined != joined || snapshot.pending != pending || snapshot.buzz != buzz
                        joined = snapshot.joined
                        pending = snapshot.pending
                        buzz = snapshot.buzz
                        _state.update { it.copy(hasLoaded = snapshot.loaded) }
                        publish()
                        if (changed) refreshJoined()
                    }
            }
        } else {
            _state.update { it.copy(hasLoaded = true) }
        }
    }

    private fun standardRefs(): List<GroupRef> = (joined.map { it.ref } + pending).distinct()

    private fun isHidden(relayUrl: String) = com.nostr.torinos.chat.isHiddenRelay(relayUrl, hiddenRelays)

    /** 表示する（非表示にしていない）参加中のグループと Buzz コミュニティ。 */
    private fun visibleStandardRefs(): List<GroupRef> = standardRefs().filterNot { isHidden(it.relayUrl) }
    private fun visibleBuzz(): Set<String> = buzz.filterNot(::isHidden).toSet()

    /** 切り替えメニューの長押しから。この端末でだけ、このリレーを一覧から外す。 */
    fun hideRelay(relayUrl: String) {
        val me = account?.pubkey ?: return
        com.nostr.torinos.chat.ChatHiddenRelaysStore.hide(me, relayUrl)
    }

    private suspend fun info(relayUrl: String): RelayInformation? =
        relayInfo.getOrPut(relayUrl) { RelayInformationRepository.fetch(relayUrl).getOrNull() }

    fun layoutOf(ref: GroupRef): GroupLayout = metadataByRef[ref]?.layout ?: GroupLayout.Chat

    /** 参加中の Buzz コミュニティを kind 10009 と合わせる（チャットタブを開いたとき）。 */
    fun syncBuzzCommunities() {
        account?.syncBuzzCommunities()
    }

    /** 参加中の Buzz コミュニティ（`buzz://` のリンクをどこで開くかを決めるため）。 */
    suspend fun buzzCommunitiesForLink(): List<ChatRelaySummary> {
        account?.awaitReady()
        return account?.buzzCommunities?.value.orEmpty().sorted().map { info(it).let { i -> ChatRelaySummary(it, i?.name, GroupFlavor.Buzz, i?.icon) } }
    }

    /** 参加中のグループの情報と最新の発言を、リレーごとに並行して読む。前の読み込みは取り消す。 */
    fun refreshJoined() {
        val byRelay = visibleStandardRefs().groupBy { it.relayUrl }
        val buzzRelays = visibleBuzz()
        if (byRelay.isEmpty() && buzzRelays.isEmpty()) return
        refreshJob?.cancel()
        _state.update { it.copy(isLoading = true) }
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                account?.awaitReady()
                loadLastRead()
                coroutineScope {
                    (byRelay.map { (relayUrl, refs) -> async { refreshRelay(relayUrl, refs) } } +
                        buzzRelays.map { relayUrl -> async { refreshBuzz(relayUrl) } }).awaitAll()
                }
                saveSnapshot()
                // 参加中の一覧を読み直したので、タイムラインもその一覧で読み直す。
                refreshTimeline()
            } finally {
                if (refreshJob === coroutineContext[Job]) _state.update { it.copy(isLoading = false) }
            }
        }
        refreshJob = job
        job.start()
    }

    /** 前回の一覧を出す。リレーから読んだものがあれば、そちらを優先する。 */
    private suspend fun restoreSnapshot(account: ChatAccount) {
        val snapshot = ChatListCache.load(account.pubkey)
        if (snapshot.rows.isEmpty()) return
        loadLastRead()
        snapshot.rows.forEach { row ->
            val ref = row.ref
            row.metadata?.let { metadataByRef.getOrPut(ref) { it } }
            row.lastMessage?.let { cached ->
                val current = lastMessageByRef[ref]
                if (current == null || cached.createdAt > current.createdAt) lastMessageByRef[ref] = cached
            }
            row.editedBody?.let { body -> row.lastMessage?.let { editedBodies.getOrPut(it.id) { body } } }
            row.threadRoot?.let { root ->
                threadRoots.getOrPut(root.id) { root }
                row.threadRootEditedBody?.let { editedBodies.getOrPut(root.id) { it } }
            }
        }
        snapshot.rows.filter { it.buzzJoined }.groupBy { it.relayUrl }.forEach { (relayUrl, rows) ->
            if (!buzzJoined.containsKey(relayUrl)) buzzJoined[relayUrl] = rows.map { it.ref }.toSet()
        }
        publish()
    }

    /** いまの参加中の一覧を、次に開いたときのために保存する。 */
    private suspend fun saveSnapshot() {
        val me = account?.pubkey ?: return
        val buzzRefs = buzz.flatMap { buzzJoined[it].orEmpty() }.toSet()
        val rows = (buzzRefs + standardRefs()).map { ref ->
            val last = lastMessageByRef[ref]
            val root = threadRootOf(last, if (ref in buzzRefs) GroupFlavor.Buzz else GroupFlavor.Standard)
            CachedChatRow(
                relayUrl = ref.relayUrl,
                groupId = ref.groupId,
                buzzJoined = ref in buzzRefs,
                metadata = metadataByRef[ref],
                lastMessage = last,
                editedBody = last?.let { editedBodies[it.id] },
                threadRoot = root,
                threadRootEditedBody = root?.let { editedBodies[it.id] },
            )
        }.sortedByDescending { it.lastMessage?.createdAt ?: 0L }
        ChatListCache.save(me, ChatListSnapshot(rows))
    }

    private suspend fun loadLastRead() {
        val me = account?.pubkey ?: return
        decodeLastRead(LocalSettingsStorage.getString(lastReadKey(me))).forEach { (key, value) ->
            lastRead[key] = maxOf(value, lastRead[key] ?: 0L)
        }
    }

    private suspend fun refreshRelay(relayUrl: String, refs: List<GroupRef>) {
        val relayKey = info(relayUrl)?.self
        val ids = refs.map { it.groupId }
        // リレーには1回の要求のフィルター数に上限があるので、最新の発言は数件ずつに分けて読む。
        val events = fetchFrom(
            relayUrl,
            listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = relayKey?.let(::listOf), dTags = ids)),
        ) + ids.chunked(LAST_MESSAGE_FILTERS_PER_REQUEST).flatMap { chunk ->
            fetchFrom(relayUrl, chunk.map { NostrFilter(kinds = listOf(GROUP_CHAT_KIND, GROUP_THREAD_KIND), hTags = listOf(it), limit = 1) })
        }
        latestGroupMetadata(events, relayKey).forEach { (id, meta) -> metadataByRef[GroupRef(relayUrl, id)] = meta }
        rememberLastMessages(relayUrl, events)
        publish()
        refreshUnreadCounts(relayUrl, refs) { listOf(GROUP_CHAT_KIND, GROUP_THREAD_KIND) }
    }

    /**
     * 未読がある行だけ、既読位置より後の発言を読んで数える。読めなければ件数は付けず、点だけにする
     * （一部のリレーが答えなくても一覧は止めない）。
     */
    private suspend fun refreshUnreadCounts(relayUrl: String, refs: Collection<GroupRef>, kindsOf: (GroupRef) -> List<Int>) {
        val targets = refs.mapNotNull { ref ->
            val readUntil = lastRead[ref.key] ?: return@mapNotNull null
            val last = lastMessageByRef[ref]
            if (last == null || last.createdAt <= readUntil) {
                unreadCounts.remove(ref)
                return@mapNotNull null
            }
            Triple(ref, readUntil, kindsOf(ref))
        }
        if (targets.isEmpty()) return
        val events = fetchChunked(
            relayUrl,
            targets.map { (ref, readUntil, kinds) ->
                NostrFilter(kinds = kinds, hTags = listOf(ref.groupId), since = readUntil + 1, limit = UNREAD_BADGE_MAX + 1)
            }.chunked(LAST_MESSAGE_FILTERS_PER_REQUEST),
        )
        val byGroup = events.groupBy { e -> e.tags.firstOrNull { it.firstOrNull() == "h" }?.getOrNull(1) }
        targets.forEach { (ref, readUntil, kinds) ->
            val count = countUnreadMessages(byGroup[ref.groupId].orEmpty().filter { it.kind in kinds }, account?.pubkey, readUntil)
            if (count > 0) unreadCounts[ref] = count else unreadCounts.remove(ref)
        }
        publish()
    }

    /** Buzz: 見えるチャンネル（39000）と、自分が入っているチャンネル（39002 の `p`）、参加中のチャンネルの最新の発言。 */
    private suspend fun refreshBuzz(relayUrl: String) {
        val me = account?.pubkey ?: return
        val relayKey = info(relayUrl)?.self
        // メンバー一覧（39002）はリレーの鍵で署名されたものだけを信じる。鍵が分からなければ参加中とみなさない。
        val result = fetchResultFrom(
            relayUrl,
            listOfNotNull(
                NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = relayKey?.let(::listOf), limit = 300),
                relayKey?.let { NostrFilter(kinds = listOf(GROUP_MEMBERS_KIND), authors = listOf(it), pTags = listOf(me), limit = 300) },
                // 自分が隠した DM の一覧。
                relayKey?.let { NostrFilter(kinds = listOf(BUZZ_DM_VISIBILITY_KIND), authors = listOf(it), pTags = listOf(me), limit = 1) },
            ),
        )
        // 答えがそろわなかったときは、前回の一覧（保存済み）を残す。参加中のチャンネルが無いと決めつけない。
        if ((!result.allAnswered || relayKey == null) && buzzJoined.containsKey(relayUrl)) return
        val events = result.events
        val memberLists = events.filter { it.kind == GROUP_MEMBERS_KIND }
            .groupBy { e -> e.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) }
            .mapNotNull { (id, lists) -> id?.let { it to (lists.maxByOrNull { e -> e.createdAt } ?: return@mapNotNull null) } }
            .filter { (_, latest) -> latest.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == me } }
            .toMap()
        // DM と一覧に載らないチャンネルは、ID を指定しないとグループ情報が返らない。
        val listed = latestGroupMetadata(events, relayKey)
        val missing = memberLists.keys.filter { it !in listed }
        val extra = if (missing.isEmpty() || relayKey == null) emptyList() else fetchChunked(
            relayUrl,
            missing.chunked(METADATA_IDS_PER_REQUEST).map { ids -> listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = listOf(relayKey), dTags = ids)) },
        )
        val metadata = listed + latestGroupMetadata(extra, relayKey)
        metadata.forEach { (id, meta) -> metadataByRef[GroupRef(relayUrl, id)] = meta }
        val hiddenDms = hiddenDmIds(events, relayKey)
        val mine = memberLists.keys
            .filter { id ->
                val meta = metadata[id] ?: return@filter false
                when (meta.layout) {
                    GroupLayout.Hidden -> false
                    GroupLayout.Direct -> id !in hiddenDms
                    else -> true
                }
            }
            .map { GroupRef(relayUrl, it) }
            .toSet()
        // 未参加でも見えるチャンネル。DM は参加者にしか出さない。
        val visible = metadata.values
            .filter { it.layout != GroupLayout.Hidden && it.layout != GroupLayout.Direct && !it.isHidden }
            .map { GroupRef(relayUrl, it.groupId) }
        buzzPeople[relayUrl] = memberLists.values
            .flatMap { list -> list.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) } }
            .filter { it != me }
            .toSet()
        // DM の見出しは参加者の名前から作る。コミュニティのリレーへ先に聞く。
        val dmPeople = mine.flatMap { metadata[it.groupId]?.takeIf { m -> m.layout == GroupLayout.Direct }?.participants.orEmpty() }.toSet() - me
        if (dmPeople.isNotEmpty()) {
            launch { ProfileRepository.ensureProfiles(dmPeople, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
        }
        // グループの画面を開いたとき、リレーの答えを待たずに投稿欄を出せるようにする。
        mine.forEach { account.roomCache.setJoinedHint(it, true) }
        // 参加中のチャンネルは、最新の発言を読んでから一覧に入れる。先に入れると発言の無い順で並び、読んだ後に並び替わってしまう。
        // それまでは前回の一覧（保存済み）を出しておく。
        if (mine.isEmpty()) {
            buzzChannels[relayUrl] = visible
            buzzJoined[relayUrl] = mine
            publish()
        } else {
            val latest = fetchChunked(
                relayUrl,
                mine.take(30).map { ref ->
                    val kinds = if (metadataByRef[ref]?.layout == GroupLayout.Forum) listOf(BUZZ_FORUM_POST_KIND) else listOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND)
                    NostrFilter(kinds = kinds, hTags = listOf(ref.groupId), limit = 1)
                }.chunked(LAST_MESSAGE_FILTERS_PER_REQUEST),
            )
            rememberLastMessages(relayUrl, latest)
            // 並び順は最新の発言で決まる。編集（1行目の本文だけ）を待たずに出す。
            buzzChannels[relayUrl] = visible
            buzzJoined[relayUrl] = mine
            publish()
            launch {
                refreshUnreadCounts(relayUrl, mine.take(30)) { ref ->
                    if (metadataByRef[ref]?.layout == GroupLayout.Forum) listOf(BUZZ_FORUM_POST_KIND) else listOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND)
                }
            }
            // 最新の発言がスレッドの返信なら、ルートも読んで「元の投稿 + 返信」で出す。
            loadThreadRoots(relayUrl, mine.mapNotNull { lastMessageByRef[it] })
            // 一覧の1行目にも編集（40003）を反映する。作者本人の最新の編集だけ。
            val lastIds = mine.mapNotNull { lastMessageByRef[it] }.flatMap { listOfNotNull(it, threadRootOf(it, GroupFlavor.Buzz)) }
            if (lastIds.isNotEmpty()) {
                val edits = fetchFrom(relayUrl, listOf(NostrFilter(kinds = listOf(com.nostr.torinos.chat.BUZZ_EDIT_KIND), eTags = lastIds.map { it.id }, limit = 100)))
                lastIds.forEach { target ->
                    edits.filter { edit -> edit.pubkey == target.pubkey && edit.tags.any { it.firstOrNull() == "e" && it.getOrNull(1) == target.id } }
                        .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenByDescending { it.id })
                        ?.let { editedBodies[target.id] = it.content }
                }
            }
            publish()
        }
    }

    /** スレッドの返信のルートのうち、まだ読んでいないものを読む。読めなければ返信だけを出す。 */
    private suspend fun loadThreadRoots(relayUrl: String, messages: List<NostrEvent>) {
        val missing = messages.mapNotNull { com.nostr.torinos.chat.buzzThreadRootId(it) }.filter { it !in threadRoots }.distinct()
        if (missing.isEmpty()) return
        val roots = fetchFrom(relayUrl, listOf(NostrFilter(ids = missing)))
        roots.filter { it.id in missing }.forEach { threadRoots[it.id] = it }
        rememberAuthors(relayUrl, roots)
        publish()
    }

    private fun threadRootOf(message: NostrEvent?, flavor: GroupFlavor): NostrEvent? {
        if (message == null || flavor != GroupFlavor.Buzz) return null
        return com.nostr.torinos.chat.buzzThreadRootId(message)?.let(threadRoots::get)
    }

    private fun rememberAuthors(relayUrl: String, events: List<NostrEvent>) {
        val flavor = if (relayUrl in buzz) GroupFlavor.Buzz else GroupFlavor.Standard
        val relayKey = relayInfo[relayUrl]?.self
        val authors = events.map { groupMessageAuthor(it, flavor, relayKey) }.toSet() - setOfNotNull(account?.pubkey)
        if (authors.isNotEmpty()) {
            launch { ProfileRepository.ensureProfiles(authors, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
        }
    }

    private fun rememberLastMessages(relayUrl: String, events: List<NostrEvent>) {
        // 一覧のプレビューに送信者の名前を付けるので、プロフィールを読んでおく（グループのリレーに先に聞く）。
        val flavor = if (relayUrl in buzz) GroupFlavor.Buzz else GroupFlavor.Standard
        val relayKey = relayInfo[relayUrl]?.self
        val authors = events
            .filter { it.kind != GROUP_METADATA_KIND && it.kind != GROUP_MEMBERS_KIND }
            .map { groupMessageAuthor(it, flavor, relayKey) }
            .toSet() - setOfNotNull(account?.pubkey)
        if (authors.isNotEmpty()) {
            launch { ProfileRepository.ensureProfiles(authors, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
        }
        events.filter { it.kind != GROUP_METADATA_KIND && it.kind != GROUP_MEMBERS_KIND }.forEach { event ->
            val groupId = event.tags.firstOrNull { it.firstOrNull() == "h" }?.getOrNull(1) ?: return@forEach
            val ref = GroupRef(relayUrl, groupId)
            val current = lastMessageByRef[ref]
            if (current == null || event.createdAt > current.createdAt) lastMessageByRef[ref] = event
        }
    }

    /**
     * 前回選んだリレーに戻す。表示はすぐ切り替え、リレーの中身は Buzz の一覧(端末に保存済み)を読んでから読む
     * (読む前だと Buzz のリレーを通常の NIP-29 として読んでしまう)。
     */
    private suspend fun restoreSelection(account: ChatAccount) {
        val stored = decodeChatSelection(LocalSettingsStorage.getString(selectionKey(account.pubkey)))
        if (stored == ChatSelection.All || selectionTouched) return
        if (stored is ChatSelection.Relay && isHidden(stored.relayUrl)) return
        _state.update { it.copy(selection = stored) }
        publish()
        if (stored !is ChatSelection.Relay) return
        account.awaitReady()
        if (selectionTouched || _state.value.selection != stored) return
        if (stored.relayUrl !in account.buzzCommunities.value) browseRelay(stored.relayUrl) else loadCommunityRole(stored.relayUrl)
    }

    fun select(selection: ChatSelection) {
        selectionTouched = true
        account?.pubkey?.let { me ->
            launch { LocalSettingsStorage.putString(selectionKey(me), encodeChatSelection(selection)) }
        }
        _state.update { it.copy(selection = selection, browseAuthNeeded = false) }
        publish()
        if (refreshJob?.isActive != true) refreshTimeline()
        if (selection is ChatSelection.Relay && selection.relayUrl !in buzz) browseRelay(selection.relayUrl)
        if (selection is ChatSelection.Relay && selection.relayUrl in buzz) loadCommunityRole(selection.relayUrl)
    }

    // ---- タイムライン（docs/chat-joined-timeline-design.md） ----

    private suspend fun restoreView(account: ChatAccount) {
        if (LocalSettingsStorage.getString(hideBotsKey(account.pubkey)) == "true" && !_state.value.timeline.hideBots) {
            _state.update { it.copy(timeline = it.timeline.copy(hideBots = true)) }
            publishTimeline()
        }
        val stored = LocalSettingsStorage.getString(viewKey(account.pubkey))
        if (stored != ChatListView.Timeline.name || _state.value.view == ChatListView.Timeline) return
        setView(ChatListView.Timeline, persist = false)
    }

    fun setView(view: ChatListView) = setView(view, persist = true)

    private fun setView(view: ChatListView, persist: Boolean) {
        if (persist) account?.pubkey?.let { me -> launch { LocalSettingsStorage.putString(viewKey(me), view.name) } }
        _state.update { it.copy(view = view) }
        // 一覧の読み直しの途中なら、終わったときに読む。
        if (view == ChatListView.Timeline && refreshJob?.isActive != true) refreshTimeline()
    }

    /** タイムラインで bot の投稿を出さない。読み直さず、読めているものから作り直す。 */
    fun setHideBots(hide: Boolean) {
        account?.pubkey?.let { me -> launch { LocalSettingsStorage.putString(hideBotsKey(me), hide.toString()) } }
        _state.update { it.copy(timeline = it.timeline.copy(hideBots = hide)) }
        publishTimeline()
    }

    /** 参加中のグループ（選んでいるリレーのものだけ）。削除・非表示のチャンネルは外す。 */
    private suspend fun timelineGroups(): List<com.nostr.torinos.chat.TimelineGroup> {
        val selection = _state.value.selection
        val deleted = account?.deletedChannels?.value.orEmpty()
        val buzzRefs = visibleBuzz().flatMap { buzzJoined[it].orEmpty() }
            .filter { it !in deleted && metadataByRef[it]?.layout != GroupLayout.Hidden }
            .map { it to GroupFlavor.Buzz }
        val standard = joined.map { it.ref }.filterNot { isHidden(it.relayUrl) }.map { it to GroupFlavor.Standard }
        return (buzzRefs + standard)
            .filter { (ref, _) -> selection !is ChatSelection.Relay || ref.relayUrl == selection.relayUrl }
            .distinctBy { it.first }
            .map { (ref, flavor) -> com.nostr.torinos.chat.TimelineGroup(ref, flavor, info(ref.relayUrl)?.self) }
    }

    /** タイムラインを読み直す。読み終わるまでは前の結果を出しておく。 */
    fun refreshTimeline() {
        if (!_state.value.showsTimeline) return
        val account = account ?: return
        timelineJob?.cancel()
        _state.update { it.copy(timeline = it.timeline.copy(isLoading = true)) }
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                account.awaitReady()
                val loader = com.nostr.torinos.chat.JoinedTimelineLoader(account.pubkey, ::timelineFetch)
                loader.load(timelineGroups())
                timelineLoader = loader
                publishTimeline(loaded = true)
            } finally {
                if (timelineJob === coroutineContext[Job]) {
                    _state.update { it.copy(timeline = it.timeline.copy(isLoading = false, isLoadingOlder = false)) }
                }
            }
        }
        timelineJob = job
        job.start()
    }

    fun loadOlderTimeline() {
        val loader = timelineLoader ?: return
        if (timelineJob?.isActive == true || !loader.canLoadOlder) return
        _state.update { it.copy(timeline = it.timeline.copy(isLoadingOlder = true)) }
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                loader.loadOlder()
                if (timelineLoader === loader) publishTimeline()
            } finally {
                if (timelineJob === coroutineContext[Job]) _state.update { it.copy(timeline = it.timeline.copy(isLoadingOlder = false)) }
            }
        }
        timelineJob = job
        job.start()
    }

    private suspend fun timelineFetch(relayUrl: String, filters: List<NostrFilter>): com.nostr.torinos.chat.TimelineFetch {
        if (filters.isEmpty()) return com.nostr.torinos.chat.TimelineFetch(emptyList(), answered = true)
        val result = fetchResultFrom(relayUrl, filters)
        return com.nostr.torinos.chat.TimelineFetch(result.events, result.allAnswered)
    }

    private fun publishTimeline(loaded: Boolean = false) {
        val loader = timelineLoader ?: return
        val entries = loader.entries(mutedPubkeys?.value.orEmpty(), hideBots = _state.value.timeline.hideBots)
        // 行に出す作者の名前を読んでおく（グループのリレーに先に聞く）。Buzz では本文でメンションされた人の名前も要る。
        entries.flatMap { entry ->
            val posts = listOfNotNull(entry.conversation.root, entry.conversation.latestReply)
            val mentioned = if (entry.ref.relayUrl in buzz) posts.flatMap { com.nostr.torinos.chat.buzzProfileTargets(it.event) } else emptyList()
            (posts.map { it.author } + mentioned).map { it to entry.ref.relayUrl }
        }.filter { (author, _) -> author != account?.pubkey && timelineAuthors.add(author) }
            .groupBy({ it.second }, { it.first })
            .forEach { (relayUrl, authors) ->
                launch { ProfileRepository.ensureProfiles(authors.toSet(), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
            }
        val rows = entries.map { entry ->
            ChatTimelineRow(entry, row(entry.ref, if (entry.ref.relayUrl in buzz) GroupFlavor.Buzz else GroupFlavor.Standard))
        }
        val failed = loader.failedRelays.map { url -> relayInfo[url]?.name?.takeIf { it.isNotBlank() } ?: url.substringAfter("://").trimEnd('/') }
        _state.update {
            it.copy(
                timeline = it.timeline.copy(
                    rows = rows,
                    loaded = it.timeline.loaded || loaded,
                    canLoadOlder = loader.canLoadOlder,
                    failedRelays = failed.sorted(),
                    hiddenBotPosts = loader.hiddenBotPosts(mutedPubkeys?.value.orEmpty()),
                ),
            )
        }
    }

    /** Buzz の外部署名器で、タイムラインの画像を読むための署名を許可した。 */
    fun unlockTimelineImages() {
        _state.update { it.copy(timeline = it.timeline.copy(imagesUnlocked = true)) }
    }

    /** タイムラインの画像の `Authorization`。Buzz のコミュニティの `/media/` だけに付ける（GroupChatViewModel と同じ）。 */
    suspend fun timelineImageAuth(url: String): ImageAuth {
        val account = account ?: return ImageAuth.NotNeeded
        val host = com.nostr.torinos.chat.buzzMediaHost(url, buzz) ?: return ImageAuth.NotNeeded
        if (!account.signsSilently && !_state.value.timeline.imagesUnlocked) return ImageAuth.NeedsConsent
        return runCatching { ImageAuth.Header(account.blossomAuthorization(host)) }.getOrElse { ImageAuth.Failed }
    }

    private fun browseRelay(relayUrl: String) {
        _state.update { it.copy(isLoading = true) }
        launch {
            try {
                val relayKey = info(relayUrl)?.self
                val result = fetchResultFrom(
                    relayUrl,
                    listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = relayKey?.let(::listOf), limit = 300)),
                )
                // 認証を求められて読めなかったら、空の一覧にせず、公開鍵を伝えてよいかを聞く。
                val outcome = result.relays[relayUrl]?.outcome
                val authNeeded = outcome is RelayOutcome.Closed && outcome.retry == RetryDisposition.RetryAfterAuth &&
                    account?.isAuthAllowed(relayUrl) == false
                if ((_state.value.selection as? ChatSelection.Relay)?.relayUrl == relayUrl) {
                    _state.update { it.copy(browseAuthNeeded = authNeeded) }
                }
                val metadata = latestGroupMetadata(result.events, relayKey)
                metadata.forEach { (id, meta) -> metadataByRef[GroupRef(relayUrl, id)] = meta }
                browsedRefs = metadata.values
                    .filterNot { it.isHidden }
                    .map { GroupRef(relayUrl, it.groupId) }
                publish()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** 選んだリレーへの NIP-42 の認証を許可して、グループの一覧を読み直す。 */
    fun allowBrowseAuth() {
        val account = account ?: return
        val relayUrl = (_state.value.selection as? ChatSelection.Relay)?.relayUrl ?: return
        _state.update { it.copy(browseAuthNeeded = false) }
        launch {
            account.allowAuth(relayUrl)
            browseRelay(relayUrl)
        }
    }

    private suspend fun fetchChunked(relayUrl: String, chunks: List<List<NostrFilter>>): List<NostrEvent> =
        chunks.flatMap { fetchFrom(relayUrl, it) }

    private suspend fun fetchFrom(relayUrl: String, filters: List<NostrFilter>): List<NostrEvent> =
        fetchResultFrom(relayUrl, filters).events

    private suspend fun fetchResultFrom(relayUrl: String, filters: List<NostrFilter>) =
        fetchFiniteEvents(
            SubscriptionSpec(
                id = "chat-list-${Random.nextLong().toULong().toString(16)}",
                filters = filters,
                target = RelayTarget.Explicit(setOf(relayUrl)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                replayCachedEvents = false,
            ),
        )

    private fun row(ref: GroupRef, flavor: GroupFlavor = GroupFlavor.Standard): ChatGroupRow {
        val isJoined = if (flavor == GroupFlavor.Buzz) ref in buzzJoined[ref.relayUrl].orEmpty() else joined.any { it.ref == ref }
        val last = lastMessageByRef[ref]
        val readUntil = lastRead[ref.key]
        val lastAuthor = last?.let { groupMessageAuthor(it, flavor, relayInfo[ref.relayUrl]?.self) }
        val root = threadRootOf(last, flavor)
        // 一度も開いていないグループは未読にしない（参加直後に全部が未読になるのを避ける）。
        val unread = isJoined && last != null && readUntil != null && lastAuthor != account?.pubkey && last.createdAt > readUntil
        return ChatGroupRow(
            ref = ref,
            metadata = metadataByRef[ref],
            joined = isJoined,
            pending = flavor == GroupFlavor.Standard && ref in pending && !isJoined,
            lastMessage = last,
            flavor = flavor,
            editedBody = last?.let { editedBodies[it.id] },
            listedName = joined.firstOrNull { it.ref == ref }?.name,
            unread = unread,
            unreadCount = if (unread) unreadCounts[ref] else null,
            lastAuthor = lastAuthor,
            threadRoot = root,
            threadRootEditedBody = root?.let { editedBodies[it.id] },
            threadRootAuthor = root?.let { groupMessageAuthor(it, flavor, relayInfo[ref.relayUrl]?.self) },
        )
    }

    private fun publish() {
        // グループが無くなったら「DM」の選択は「すべて」に戻す（同じ中身になるため、§8.4.6）。
        _state.value.let { current ->
            if (current.selection == ChatSelection.Direct && current.hasLoaded && !current.hasGroups) {
                _state.update { it.copy(selection = ChatSelection.All) }
            }
        }
        val selection = _state.value.selection
        val standardRelays = visibleStandardRefs().map { it.relayUrl }
        val relays = (standardRelays + buzz + listOfNotNull((selection as? ChatSelection.Relay)?.relayUrl))
            .distinct()
            .filterNot(::isHidden)
            .map { ChatRelaySummary(it, relayInfo[it]?.name, if (it in buzz) GroupFlavor.Buzz else GroupFlavor.Standard, relayInfo[it]?.icon) }
        val restoringRelays = (restoringBuzz - buzz).sorted().filterNot(::isHidden)
            .map { ChatRelaySummary(it, relayInfo[it]?.name, GroupFlavor.Buzz, relayInfo[it]?.icon) }
        // 削除したチャンネルは、控えや前回の一覧に残っていても出さない。
        val deleted = account?.deletedChannels?.value.orEmpty()
        val buzzMine = visibleBuzz().flatMap { url -> buzzJoined[url].orEmpty().filter { it !in deleted }.map { row(it, GroupFlavor.Buzz) } }
        val standardMine = visibleStandardRefs().map { row(it) }
        val mine = (standardMine + buzzMine)
            .filter { selection !is ChatSelection.Relay || it.ref.relayUrl == selection.relayUrl }
            .sortedByDescending { it.lastActivity }
        val others = when {
            selection !is ChatSelection.Relay -> emptyList()
            selection.relayUrl in buzz -> buzzChannels[selection.relayUrl].orEmpty()
                .filter { it !in buzzJoined[selection.relayUrl].orEmpty() && it !in deleted }
                .map { row(it, GroupFlavor.Buzz) }
                .sortedWith(compareBy<ChatGroupRow> { it.layout }.thenBy { it.title.lowercase() })
            else -> browsedRefs.filter { it.relayUrl == selection.relayUrl && it !in standardRefs() }
                .map { row(it) }
                .sortedBy { it.title.lowercase() }
        }
        val (activeMine, archivedMine) = splitArchivedRows(mine)
        val (activeOthers, archivedOthers) = splitArchivedRows(others)
        _state.update {
            it.copy(
                relays = relays,
                restoringRelays = restoringRelays,
                joinedRows = activeMine,
                otherRows = activeOthers,
                archivedRows = archivedMine + archivedOthers,
                joinedRefs = standardRefs().toSet(),
            )
        }
    }

    // ---- コミュニティの管理（Buzz、docs/buzz-admin-design.md 3.5） ----

    /** 自分の役割を読む（ヘッダーのメニューの出し分けに使う）。読めなければ前の値を残す。 */
    private fun loadCommunityRole(relayUrl: String) {
        val account = account ?: return
        launch {
            val load = account.fetchCommunityRoster(relayUrl)
            applyRoster(relayUrl, load)
        }
    }

    private fun applyRoster(relayUrl: String, load: CommunityRosterLoad) {
        val me = account?.pubkey
        when (load) {
            is CommunityRosterLoad.Loaded -> {
                val role = load.members.firstOrNull { it.pubkey == me }?.role
                _state.update { state ->
                    val roles = if (role == null) state.communityRoles - relayUrl else state.communityRoles + (relayUrl to role)
                    val sheet = state.communityAdmin?.takeIf { it.relayUrl == relayUrl }
                        ?.copy(members = load.members, viewerRole = role, isLoading = false, error = null)
                    state.copy(communityRoles = roles, communityAdmin = sheet ?: state.communityAdmin)
                }
                val people = load.members.map { it.pubkey }.take(COMMUNITY_PROFILE_LIMIT).toSet()
                if (people.isNotEmpty() && _state.value.communityAdmin?.relayUrl == relayUrl) {
                    launch { ProfileRepository.ensureProfiles(people, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
                }
            }
            CommunityRosterLoad.None -> _state.update { state ->
                val sheet = state.communityAdmin?.takeIf { it.relayUrl == relayUrl }
                    ?.copy(members = emptyList(), isLoading = false, error = "このコミュニティはメンバーの一覧を公開していません")
                state.copy(communityRoles = state.communityRoles - relayUrl, communityAdmin = sheet ?: state.communityAdmin)
            }
            is CommunityRosterLoad.Failed -> _state.update { state ->
                val sheet = state.communityAdmin?.takeIf { it.relayUrl == relayUrl }
                    ?.copy(isLoading = false, error = "メンバーの一覧を読めませんでした: ${load.reason}")
                state.copy(communityAdmin = sheet ?: state.communityAdmin)
            }
        }
    }

    fun openCommunityAdmin() {
        val relayUrl = (_state.value.selection as? ChatSelection.Relay)?.relayUrl?.takeIf { it in buzz } ?: return
        val label = _state.value.relays.firstOrNull { it.relayUrl == relayUrl }?.label ?: relayUrl.substringAfter("://")
        _state.update {
            it.copy(communityAdmin = CommunityAdminSheet(relayUrl = relayUrl, label = label, viewerRole = it.communityRoles[relayUrl]))
        }
        loadCommunityRole(relayUrl)
    }

    fun closeCommunityAdmin() {
        _state.update { if (it.communityAdmin?.isMinting == true) it else it.copy(communityAdmin = null) }
    }

    fun setCommunityAdminTab(tab: CommunityAdminTab) {
        _state.update { state -> state.communityAdmin?.let { state.copy(communityAdmin = it.copy(tab = tab)) } ?: state }
    }

    fun dismissCommunityAdminError() {
        _state.update { state -> state.communityAdmin?.let { state.copy(communityAdmin = it.copy(error = null)) } ?: state }
    }

    /** 役割の変更・外す（9032・9031）。外すときの確認は画面で済ませてから呼ぶ。 */
    fun communityAction(member: CommunityMember, action: CommunityAction) {
        val account = account ?: return
        val sheet = _state.value.communityAdmin ?: return
        if (member.pubkey in sheet.busy) return
        if (action !in allowedCommunityActions(sheet.viewerRole, member, self = member.pubkey == account.pubkey)) return
        val draft = when (action) {
            CommunityAction.Promote -> buildCommunityRole(member.pubkey, CommunityRole.Admin)
            CommunityAction.Demote -> buildCommunityRole(member.pubkey, CommunityRole.Member)
            CommunityAction.Remove -> buildCommunityRemove(member.pubkey)
        }
        val relayUrl = sheet.relayUrl
        fun updateSheet(transform: (CommunityAdminSheet) -> CommunityAdminSheet) =
            _state.update { state -> state.communityAdmin?.takeIf { it.relayUrl == relayUrl }?.let { state.copy(communityAdmin = transform(it)) } ?: state }
        updateSheet { it.copy(busy = it.busy + member.pubkey, error = null) }
        launch {
            try {
                when (val outcome = account.sendCommunityCommand(relayUrl, draft)) {
                    is GroupPublishOutcome.Published -> {
                        // 新しい 13534 が出るまでは、変更後の形を見せる。
                        updateSheet { current ->
                            val members = current.members.orEmpty().mapNotNull { m ->
                                when {
                                    m.pubkey != member.pubkey -> m
                                    action == CommunityAction.Remove -> null
                                    action == CommunityAction.Promote -> m.copy(role = CommunityRole.Admin)
                                    else -> m.copy(role = CommunityRole.Member)
                                }
                            }
                            current.copy(members = members)
                        }
                        // リレーが新しい一覧を出すのを少し待ってから読み直す。
                        delay(COMMUNITY_ROSTER_REREAD_DELAY_MS)
                        applyRoster(relayUrl, account.fetchCommunityRoster(relayUrl))
                    }
                    is GroupPublishOutcome.Rejected -> updateSheet { it.copy(error = adminRejectionMessage(outcome.reason)) }
                    is GroupPublishOutcome.Failed -> updateSheet { it.copy(error = "送信できませんでした: ${outcome.reason}") }
                }
            } finally {
                updateSheet { it.copy(busy = it.busy - member.pubkey) }
            }
        }
    }

    fun setInviteTtl(seconds: Long) {
        _state.update { state -> state.communityAdmin?.let { state.copy(communityAdmin = it.copy(inviteTtlSeconds = seconds, inviteError = null)) } ?: state }
    }

    fun setInviteMaxUses(uses: Int?) {
        _state.update { state -> state.communityAdmin?.let { state.copy(communityAdmin = it.copy(inviteMaxUses = uses, inviteError = null)) } ?: state }
    }

    /** 招待リンクを作る。作ったものはシートの一覧の先頭に足す（保存しない・ログに出さない）。 */
    fun mintInvite() {
        val account = account ?: return
        val sheet = _state.value.communityAdmin?.takeIf { !it.isMinting } ?: return
        val request = runCatching { InviteRequest(sheet.inviteTtlSeconds, sheet.inviteMaxUses) }.getOrNull() ?: return
        _state.update { it.copy(communityAdmin = sheet.copy(isMinting = true, inviteError = null)) }
        launch {
            val result = account.mintInvite(sheet.relayUrl, request)
            _state.update { state ->
                val current = state.communityAdmin?.takeIf { it.relayUrl == sheet.relayUrl } ?: return@update state
                state.copy(
                    communityAdmin = when (result) {
                        is InviteMintResult.Minted -> current.copy(isMinting = false, invites = listOf(result.invite) + current.invites)
                        is InviteMintResult.Refused -> current.copy(isMinting = false, inviteError = result.message)
                    },
                )
            }
        }
    }

    /** DM の読み直し（一覧に入るたび）。 */
    fun refreshDm() {
        dm?.refreshIfStarted()
    }

    /** DM の会話を開いた。 */
    fun markDmRead(key: String) {
        dm?.markRead(key)
    }

    /** グループを開いた。いまの最新の発言までを既読にする。 */
    fun markRead(ref: GroupRef) {
        val me = account?.pubkey ?: return
        lastRead[ref.key] = maxOf(lastMessageByRef[ref]?.createdAt ?: 0L, kotlin.time.Clock.System.now().epochSeconds)
        unreadCounts.remove(ref)
        publish()
        launch {
            // 保存済みの既読位置を先に取り込む。読み込む前に書くと、ほかのグループの分を消してしまう。
            loadLastRead()
            LocalSettingsStorage.putString(lastReadKey(me), encodeLastRead(lastRead))
        }
    }

    // ---- 作成シート ----

    /** 作成先。ヘッダーで選んでいるリレー・コミュニティを先に出す。 */
    private fun createTargets(buzzOnly: Boolean): List<ChatRelaySummary> {
        val selected = (_state.value.selection as? ChatSelection.Relay)?.relayUrl
        return _state.value.relays
            .filter { !buzzOnly || it.flavor == GroupFlavor.Buzz }
            .sortedByDescending { it.relayUrl == selected }
    }

    /** 種類はメニューで選ぶ。フォーラムの作成先は Buzz のコミュニティだけ。 */
    fun openCreateChannel(type: NewGroupType = NewGroupType.Chat) {
        val targets = createTargets(buzzOnly = type == NewGroupType.Forum)
        val target = targets.firstOrNull() ?: return
        _state.update { it.copy(create = CreateSheetState.Channel(targets = targets, target = target, type = type)) }
    }

    fun openCreateDm() {
        val targets = createTargets(buzzOnly = true)
        val target = targets.firstOrNull() ?: return
        _state.update { it.copy(create = CreateSheetState.Dm(targets = targets, target = target, candidates = dmCandidates(target.relayUrl))) }
    }

    /** 宛先の候補。名前で探せるよう、プロフィールも取りに行く。 */
    private fun dmCandidates(relayUrl: String): List<String> {
        val candidates = buzzPeople[relayUrl].orEmpty().sorted().take(DM_CANDIDATE_LIMIT)
        if (candidates.isNotEmpty()) {
            launch { ProfileRepository.ensureProfiles(candidates.toSet(), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = relayUrl) }
        }
        return candidates
    }

    fun closeCreateSheet() {
        _state.update { it.copy(create = CreateSheetState.Closed) }
    }

    fun updateCreateChannel(transform: (CreateSheetState.Channel) -> CreateSheetState.Channel) {
        _state.update { state ->
            val sheet = state.create as? CreateSheetState.Channel ?: return@update state
            if (sheet.isSubmitting) state else state.copy(create = transform(sheet).copy(isSubmitting = false, error = null))
        }
    }

    fun setCreateTarget(target: ChatRelaySummary) {
        _state.update { state ->
            when (val sheet = state.create) {
                is CreateSheetState.Channel -> if (sheet.isSubmitting) state else state.copy(create = sheet.copy(target = target, error = null))
                is CreateSheetState.Dm -> if (sheet.isSubmitting) state else state.copy(
                    // 相手はコミュニティごとに違うので、選び直す。
                    create = sheet.copy(target = target, picked = emptyList(), candidates = dmCandidates(target.relayUrl), error = null),
                )
                CreateSheetState.Closed -> state
            }
        }
    }

    fun submitCreateChannel(onCreated: (GroupRef, GroupLayout) -> Unit) {
        val account = account ?: return
        val sheet = (_state.value.create as? CreateSheetState.Channel)?.takeIf { it.canSubmit } ?: return
        val buzzTarget = sheet.target.flavor == GroupFlavor.Buzz
        val spec = NewGroupSpec(
            groupId = if (buzzTarget) newBuzzChannelId() else newStandardGroupId(),
            name = canonicalGroupName(sheet.name),
            about = sheet.about.trim(),
            // 標準の NIP-29 には種類の指定が無い。
            type = if (buzzTarget) sheet.type else NewGroupType.Chat,
            visibility = sheet.visibility,
        )
        _state.update { it.copy(create = sheet.copy(isSubmitting = true, error = null)) }
        launch {
            val result = account.createGroup(sheet.target.relayUrl, sheet.target.flavor, spec)
            // 作成中にシートを閉じて別のシートを開いていたら、そのシートには触らない。
            val current = _state.value.create
            val stillOpen = current is CreateSheetState.Channel && current.isSubmitting && current.target == sheet.target && current.name == sheet.name
            when (result) {
                is GroupCreateResult.Created -> {
                    onGroupCreated(result, spec, sheet.target.flavor)
                    if (stillOpen) {
                        closeCreateSheet()
                        onCreated(result.ref, layoutOf(result.ref))
                    }
                }
                is GroupCreateResult.Rejected -> {
                    val message = createRejectionMessage(result.kind, result.reason)
                    if (stillOpen) _state.update { it.copy(create = sheet.copy(isSubmitting = false, error = message)) }
                    else _state.update { it.copy(creationMessage = "「${spec.name}」: $message") }
                }
                // 記録が残っている。一覧の上の帯からやり直せる。
                is GroupCreateResult.Unfinished -> {
                    if (stillOpen) closeCreateSheet()
                    _state.update { it.copy(creationMessage = "「${spec.name}」の作成が終わりませんでした: ${result.reason}") }
                }
            }
        }
    }

    /** 作れたグループを、リレーの読み直しを待たずに一覧へ足す。 */
    private fun onGroupCreated(result: GroupCreateResult.Created, spec: NewGroupSpec, flavor: GroupFlavor) {
        val ref = result.ref
        metadataByRef[ref] = result.metadata ?: GroupMetadata(
            groupId = ref.groupId,
            name = spec.name,
            picture = "",
            about = spec.about,
            isPrivate = spec.visibility == NewGroupVisibility.Private,
            isRestricted = spec.visibility == NewGroupVisibility.Private,
            isHidden = false,
            isClosed = spec.visibility == NewGroupVisibility.Private,
            supportedKinds = null,
            createdAt = 0,
            channelType = if (flavor == GroupFlavor.Buzz) (if (spec.type == NewGroupType.Forum) "forum" else "stream") else null,
        )
        if (flavor == GroupFlavor.Buzz) {
            buzzJoined[ref.relayUrl] = buzzJoined[ref.relayUrl].orEmpty() + ref
            buzzChannels[ref.relayUrl] = (buzzChannels[ref.relayUrl].orEmpty() + ref).distinct()
        }
        // 作った直後に未読にならないよう、開いたことにしておく。
        markRead(ref)
        if (!result.confirmed) {
            _state.update { it.copy(creationMessage = "作成しました。一覧に出るまで少しかかることがあります") }
        }
    }

    fun resumeCreation(pending: PendingCreation, onCreated: (GroupRef, GroupLayout) -> Unit) {
        val account = account ?: return
        if (_state.value.resumingCreation != null) return
        _state.update { it.copy(resumingCreation = pending.ref) }
        launch {
            try {
                when (val result = account.resumeCreation(pending)) {
                    is GroupCreateResult.Created -> {
                        onGroupCreated(result, pending.spec, pending.flavor)
                        onCreated(result.ref, layoutOf(result.ref))
                    }
                    is GroupCreateResult.Rejected ->
                        _state.update { it.copy(creationMessage = "「${pending.name}」: ${createRejectionMessage(result.kind, result.reason)}") }
                    is GroupCreateResult.Unfinished ->
                        _state.update { it.copy(creationMessage = "「${pending.name}」の作成が終わりませんでした: ${result.reason}") }
                }
            } finally {
                _state.update { it.copy(resumingCreation = null) }
            }
        }
    }

    fun discardCreation(pending: PendingCreation) {
        val account = account ?: return
        launch { account.discardCreation(pending) }
    }

    fun dismissCreationMessage() {
        _state.update { it.copy(creationMessage = null) }
    }

    fun onDmQueryChange(query: String) {
        _state.update { state ->
            val sheet = state.create as? CreateSheetState.Dm ?: return@update state
            val typed = com.nostr.torinos.ui.post.parsePubkeyInput(query.trim())?.takeIf { it != account?.pubkey }
            state.copy(create = sheet.copy(query = query, typedPubkey = typed, error = null))
        }
        (_state.value.create as? CreateSheetState.Dm)?.let { sheet ->
            val typed = sheet.typedPubkey ?: return@let
            launch { ProfileRepository.ensureProfiles(setOf(typed), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = sheet.target.relayUrl) }
        }
    }

    fun toggleDmRecipient(pubkey: String) {
        _state.update { state ->
            val sheet = (state.create as? CreateSheetState.Dm)?.takeIf { !it.isSubmitting } ?: return@update state
            val picked = when {
                pubkey in sheet.picked -> sheet.picked - pubkey
                sheet.canPickMore -> sheet.picked + pubkey
                else -> sheet.picked
            }
            // 選んだら、入力した検索語は消す。
            state.copy(create = sheet.copy(picked = picked, query = if (pubkey in sheet.picked) sheet.query else "", typedPubkey = null, error = null))
        }
    }

    fun submitDm(onOpened: (GroupRef) -> Unit) {
        val account = account ?: return
        val sheet = (_state.value.create as? CreateSheetState.Dm)?.takeIf { it.canSubmit } ?: return
        _state.update { it.copy(create = sheet.copy(isSubmitting = true, error = null)) }
        launch {
            val outcome = account.openBuzzDm(sheet.target.relayUrl, sheet.picked)
            val current = _state.value.create
            val stillOpen = current is CreateSheetState.Dm && current.isSubmitting && current.target == sheet.target
            when (outcome) {
                is BuzzDmOutcome.Opened -> {
                    val ref = outcome.ref
                    val me = account.pubkey
                    // リレーの読み直しを待たずに一覧へ出す。
                    if (metadataByRef[ref] == null) {
                        metadataByRef[ref] = GroupMetadata(
                            groupId = ref.groupId, name = "", picture = "", about = "", isPrivate = true, isRestricted = true,
                            isHidden = true, isClosed = true, supportedKinds = null, createdAt = 0, channelType = "dm",
                            participants = listOf(me) + sheet.picked,
                        )
                    }
                    buzzJoined[ref.relayUrl] = buzzJoined[ref.relayUrl].orEmpty() + ref
                    markRead(ref)
                    if (stillOpen) {
                        closeCreateSheet()
                        onOpened(ref)
                    }
                }
                is BuzzDmOutcome.Failed -> {
                    if (stillOpen) _state.update { it.copy(create = sheet.copy(isSubmitting = false, error = "DM を開けませんでした: ${outcome.reason}")) }
                    else _state.update { it.copy(creationMessage = "DM を開けませんでした: ${outcome.reason}") }
                }
            }
        }
    }

    // ---- 参加シート ----

    fun openJoinSheet(prefill: String = "") {
        _state.update { it.copy(join = JoinSheetState.Input(text = prefill)) }
        if (prefill.isNotBlank()) submitJoinInput() else loadPublicGroups()
    }

    private var publicGroupsLoadedAt: Long? = null

    /**
     * 公開グループを、リレー設定に登録しているリレーと参加中の標準のリレーから並行して読む（日本向けなので、登録外のリレーは探さない）。
     * 登録しているリレーは NIP-11 で NIP-29 に対応しているものだけ読む（一般のリレーにある、誰でも署名できる 39000 を出さないため）。
     * リレーごとに読めた分から出す。5分以内に読んだものがあれば読み直さない。
     */
    private fun loadPublicGroups() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (_state.value.publicGroupsLoading) return
        publicGroupsLoadedAt?.let { if (now - it < PUBLIC_GROUPS_TTL_MILLIS) return }
        val joinedRelays = standardRefs().map { it.relayUrl.trimEnd('/') }.toSet()
        val relays = (joinedRelays + com.nostr.torinos.network.RelayStore.enabledRelayUrlsSnapshot().map { it.trimEnd('/') })
            .distinct()
            .filter { it !in buzz }
        _state.update { it.copy(publicGroupsLoading = true, publicGroups = emptyList()) }
        launch {
            try {
                coroutineScope {
                    relays.map { relayUrl ->
                        async {
                            val info = info(relayUrl)
                            // Buzz のリレーは参加の手順が違うので、ここでは探さない。
                            if (groupFlavorOf(info?.supportedExtensions.orEmpty()) == GroupFlavor.Buzz) return@async
                            if (relayUrl !in joinedRelays && NIP29 !in info?.supportedNips.orEmpty()) return@async
                            val relayKey = info?.self
                            val events = fetchFrom(
                                relayUrl,
                                listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = relayKey?.let(::listOf), limit = 500)),
                            )
                            val entries = publicGroupEntries(relayUrl, events, relayKey)
                            entries.forEach { metadataByRef[it.ref] = it.metadata }
                            _state.update { it.copy(publicGroups = it.publicGroups + entries) }
                        }
                    }.awaitAll()
                }
                publicGroupsLoadedAt = now
            } finally {
                _state.update { it.copy(publicGroupsLoading = false) }
            }
        }
    }

    /** 公開グループの検索結果から選んだグループの確認へ進む。 */
    fun pickPublicGroup(ref: GroupRef) {
        val input = (_state.value.join as? JoinSheetState.Input) ?: return
        _state.update { it.copy(join = input.copy(isResolving = true, error = null)) }
        launch { resolveGroup(ref, inviteCode = null, knownRelayPubkey = null) }
    }

    fun closeJoinSheet() {
        _state.update { it.copy(join = JoinSheetState.Closed) }
    }

    fun onJoinInputChange(text: String) {
        _state.update { it.copy(join = JoinSheetState.Input(text = text)) }
    }

    /** 入力を判定し、グループなら確認へ、リレーならそのリレーの一覧（Buzz なら参加の確認）を開く。 */
    fun submitJoinInput() {
        val input = (_state.value.join as? JoinSheetState.Input) ?: return
        when (val parsed = parseGroupInput(input.text)) {
            GroupInput.Invalid -> _state.update {
                it.copy(join = input.copy(error = "グループのリンク（naddr）、招待リンク、リレーのアドレスを入力してください"))
            }
            GroupInput.BareBuzzCode -> _state.update {
                it.copy(join = input.copy(error = "招待リンク全体を貼ってください"))
            }
            is GroupInput.BuzzInvite -> {
                _state.update { it.copy(join = input.copy(isResolving = true, error = null)) }
                launch { resolveBuzz(parsed.relayUrl, parsed.code, input) }
            }
            is GroupInput.Relay -> {
                _state.update { it.copy(join = input.copy(isResolving = true, error = null)) }
                launch {
                    val info = info(parsed.relayUrl)
                    if (groupFlavorOf(info?.supportedExtensions.orEmpty()) == GroupFlavor.Buzz) {
                        resolveBuzz(parsed.relayUrl, null, input)
                    } else {
                        closeJoinSheet()
                        select(ChatSelection.Relay(parsed.relayUrl))
                    }
                }
            }
            is GroupInput.Group -> {
                _state.update { it.copy(join = input.copy(isResolving = true, error = null)) }
                launch { resolveGroup(parsed.ref, parsed.inviteCode, parsed.relayPubkey) }
            }
        }
    }

    /** Buzz: NIP-11 で先に判定し（外部 ID 連携が必須なら参加できない）、招待コードがあれば規約を読む。 */
    private suspend fun resolveBuzz(relayUrl: String, code: String?, input: JoinSheetState.Input) {
        val info = info(relayUrl)
        if (info?.requiresNipFi == true) {
            _state.update { it.copy(join = input.copy(isResolving = false, error = "このコミュニティは外部 ID 連携が必要なため、ToriNos では参加できません")) }
            return
        }
        if (relayUrl in buzz && code == null) {
            closeJoinSheet()
            select(ChatSelection.Relay(relayUrl))
            return
        }
        val policy = if (code != null) {
            runCatching { account?.buzzJoin?.fetchPolicy(relayUrl) }.getOrElse { error ->
                _state.update { it.copy(join = input.copy(isResolving = false, error = error.message ?: "参加規約を読めませんでした")) }
                return
            }
        } else {
            null
        }
        _state.update {
            it.copy(
                join = JoinSheetState.Buzz(
                    relayUrl = relayUrl,
                    name = info?.name,
                    code = code,
                    policy = policy,
                    inviteOnly = 43 in info?.supportedNips.orEmpty(),
                ),
            )
        }
    }

    fun updateBuzzConsent(agreed: Boolean? = null, ageConfirmed: Boolean? = null) {
        val current = _state.value.join as? JoinSheetState.Buzz ?: return
        _state.update {
            it.copy(join = current.copy(agreed = agreed ?: current.agreed, ageConfirmed = ageConfirmed ?: current.ageConfirmed))
        }
    }

    /** Buzz: 招待コードを使って参加し、AUTH で参加できたことを確かめる。コードが無ければ確かめるだけ。 */
    fun confirmBuzz(onJoined: (String) -> Unit) {
        val account = account ?: return
        val current = _state.value.join as? JoinSheetState.Buzz ?: return
        if (!current.canSubmit) return
        _state.update { it.copy(join = current.copy(isJoining = true, error = null)) }
        launch {
            fun fail(message: String) = _state.update { it.copy(join = current.copy(isJoining = false, error = message)) }
            val wasMember = current.relayUrl in buzz
            if (current.code != null) {
                val result = runCatching {
                    account.buzzJoin.claim(current.relayUrl, current.code, current.policy, current.ageConfirmed)
                }.getOrElse { error ->
                    fail(error.message ?: "参加できませんでした")
                    return@launch
                }
                if (result is BuzzJoinResult.Refused) {
                    fail(result.message)
                    return@launch
                }
            }
            account.addBuzzCommunity(current.relayUrl)
            when (val check = account.checkBuzzMembership(current.relayUrl)) {
                BuzzMembershipCheck.Member -> {
                    account.recordBuzzJoined(current.relayUrl)
                    closeJoinSheet()
                    onJoined(current.relayUrl)
                    select(ChatSelection.Relay(current.relayUrl))
                }
                is BuzzMembershipCheck.NotMember -> {
                    // 招待を使えたあとは、認証の失敗だけで外さない（署名の拒否・時間切れでも参加は済んでいる）。
                    if (!wasMember && current.code == null) account.removeBuzzCommunity(current.relayUrl)
                    fail(
                        if (current.code == null) "このアカウントはこのコミュニティに参加していません。招待リンクを使ってください"
                        else "参加を確認できませんでした: ${check.reason}",
                    )
                }
                is BuzzMembershipCheck.Unknown -> {
                    if (!wasMember && current.code == null) account.removeBuzzCommunity(current.relayUrl)
                    fail("参加を確認できませんでした: ${check.reason}")
                }
            }
        }
    }

    /** グループ情報（kind 39000）を読み、確認の段階へ進める。 */
    private suspend fun resolveGroup(ref: GroupRef, inviteCode: String?, knownRelayPubkey: String?) {
        val info = info(ref.relayUrl)
        // リレー自身が名乗る鍵（NIP-11 の self）を優先する。リンクに埋め込まれた鍵は、それが分からないときだけ使う。
        val relayKey = info?.self ?: knownRelayPubkey
        val result = fetchResultFrom(
            ref.relayUrl,
            listOf(NostrFilter(kinds = listOf(GROUP_METADATA_KIND), authors = relayKey?.let(::listOf), dTags = listOf(ref.groupId))),
        )
        val metadata = latestGroupMetadata(result.events, relayKey)[ref.groupId]
        metadata?.let { metadataByRef[ref] = it }
        val outcome = result.relays[ref.relayUrl]?.outcome
        val needsAuth = metadata == null && outcome is RelayOutcome.Closed && outcome.retry == RetryDisposition.RetryAfterAuth
        _state.update {
            it.copy(join = JoinSheetState.Confirm(ref, inviteCode, metadata, info?.name, needsAuth = needsAuth, relayPubkey = relayKey))
        }
    }

    /** 確認の段階で、リレーへの NIP-42 の認証を許可してグループ情報を読み直す。 */
    fun authenticateAndReload() {
        val account = account ?: return
        val confirm = (_state.value.join as? JoinSheetState.Confirm) ?: return
        _state.update { it.copy(join = confirm.copy(isJoining = true, error = null)) }
        launch {
            account.allowAuth(confirm.ref.relayUrl)
            resolveGroup(confirm.ref, confirm.inviteCode, confirm.relayPubkey)
        }
    }

    /** 参加要求（kind 9021）を送る。送れたら [onJoined] でチャット画面を開く。 */
    fun confirmJoin(onJoined: (GroupRef) -> Unit) {
        val account = account ?: return
        val confirm = (_state.value.join as? JoinSheetState.Confirm) ?: return
        if (confirm.isJoining) return
        _state.update { it.copy(join = confirm.copy(isJoining = true, error = null)) }
        launch {
            // 参加はグループのリレーへの書き込みなので、求められれば NIP-42 で公開鍵を伝える（シートで案内済み）。
            account.allowAuth(confirm.ref.relayUrl)
            val outcome = account.publishToGroup(
                confirm.ref,
                buildGroupJoinRequest(confirm.ref.groupId, confirm.inviteCode, ""),
            )
            when {
                outcome is GroupPublishOutcome.Published -> {
                    account.markPending(confirm.ref)
                    closeJoinSheet()
                    onJoined(confirm.ref)
                }
                outcome is GroupPublishOutcome.Rejected && classifyJoinRejection(outcome.reason) == JoinRejection.AlreadyMember -> {
                    runCatching { account.recordJoined(JoinedGroup(confirm.ref, confirm.metadata?.name), userInitiated = true) }
                    closeJoinSheet()
                    onJoined(confirm.ref)
                }
                // 審査待ちは `OK false` とメッセージで返る（NIP-29）。承認待ちとして一覧に出す。
                outcome is GroupPublishOutcome.Rejected && classifyJoinRejection(outcome.reason) == JoinRejection.PendingReview -> {
                    account.markPending(confirm.ref)
                    closeJoinSheet()
                    onJoined(confirm.ref)
                }
                else -> {
                    val reason = when (outcome) {
                        is GroupPublishOutcome.Rejected -> outcome.reason
                        is GroupPublishOutcome.Failed -> outcome.reason
                        else -> ""
                    }
                    _state.update { it.copy(join = confirm.copy(isJoining = false, error = "参加できませんでした: $reason")) }
                }
            }
        }
    }
}

private const val LAST_MESSAGE_FILTERS_PER_REQUEST = 8
private const val METADATA_IDS_PER_REQUEST = 50
private const val DM_CANDIDATE_LIMIT = 300
private const val PUBLIC_GROUPS_TTL_MILLIS = 5 * 60 * 1000L
private const val NIP29 = 29

private fun lastReadKey(pubkey: String) = "chat.lastRead.$pubkey"

private fun selectionKey(pubkey: String) = "chat.selectedRelay.$pubkey"

private fun viewKey(pubkey: String) = "chat.view.$pubkey"

private fun hideBotsKey(pubkey: String) = "chat.hideBots.$pubkey"

/** 「すべて」は何も保存しない。リレーは URL をそのまま保存する。 */
internal fun encodeChatSelection(selection: ChatSelection): String? = when (selection) {
    ChatSelection.All -> null
    is ChatSelection.Relay -> selection.relayUrl
    ChatSelection.Direct -> DIRECT_SELECTION_VALUE
}

/** 「DM」の選択の保存値。リレーの URL とは重ならない。 */
private const val DIRECT_SELECTION_VALUE = "dm"

internal fun decodeChatSelection(value: String?): ChatSelection {
    val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return ChatSelection.All
    if (url == DIRECT_SELECTION_VALUE) return ChatSelection.Direct
    val normalized = com.nostr.torinos.network.normalizeRelayUrl(url) ?: return ChatSelection.All
    return ChatSelection.Relay(normalized)
}

/** `<relay>'<group>\t<秒>` を1行ずつ。 */
internal fun encodeLastRead(values: Map<String, Long>): String =
    values.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

internal fun decodeLastRead(stored: String?): Map<String, Long> =
    stored.orEmpty().lineSequence().mapNotNull { line ->
        val key = line.substringBefore('\t', missingDelimiterValue = "")
        val value = line.substringAfter('\t', missingDelimiterValue = "").toLongOrNull()
        if (key.isEmpty() || value == null) null else key to value
    }.toMap()

/** コミュニティの管理で、プロフィールを先に取る人数。 */
private const val COMMUNITY_PROFILE_LIMIT = 300

/** コミュニティの命令の後、新しいメンバー一覧を読み直すまでの待ち。 */
private const val COMMUNITY_ROSTER_REREAD_DELAY_MS = 1_500L
