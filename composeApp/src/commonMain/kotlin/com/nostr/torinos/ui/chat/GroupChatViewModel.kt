package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.BUZZ_AGENT_MESSAGE_KIND
import com.nostr.torinos.chat.BUZZ_EDIT_KIND
import com.nostr.torinos.chat.BUZZ_FORUM_COMMENT_KIND
import com.nostr.torinos.chat.BUZZ_FORUM_POST_KIND
import com.nostr.torinos.chat.BUZZ_TYPING_KIND
import com.nostr.torinos.chat.BUZZ_PRESENCE_KIND
import com.nostr.torinos.chat.PresenceBook
import com.nostr.torinos.chat.PresenceStatus
import com.nostr.torinos.chat.presenceMembersSubjects
import com.nostr.torinos.chat.TypingThrottle
import com.nostr.torinos.chat.TypingTracker
import com.nostr.torinos.chat.ChatAccount
import com.nostr.torinos.chat.GROUP_ADMINS_KIND
import com.nostr.torinos.chat.GROUP_CHAT_KIND
import com.nostr.torinos.chat.GROUP_COMMENT_KIND
import com.nostr.torinos.chat.GROUP_DELETE_EVENT_KIND
import com.nostr.torinos.chat.GROUP_MEMBERS_KIND
import com.nostr.torinos.chat.GroupLeaveOutcome
import com.nostr.torinos.chat.GROUP_METADATA_KIND
import com.nostr.torinos.chat.GROUP_PUT_USER_KIND
import com.nostr.torinos.chat.GROUP_REMOVE_USER_KIND
import com.nostr.torinos.chat.GROUP_THREAD_KIND
import com.nostr.torinos.chat.GroupEventDraft
import com.nostr.torinos.chat.GroupFlavor
import com.nostr.torinos.chat.GroupMembership
import com.nostr.torinos.chat.GroupMessage
import com.nostr.torinos.chat.GroupMetadata
import com.nostr.torinos.chat.GroupPublishOutcome
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.GroupReplyTarget
import com.nostr.torinos.chat.GroupTimeline
import com.nostr.torinos.chat.JoinedGroup
import com.nostr.torinos.chat.PreviousReferenceWindow
import com.nostr.torinos.chat.buildBuzzEdit
import com.nostr.torinos.chat.buildBuzzMessage
import com.nostr.torinos.chat.buildForumComment
import com.nostr.torinos.chat.buildForumPost
import com.nostr.torinos.chat.buildGroupChatMessage
import com.nostr.torinos.chat.withMentionTags
import com.nostr.torinos.chat.buildGroupComment
import com.nostr.torinos.chat.buildGroupDeletion
import com.nostr.torinos.chat.buildGroupJoinRequest
import com.nostr.torinos.chat.buildGroupReaction
import com.nostr.torinos.chat.BuzzEmojiPalette
import com.nostr.torinos.chat.GROUP_RECENT_REACTION_LIMIT
import com.nostr.torinos.chat.decodeGroupRecent
import com.nostr.torinos.chat.encodeGroupRecent
import com.nostr.torinos.chat.groupRecentStorageKey
import com.nostr.torinos.chat.isGroupReactionSendable
import com.nostr.torinos.chat.ownReactionIdFor
import com.nostr.torinos.chat.recordGroupRecent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.chat.buildGroupThread
import com.nostr.torinos.chat.classifyJoinRejection
import com.nostr.torinos.chat.JoinRejection
import com.nostr.torinos.chat.withAttachments
import com.nostr.torinos.chat.buzzMediaHost
import com.nostr.torinos.chat.buzzMembershipOf
import com.nostr.torinos.chat.groupFlavorOf
import com.nostr.torinos.chat.latestGroupMetadata
import com.nostr.torinos.chat.membershipOf
import com.nostr.torinos.chat.groupMemberList
import com.nostr.torinos.chat.parseGroupAdmins
import com.nostr.torinos.chat.CreateRejection
import com.nostr.torinos.chat.GroupEdit
import com.nostr.torinos.chat.buildBuzzEditMetadata
import com.nostr.torinos.chat.buildStandardEditMetadata
import com.nostr.torinos.chat.canEditGroup
import com.nostr.torinos.chat.classifyCreateRejection
import com.nostr.torinos.chat.groupEditOf
import com.nostr.torinos.chat.isGroupEditSavable
import com.nostr.torinos.chat.withEdit
import com.nostr.torinos.chat.ChannelAuthority
import com.nostr.torinos.chat.ChannelRole
import com.nostr.torinos.chat.CommunityRosterLoad
import com.nostr.torinos.chat.adminRejectionMessage
import com.nostr.torinos.chat.applyPendingRoles
import com.nostr.torinos.chat.buildAdminDeletion
import com.nostr.torinos.chat.buildChannelArchive
import com.nostr.torinos.chat.buildChannelDelete
import com.nostr.torinos.chat.buildChannelPutUser
import com.nostr.torinos.chat.buildChannelRemoveUser
import com.nostr.torinos.chat.canChangeRole
import com.nostr.torinos.chat.canRemoveMember
import com.nostr.torinos.chat.channelAuthority
import com.nostr.torinos.chat.channelRoles
import com.nostr.torinos.chat.latestMemberListId
import com.nostr.torinos.chat.roleChoicesFor
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayAuthState
import com.nostr.torinos.network.RelayInformationRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.fetchFiniteEvents
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.random.Random
import com.nostr.torinos.chat.GroupRoomCache
import com.nostr.torinos.util.appLog

/** 投稿欄の状態（設計書 4.6）。 */
enum class GroupComposerState {
    Writable,
    /** 未参加。参加すれば書ける。 */
    JoinToWrite,
    /** 参加要求を送って承認待ち。 */
    Pending,
    /** 招待制で非メンバーなど、書けない。 */
    ReadOnly,
    /** 署名できないアカウント・未ログイン。 */
    NoSigner,
    /** 入室直後で、リレーからメンバー情報がまだ届いていない。 */
    Checking,
}

/** 画面の種類。 */
sealed interface GroupRoomMode {
    data object Chat : GroupRoomMode
    data class Thread(val rootId: String) : GroupRoomMode
    data object Forum : GroupRoomMode
}

/** グループ情報の編集（名前・説明・公開範囲）。 */
data class GroupEditSheet(
    val current: GroupMetadata,
    val edit: GroupEdit,
    /** 公開範囲・参加した人の役割を変えられる（Buzz のみ）。 */
    val canChangeVisibility: Boolean,
    /** 「一覧に出さない」を変えられる（リレーが `buzz-unlisted-channels` を名乗る Buzz）。 */
    val canSetUnlisted: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = !isSaving && isGroupEditSavable(current, edit, if (canChangeVisibility) GroupFlavor.Buzz else GroupFlavor.Standard)
}

/** チャンネルにメンバーを加えるシート（docs/buzz-admin-design.md 3.1）。 */
data class AddMembersSheet(
    val query: String = "",
    val picked: List<String> = emptyList(),
    /** コミュニティのメンバーのうち、まだチャンネルにいない人。 */
    val candidates: List<String> = emptyList(),
    /** 入力が公開鍵（npub / hex）として読めたとき、その人。 */
    val typedPubkey: String? = null,
    val role: ChannelRole = ChannelRole.Member,
    val isLoadingCandidates: Boolean = true,
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !isSubmitting && picked.isNotEmpty()
}

data class GroupChatUiState(
    val ref: GroupRef,
    val mode: GroupRoomMode = GroupRoomMode.Chat,
    val flavor: GroupFlavor = GroupFlavor.Standard,
    val metadata: GroupMetadata? = null,
    val relayName: String? = null,
    /** チャット・スレッドの返信・フォーラムの投稿（画面の種類による）。 */
    val messages: List<GroupMessage> = emptyList(),
    /** スレッドの画面のルート。 */
    val threadRoot: GroupMessage? = null,
    /**
     * スレッドの画面で折りたたんで出す、ミュート中の人の発言の作者。
     * スレッドではミュート中の人の発言も「ミュートしています」として残し、押すと開く。チャンネルの画面には出さない。
     */
    val collapsedAuthors: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val canLoadOlder: Boolean = true,
    /** リレーが示したメンバー状態。null はメンバー情報(kind 9000/9001・39002)がまだ無い。 */
    val membership: GroupMembership? = null,
    /** 最初の EOSE(または拒否)を受け取り、リレーの答えが出そろった。 */
    val membershipChecked: Boolean = false,
    /** 自分の参加済み一覧(kind 10009)にある。 */
    val locallyJoined: Boolean = false,
    /** 退出の送信と参加中の一覧の更新を待っている。 */
    val isLeaving: Boolean = false,
    val isPending: Boolean = false,
    /** このリレーが NIP-42 の認証を求めていて、まだ許可していない。 */
    val authConsentNeeded: Boolean = false,
    /** 認証に失敗した理由。 */
    val authFailure: String? = null,
    /** メンバーでないので読めない（`private` や `restricted:` の拒否）。 */
    val readRestricted: Boolean = false,
    val replyTo: GroupMessage? = null,
    val editing: GroupMessage? = null,
    val isSending: Boolean = false,
    val notice: String? = null,
    /** 送ったばかりの投稿（編集は除く）。一覧がその位置までスクロールしたら [GroupChatViewModel.consumeSent] で消す。 */
    val sentEventId: String? = null,
    val hasSigner: Boolean = true,
    /** Buzz の画像を読むための署名を許可した（外部の署名器のとき）。 */
    val imagesUnlocked: Boolean = false,
    /** 送る前の添付（アップロード済み）。 */
    val attachments: List<com.nostr.torinos.chat.MessageAttachment> = emptyList(),
    val isUploading: Boolean = false,
    /** 自分が作成者か管理者で、グループ情報を編集できる（実際の可否はリレーが決める）。 */
    val canEdit: Boolean = false,
    val editSheet: GroupEditSheet? = null,
    /** 入力中の人（Buzz のチャット・スレッドだけ。入力中になった順）。 */
    val typingAuthors: List<String> = emptyList(),
    /** 在席が分かっている人（Buzz のチャット・スレッドだけ。不明の人は入らない）。 */
    val presence: Map<String, PresenceStatus> = emptyMap(),
    /** リレーが出すメンバー一覧（kind 39002）。出さないリレー・非公開のグループでは null。 */
    val members: List<String>? = null,
    /** 管理者（kind 39001）。 */
    val admins: Set<String> = emptySet(),
    /** Buzz: メンバーごとの役割（kind 39002。送った変更を重ねたもの）。 */
    val roles: Map<String, ChannelRole> = emptyMap(),
    /** Buzz: 自分が owner / admin としてできること（表示のための判定）。 */
    val authority: ChannelAuthority = ChannelAuthority(),
    /** 役割の変更・外すを送っている人。 */
    val busyMembers: Set<String> = emptySet(),
    /** メンバーの管理の失敗（グループの情報のシートに出す）。 */
    val memberError: String? = null,
    val addMembers: AddMembersSheet? = null,
    /** アーカイブ・削除を送っている。 */
    val isAdminBusy: Boolean = false,
    /** このコミュニティ（グループのリレー）で最近使ったリアクション（docs/buzz-emoji-reaction-design.md 4.7）。 */
    val communityRecent: List<ReactionOption> = emptyList(),
    /** Buzz のコミュニティの絵文字。 */
    val communityEmojis: CommunityEmojiState = CommunityEmojiState(),
    /** リアクションを送っているメッセージ。送り終わるまで付け外しを止める。 */
    val reactingMessageIds: Set<String> = emptySet(),
) {
    /** リレーがメンバーと示したか、リレーの情報が無く参加済み一覧にある。リレーが非メンバーと示したらそちらに従う。 */
    val isMember: Boolean
        get() = membership == GroupMembership.Member || (membership == null && locallyJoined)

    val composer: GroupComposerState
        get() = when {
            !hasSigner -> GroupComposerState.NoSigner
            isMember -> GroupComposerState.Writable
            // 承認待ちは手元の記録なので、リレーの答えを待たずに出す。
            isPending -> GroupComposerState.Pending
            membership == null && !membershipChecked -> GroupComposerState.Checking
            // restricted は「メンバーだけが書ける」なので、参加すれば書ける。closed は招待コードが無いと参加できない。
            metadata?.isClosed == true -> GroupComposerState.ReadOnly
            else -> GroupComposerState.JoinToWrite
        }

    val title: String get() = metadata?.displayName ?: ref.groupId
}

private const val PAGE_SIZE = 50
private const val THREAD_ROOTS_DEBOUNCE_MILLIS = 300L

/** Buzz のコミュニティの絵文字の読み込み状態。 */
data class CommunityEmojiState(
    val palette: BuzzEmojiPalette? = null,
    val isLoading: Boolean = false,
    /** 一度も読めていない（読めたことがあれば前の一覧を出し続ける）。 */
    val failed: Boolean = false,
)

/** リアクションを押したときに起きたこと。 */
enum class GroupReactionResult { Added, Removed, Ignored }

/** コミュニティの絵文字を読み直すまでの時間。 */
private const val COMMUNITY_EMOJI_MAX_AGE_MILLIS = 5 * 60_000L

/** コミュニティごとの「最近使った」の保存。同じコミュニティの画面（チャットとスレッド）が同時に書いても失わないよう、読み直して書く。 */
private val groupRecentMutex = Mutex()

/** 入力中の送信を諦めるまでの時間（それより遅れた入力中は古い）。 */
private const val TYPING_SEND_TIMEOUT_MILLIS = 3_000L

/**
 * メンバー一覧の並び。DM は参加者、それ以外はリレーのメンバー一覧（kind 39002）。管理者を先に、自分を最後に並べる。
 * 一覧の表示と、在席を見る人の優先順の両方に使う。
 */
internal fun memberListOrder(state: GroupChatUiState, ownPubkey: String?): List<String> {
    val metadata = state.metadata
    val all = (if (metadata?.layout == com.nostr.torinos.chat.GroupLayout.Direct) metadata.participants else state.members).orEmpty()
    return all.sortedWith(compareBy<String> { it == ownPubkey }.thenBy { it !in state.admins })
}

/** 発言者が変わってから在席を見る人を決め直すまでの待ち。 */
private const val PRESENCE_TARGETS_DEBOUNCE_MILLIS = 500L

/** 在席の一括取得の最短の間隔。 */
private const val PRESENCE_SNAPSHOT_GAP_MILLIS = 5_000L

private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** 入室の各段階の時刻をログに出す(実機での体感の計測用)。 */
private const val GROUP_OPEN_TRACE = false

/**
 * 1つのグループの画面（チャット・スレッド・フォーラム）。グループのリレーだけに問い合わせ、送る。
 * 標準の NIP-29 と Buzz の違いは、NIP-11 から決めた [GroupFlavor] で切り替える。
 */
class GroupChatViewModel(
    private val ref: GroupRef,
    private val account: ChatAccount?,
    /** ミュートした人。そのメッセージは出さない。 */
    private val mutedPubkeys: StateFlow<Set<String>>? = null,
    private val mode: GroupRoomMode = GroupRoomMode.Chat,
) : SafeViewModel() {
    val ownPubkey = account?.pubkey
    /** 保存した編集。リレーの新しいグループ情報が届くまで、これを表示する（基準の時刻, 編集後）。 */
    private var savedEdit: Pair<Long, GroupMetadata>? = null
    /** プロフィールを取りに行った DM の参加者。 */
    private var requestedParticipants: Set<String> = emptySet()
    /** 送った役割の変更（null は外した）と、送った時点の最新の 39002 の ID。それと違う 39002 が届いたら外す。 */
    private var pendingRoles: Map<String, Pair<ChannelRole?, String?>> = emptyMap()
    /** リレーが一覧に出さない設定（`unlisted`）を扱える。 */
    private var supportsUnlisted = false
    private var flavor = GroupFlavor.Standard
    private var timeline = GroupTimeline(ref.groupId, ownPubkey)
    private val previous = PreviousReferenceWindow()
    private val moderationEvents = mutableListOf<NostrEvent>()
    private val metadataEvents = mutableListOf<NostrEvent>()
    private var relayPubkey: String? = null
    private var linkPubkey: String? = null
    /** 参加中の一覧への保存は1画面で1回だけ試す。 */
    private var joinRecordAttempted = false
    private val pager = com.nostr.torinos.chat.OlderPager(PAGE_SIZE)
    private var session: SubscriptionSession? = null
    private var subscriptionJob: Job? = null
    /** 古いスレッドの元の発言の読み込み（スレッドへの返信の行に引用する）。 */
    private var threadRootsJob: Job? = null
    private val requestedThreadRoots = mutableSetOf<String>()
    // init のコルーチンがすぐ subscribe() を呼ぶことがあるので、init より前に初期化しておく。
    private val subscribeMutex = Mutex()
    private val flavorKnown = CompletableDeferred<Unit>()
    private val roomCache = account?.roomCache ?: GroupRoomCache()
    private val roomKey = when (mode) {
        GroupRoomMode.Chat -> "chat"
        is GroupRoomMode.Thread -> "thread:${mode.rootId}"
        GroupRoomMode.Forum -> "forum"
    }
    /** 前回の控えから出していて、まだリレーから届いていないイベント。 */
    private val cachedOnlyIds = mutableSetOf<String>()
    /** 最初の EOSE までに届いた、ページ対象の発言の数。 */
    private var firstPageCount = 0
    private var firstEoseSeen = false
    private val openedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private var tracedFirstEvent = false
    // ---- 入力中（Buzz のみ、docs/buzz-typing-indicator-design.md） ----
    private val typingTracker = TypingTracker(ownPubkey, ref.groupId)
    private val typingThrottle = TypingThrottle()
    private var typingSendJob: Job? = null
    private var typingTimerJob: Job? = null
    /** ログ用。同じ理由で送らなかったことを何度も出さない。 */
    private var lastTypingSkip: String? = null
    private var requestedTypingAuthors: Set<String> = emptySet()
    /** 入力中の単位（スレッドの画面ならそのルート、チャットならチャンネル直下）。 */
    private val typingRoot: String? = (mode as? GroupRoomMode.Thread)?.rootId
    // ---- 在席（Buzz のみ、docs/buzz-presence-design.md） ----
    private val presenceBook = PresenceBook()
    /** 画面が前面に出ている。 */
    private var screenVisible = false
    /** メンバー一覧（グループの情報）を開いている。開いている間は一覧の人の在席も見る。 */
    private var memberListOpen = false
    private var releasePresence: (() -> Unit)? = null
    private var presenceTargets: List<String> = emptyList()
    private var presenceSession: SubscriptionSession? = null
    private var presenceSessionJob: Job? = null
    private var presenceSnapshotJob: Job? = null
    private var presenceExpiryJob: Job? = null
    private var presenceTargetsJob: Job? = null
    private var lastSnapshotAt = 0L

    private val _state = MutableStateFlow(GroupChatUiState(ref = ref, mode = mode, hasSigner = account != null))
    val state: StateFlow<GroupChatUiState> = _state.asStateFlow()

    init {
        account?.start()
        loadCommunityRecent()
        launch {
            account?.pendingGroups?.collect { pending -> _state.update { it.copy(isPending = ref in pending) } }
        }
        launch {
            // 参加済み一覧(標準の NIP-29)か、チャット一覧などで分かった参加状況(Buzz を含む)を、リレーの答えまでの見込みにする。
            account?.joinedGroups?.collect { joined ->
                _state.update { it.copy(locallyJoined = joined.any { g -> g.ref == ref } || roomCache.joinedHint(ref) == true) }
            }
        }
        launch {
            mutedPubkeys?.collect {
                publishTimeline()
                refreshTyping()
            }
        }
        launch {
            NostrRepository.relayAuthStates.collect { states ->
                val failure = (states[ref.relayUrl] as? RelayAuthState.Failed)?.reason
                _state.update { it.copy(authFailure = failure) }
            }
        }
        if (roomCache.joinedHint(ref) == true) _state.update { it.copy(locallyJoined = true) }
        launch {
            trace("start")
            account?.awaitReady()
            trace("account ready")
            val info = RelayInformationRepository.fetch(ref.relayUrl).getOrNull()
            trace("nip11 ${if (info == null) "failed" else "ok"}")
            // NIP-11 の pubkey は運営者の連絡先で、グループ情報の署名者（self）とは限らないので使わない。
            relayPubkey = info?.self
            supportsUnlisted = BUZZ_UNLISTED_EXTENSION in info?.supportedExtensions.orEmpty()
            // NIP-11 を読めなくても、参加済みの Buzz コミュニティなら Buzz として扱う。
            flavor = if (info == null && account?.buzzCommunities?.value?.contains(ref.relayUrl) == true) {
                GroupFlavor.Buzz
            } else {
                groupFlavorOf(info?.supportedExtensions.orEmpty())
            }
            timeline = GroupTimeline(ref.groupId, ownPubkey, flavor, relayPubkey)
            flavorKnown.complete(Unit)
            _state.update { it.copy(relayName = info?.name, flavor = flavor, imagesUnlocked = account?.signsSilently == true) }
            replayCache()
            recomputeState()
            subscribe()
            trace("subscribed")
        }
    }

    /** 前回この画面で受け取ったイベントを先に出す。リレーの答えが届けば上書きされる。 */
    private fun replayCache() {
        val cached = roomCache.events(ref, roomKey)
        if (cached.isEmpty()) return
        cachedOnlyIds += cached.map { it.id }
        onEvents(cached, fromCache = true)
        trace("cache replayed ${cached.size}")
    }

    /**
     * 最初の EOSE で、リレーの最新ページが上限まで埋まっていたら、そのページより古い控えの発言を外す。
     * 前回からの新しい発言がページに収まらないと、控えとの間に抜けができるため(古い方は過去の読み込みで取り直す)。
     */
    private fun dropDisconnectedCache() {
        if (cachedOnlyIds.isEmpty() || mode is GroupRoomMode.Thread) {
            cachedOnlyIds.clear()
            return
        }
        val liveOldest = pager.until()
        if (firstPageCount >= PAGE_SIZE && liveOldest != null) {
            val stale = timeline.eventsCreatedBefore(cachedOnlyIds, liveOldest)
            timeline.remove(stale)
            trace("dropped ${stale.size} cached events past a gap")
        }
        cachedOnlyIds.clear()
        publishTimeline()
    }

    private fun trace(stage: String) {
        if (GROUP_OPEN_TRACE) appLog("[GroupOpen] ${ref.groupId.take(8)} $roomKey +${kotlin.time.Clock.System.now().toEpochMilliseconds() - openedAt}ms $stage")
    }

    private val chatKinds get() = if (flavor == GroupFlavor.Buzz) listOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND) else listOf(GROUP_CHAT_KIND)
    private val postKind get() = if (flavor == GroupFlavor.Buzz) BUZZ_FORUM_POST_KIND else GROUP_THREAD_KIND
    private val commentKind get() = if (flavor == GroupFlavor.Buzz) BUZZ_FORUM_COMMENT_KIND else GROUP_COMMENT_KIND
    /** ほかの人の在席を読むか。Buzz のチャットとスレッドだけ（フォーラムの一覧にはアバターが無い）。 */
    private val presenceReadable get() = flavor == GroupFlavor.Buzz && (mode != GroupRoomMode.Forum || memberListOpen)
    private val presenceActive get() = screenVisible && flavor == GroupFlavor.Buzz

    /** 入力中を送り・出すか。Buzz のチャットとスレッドだけ（標準の NIP-29 には無い kind なので送らない）。 */
    private val typingEnabled get() = flavor == GroupFlavor.Buzz && mode != GroupRoomMode.Forum

    private fun filters(): List<NostrFilter> = buildList {
        val h = listOf(ref.groupId)
        when (mode) {
            GroupRoomMode.Chat -> add(NostrFilter(kinds = chatKinds, hTags = h, limit = PAGE_SIZE))
            is GroupRoomMode.Thread -> {
                // Buzz は #h の無い読み取りから unlisted・system チャンネルを外すので、ルートもグループを指定して読む。
                add(NostrFilter(ids = listOf(mode.rootId), hTags = h))
                add(NostrFilter(kinds = chatKinds + commentKind, hTags = h, eTags = listOf(mode.rootId), limit = 500))
                if (flavor == GroupFlavor.Standard) add(NostrFilter(kinds = listOf(GROUP_COMMENT_KIND), hTags = h, rootEventTags = listOf(mode.rootId), limit = 500))
            }
            GroupRoomMode.Forum -> {
                add(NostrFilter(kinds = listOf(postKind), hTags = h, limit = PAGE_SIZE))
                add(NostrFilter(kinds = listOf(commentKind), hTags = h, limit = 500))
            }
        }
        val engagementKinds = if (flavor == GroupFlavor.Buzz) listOf(7, 5, GROUP_DELETE_EVENT_KIND, BUZZ_EDIT_KIND) else listOf(7, 5, GROUP_DELETE_EVENT_KIND)
        add(NostrFilter(kinds = engagementKinds, hTags = h, limit = 500))
        add(
            NostrFilter(
                // メンバー一覧はヘッダーの人数とグループ情報に使う（標準の NIP-29 では出さないリレーもある）。
                kinds = listOf(GROUP_METADATA_KIND, GROUP_ADMINS_KIND, GROUP_MEMBERS_KIND),
                authors = relayPubkey?.let(::listOf),
                dTags = h,
            ),
        )
        // 入力中は保存されず配信中にしか届かないので、購読した時刻から受け取る。
        if (typingEnabled) add(NostrFilter(kinds = listOf(BUZZ_TYPING_KIND), hTags = h, since = nowMillis() / 1000))
        if (flavor == GroupFlavor.Standard) {
            ownPubkey?.let { me ->
                add(NostrFilter(kinds = listOf(GROUP_PUT_USER_KIND, GROUP_REMOVE_USER_KIND), hTags = h, pTags = listOf(me)))
            }
        }
    }

    /** 購読を張り直す。同時に呼ばれても、開いた購読は必ず1つにする。 */
    private suspend fun subscribe() = subscribeMutex.withLock {
        subscriptionJob?.cancel()
        session?.let { old -> withContext(NonCancellable) { old.close() } }
        session = null
        com.nostr.torinos.util.typingTraceLog { "subscribe ${ref.groupId.take(8)} flavor=$flavor mode=$mode typingFilter=$typingEnabled relay=${ref.relayUrl}" }
        val opened = NostrRepository.openSubscription(
            SubscriptionSpec(
                id = "group-${Random.nextLong().toULong().toString(16)}",
                filters = filters(),
                target = RelayTarget.Explicit(setOf(ref.relayUrl)),
                behavior = SubscriptionBehavior.Live,
                replayCachedEvents = false,
            ),
        )
        session = opened
        subscriptionJob = launch {
            opened.signals.collect { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        if (!tracedFirstEvent) { tracedFirstEvent = true; trace("first event") }
                        if (!firstEoseSeen && signal.event.kind in pageKinds) firstPageCount++
                        onEvents(listOf(signal.event))
                    }
                    is SubscriptionSignal.Eose -> {
                        if (!firstEoseSeen) {
                            firstEoseSeen = true
                            trace("eose")
                            dropDisconnectedCache()
                            loadThreadEngagement()
                        }
                        _state.update { it.copy(isLoading = false, membershipChecked = true) }
                    }
                    is SubscriptionSignal.Closed -> onClosed(signal)
                    else -> Unit
                }
            }
        }
    }

    private fun onClosed(signal: SubscriptionSignal.Closed) {
        com.nostr.torinos.util.typingTraceLog { "room subscription closed: ${signal.reason}" }
        trace("closed ${signal.reason}")
        _state.update { it.copy(membershipChecked = true) }
        when {
            signal.retry == RetryDisposition.RetryAfterAuth && account != null && !account.isAuthAllowed(ref.relayUrl) ->
                _state.update { it.copy(authConsentNeeded = true, isLoading = false) }
            // 認証の待ち合わせと送り直しはリポジトリが1回だけ行う。ここで購読し直すと拒否が続くリレーで繰り返してしまう。
            signal.retry == RetryDisposition.RetryAfterAuth ->
                _state.update { it.copy(isLoading = false, notice = "ログインできませんでした: ${signal.reason}") }
            signal.reason.startsWith("restricted") ->
                _state.update { it.copy(readRestricted = true, isLoading = false) }
            else -> _state.update { it.copy(isLoading = false) }
        }
    }

    private val pageKinds get() = if (mode == GroupRoomMode.Forum) setOf(postKind) else chatKinds.toSet()

    private fun onEvents(incoming: List<NostrEvent>, fromCache: Boolean = false) {
        // 入力中は控え・タイムライン・過去の読み込みの起点に入れない。
        val (typing, events) = incoming.partition { it.kind == BUZZ_TYPING_KIND }
        if (!fromCache && typingEnabled) {
            val now = nowMillis()
            typing.forEach { event ->
                val shown = typingTracker.receiveTyping(event, now)
                com.nostr.torinos.util.typingTraceLog {
                    "received from=${event.pubkey.take(8)} tags=${event.tags} createdAt=${event.createdAt} now=${now / 1000} " +
                        "newlyShown=$shown visible=${typingTracker.visible(typingRoot, now).map { it.take(8) }}"
                }
            }
            events.filter { it.kind in chatKinds }.forEach { typingTracker.receiveMessage(it, now) }
            if (typing.isNotEmpty() || events.any { it.kind in chatKinds }) refreshTyping()
        }
        if (events.isEmpty()) return
        if (!fromCache) {
            roomCache.addEvents(ref, roomKey, events)
            if (cachedOnlyIds.isNotEmpty()) cachedOnlyIds.removeAll(events.map { it.id }.toSet())
        }
        var metadataChanged = false
        events.forEach { event ->
            when (event.kind) {
                GROUP_METADATA_KIND, GROUP_ADMINS_KIND, GROUP_MEMBERS_KIND -> {
                    metadataEvents += event
                    metadataChanged = true
                }
                GROUP_PUT_USER_KIND, GROUP_REMOVE_USER_KIND -> {
                    moderationEvents += event
                    metadataChanged = true
                }
                // NIP-29: タイムライン参照には自分のイベントを使わない。
                GROUP_CHAT_KIND -> if (event.pubkey != ownPubkey) previous.add(event.id)
            }
        }
        // 控えは今のページとつながっているか分からないので、過去の読み込みの起点には使わない。
        if (!fromCache) pager.observe(events.filter { it.kind in pageKinds })
        timeline.add(events)
        if (metadataChanged) recomputeState() else publishTimeline()
        scheduleThreadRoots()
        val shown = events.filter { it.kind in chatKinds || it.kind == postKind || it.kind == commentKind }
        // Buzz では本文のメンションを名前でリンクにするので、メンションされた人のプロフィールも取る。
        val mentioned = if (flavor == GroupFlavor.Buzz) shown.flatMap { com.nostr.torinos.chat.buzzProfileTargets(it) } else emptyList()
        // リレーが署名した発言は `actor` の人が作者なので、その人のプロフィールを取る。
        val authors = shown.map { com.nostr.torinos.chat.groupMessageAuthor(it, flavor, relayPubkey) }.toSet() + mentioned
        if (authors.isNotEmpty()) {
            // Buzz などで作ったアカウントは kind 0 をそのグループのリレーにしか置かないことがある。
            // グループのリレーへ先に聞き、取れなかった分は全リレーへ広げる(ProfileRepository の relayHint の動き)。
            launch { ProfileRepository.ensureProfiles(authors, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
        }
    }

    private fun recomputeState() {
        val fromRelay = latestGroupMetadata(metadataEvents, relayPubkey)[ref.groupId]
        // 編集を保存した直後は、リレーがそれより新しいグループ情報を出すまで編集後の内容を見せる。
        val metadata = savedEdit?.let { (baseline, edited) ->
            if (fromRelay != null && fromRelay.createdAt > baseline) null.also { savedEdit = null } else edited
        } ?: fromRelay
        // DM の見出しは参加者の名前から作る。
        val participants = metadata?.participants.orEmpty().toSet()
        if (participants.isNotEmpty() && participants != requestedParticipants) {
            requestedParticipants = participants
            launch { ProfileRepository.ensureProfiles(participants, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
        }
        // self が分からないリレーでは、グループ情報の署名者を共有リンク用の鍵として覚える（認可には使わない）。
        if (relayPubkey == null) {
            linkPubkey = metadataEvents.filter { it.kind == GROUP_METADATA_KIND }.maxByOrNull { it.createdAt }?.pubkey
        }
        val admins = metadataEvents
            .filter { it.kind == GROUP_ADMINS_KIND }
            .maxByOrNull { it.createdAt }
            ?.let { parseGroupAdmins(it, relayPubkey) }
            .orEmpty()
        val authorities = admins + listOfNotNull(relayPubkey)
        timeline.setModerators(authorities)
        // リレーの鍵も管理者も分からないときは、署名者を確かめられないので表示にだけ使い、保存はしない。
        val verified = authorities.isNotEmpty()
        // メンバー情報が1件も無いときは null(未確定)にし、参加済み一覧での判断に任せる。
        val membership = ownPubkey?.let { me ->
            if (flavor == GroupFlavor.Buzz) {
                val hasMemberList = metadataEvents.any {
                    it.kind == GROUP_MEMBERS_KIND && it.firstTagValue("d") == ref.groupId && (relayPubkey == null || it.pubkey == relayPubkey)
                }
                if (hasMemberList) buzzMembershipOf(me, ref.groupId, metadataEvents, relayPubkey) else null
            } else {
                val signers = authorities.ifEmpty { moderationEvents.map { e -> e.pubkey }.toSet() }
                val hasModeration = moderationEvents.any { it.pubkey in signers && it.firstTagValue("h") == ref.groupId }
                if (hasModeration) membershipOf(me, ref.groupId, moderationEvents, signers) else null
            }
        }
        // Buzz の役割（39002）。送った変更は、それより新しい 39002 が届くまで重ねる。
        val latestMembersId = latestMemberListId(metadataEvents, ref.groupId, relayPubkey)
        pendingRoles = pendingRoles.filterValues { (_, baseline) -> baseline == latestMembersId }
        val pending = pendingRoles.mapValues { it.value.first }
        val roles = if (flavor == GroupFlavor.Buzz) channelRoles(metadataEvents, ref.groupId, relayPubkey)?.let { applyPendingRoles(it, pending) } else null
        _state.update {
            val shown = metadata ?: it.metadata
            it.copy(
                metadata = shown,
                membership = membership,
                canEdit = it.hasSigner && canEditGroup(shown, ownPubkey, admins),
                members = (groupMemberList(metadataEvents, ref.groupId, relayPubkey) ?: it.members)?.let { list -> membersWithPending(list, pending) },
                admins = admins,
                roles = roles.orEmpty(),
                authority = if (it.hasSigner) channelAuthority(shown, roles, ownPubkey) else ChannelAuthority(),
            )
        }
        membership?.let { roomCache.setJoinedHint(ref, it == GroupMembership.Member) }
        // 標準の NIP-29 だけ kind 10009 に保存する（Buzz はコミュニティ単位で保存済み）。
        if (flavor == GroupFlavor.Standard && membership == GroupMembership.Member && account != null && verified && !joinRecordAttempted) {
            joinRecordAttempted = true
            launch {
                if (account.joinedGroups.value.none { it.ref == ref }) {
                    runCatching { account.recordJoined(JoinedGroup(ref, metadata?.name?.takeIf { it.isNotBlank() })) }
                } else {
                    account.clearPending(ref)
                }
            }
        }
        publishTimeline()
    }

    /** 入力中の表示を今の時刻で出し直し、次に変わる時刻に再び呼ぶ。 */
    private fun refreshTyping() {
        if (!typingEnabled) return
        val now = nowMillis()
        val muted = mutedPubkeys?.value.orEmpty()
        val visible = typingTracker.visible(typingRoot, now).filter { it !in muted }
        if (visible != _state.value.typingAuthors) _state.update { it.copy(typingAuthors = visible) }
        val unknown = visible.toSet() - requestedTypingAuthors
        if (unknown.isNotEmpty()) {
            requestedTypingAuthors = requestedTypingAuthors + unknown
            launch { ProfileRepository.ensureProfiles(unknown, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
        }
        typingTimerJob?.cancel()
        typingTimerJob = typingTracker.nextChangeAt(now)?.let { at ->
            launch {
                delay(at - now)
                refreshTyping()
            }
        }
    }

    /**
     * ユーザーが入力欄を編集した。Buzz では入力中を送る（3秒に1回まで、確認なしで署名できるときだけ）。
     * 失敗しても何も知らせない（入力を妨げない）。
     */
    fun onInputEdited(text: String) {
        val account = account ?: return
        val current = _state.value
        val skip = when {
            !flavorKnown.isCompleted -> "flavor unknown"
            !typingEnabled -> "not buzz chat (flavor=$flavor mode=$mode)"
            !account.signsSilently -> "signer needs confirmation"
            current.composer != GroupComposerState.Writable -> "composer=${current.composer}"
            current.editing != null -> "editing"
            text.isBlank() -> "blank"
            else -> null
        }
        if (skip != null) {
            if (skip != lastTypingSkip) com.nostr.torinos.util.typingTraceLog { "input: not sending ($skip)" }
            lastTypingSkip = skip
            return
        }
        lastTypingSkip = null
        if (typingSendJob?.isActive == true || !typingThrottle.tryAcquire(nowMillis())) return
        typingSendJob = launch {
            val sent = withTimeoutOrNull(TYPING_SEND_TIMEOUT_MILLIS) { account.publishTyping(ref, typingRoot) }
            if (sent == null) com.nostr.torinos.util.typingTraceLog { "send timed out" }
        }
    }

    private fun publishTimeline() {
        val muted = mutedPubkeys?.value.orEmpty()
        fun List<GroupMessage>.visible() = filter { it.author !in muted }
        when (mode) {
            GroupRoomMode.Chat -> {
                val snapshot = timeline.snapshot(muted)
                _state.update { it.copy(messages = snapshot.messages) }
            }
            is GroupRoomMode.Thread -> {
                val thread = timeline.thread(mode.rootId)
                // ミュート中の人の発言も外さず、画面で折りたたむ。
                _state.update { it.copy(threadRoot = thread.root, messages = thread.replies, collapsedAuthors = muted) }
            }
            GroupRoomMode.Forum -> _state.update { it.copy(messages = timeline.forumPosts().visible()) }
        }
        schedulePresenceTargets()
    }

    // ---- 在席 ----

    /** 画面が前面に出た・背面に回った（画面のライフサイクル）。前面の間だけ自分の在席を送り、ほかの人の在席を読む。 */
    fun setScreenVisible(visible: Boolean) {
        if (screenVisible == visible) return
        screenVisible = visible
        launch {
            flavorKnown.await()
            applyPresenceActivity()
        }
    }

    /**
     * メンバー全員のプロフィールを取る（メンバー一覧、メンションの候補）。発言していないメンバーのプロフィールは、まだ取っていない。
     * Buzz の kind 0 はコミュニティのリレーにしか無いことがあるので、先にそこへ聞く。
     */
    fun ensureMemberProfiles() {
        val members = memberListOrder(_state.value, ownPubkey).toSet()
        if (members.isEmpty()) return
        launch { ProfileRepository.ensureProfiles(members, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
    }

    /** メンバー一覧を開いた・閉じた。 */
    fun setMemberListOpen(open: Boolean) {
        if (memberListOpen == open) return
        memberListOpen = open
        if (open) ensureMemberProfiles()
        launch {
            flavorKnown.await()
            if (!presenceActive) return@launch
            // フォーラムの画面では、一覧を開いている間だけ在席を読む。
            if (presenceReadable) schedulePresenceTargets(immediately = true) else stopPresenceReading()
        }
    }

    private fun applyPresenceActivity() {
        if (presenceActive) {
            if (releasePresence == null) releasePresence = account?.holdPresence(ref.relayUrl)
            if (presenceReadable && presenceSession == null && presenceTargetsJob?.isActive != true) {
                presenceTargets = emptyList()
                schedulePresenceTargets(immediately = true)
            }
        } else {
            releasePresence?.invoke()
            releasePresence = null
            stopPresenceReading()
        }
    }

    private fun stopPresenceReading() {
        presenceTargetsJob?.cancel()
        presenceSnapshotJob?.cancel()
        presenceExpiryJob?.cancel()
        presenceSessionJob?.cancel()
        presenceSession?.let { NostrRepository.closeSessionInBackground(it) }
        presenceSession = null
        presenceTargets = emptyList()
        if (_state.value.presence.isNotEmpty()) _state.update { it.copy(presence = emptyMap()) }
    }

    /** 画面の発言者が変わったら、少し待ってから在席を見る人を決め直す。 */
    private fun schedulePresenceTargets(immediately: Boolean = false) {
        if (!presenceActive || !presenceReadable) return
        presenceTargetsJob?.cancel()
        presenceTargetsJob = launch {
            if (!immediately) delay(PRESENCE_TARGETS_DEBOUNCE_MILLIS)
            updatePresenceTargets()
        }
    }

    private suspend fun updatePresenceTargets() {
        val current = _state.value
        val authors = (listOfNotNull(current.threadRoot) + current.messages).map { it.author }
        val targets = presenceMembersSubjects(
            authorsOldestFirst = authors,
            participants = current.metadata?.participants.orEmpty(),
            members = memberListOrder(current, ownPubkey),
            memberListOpen = memberListOpen,
        )
        if (targets == presenceTargets) return
        val added = targets.toSet() - presenceTargets.toSet()
        presenceTargets = targets
        openPresenceSubscription(targets)
        // 新しい人が入ったら、今の在席をすぐ取る（取得の間隔は5秒以上あける）。
        if (added.isNotEmpty()) restartPresenceSnapshots()
        refreshPresence()
    }

    private suspend fun openPresenceSubscription(targets: List<String>) {
        presenceSessionJob?.cancel()
        presenceSession?.let { NostrRepository.closeSessionInBackground(it) }
        presenceSession = null
        if (targets.isEmpty()) return
        // 開いている途中で取り消されて、開いた購読が残らないようにする。
        val opened = withContext(NonCancellable) {
            NostrRepository.openSubscription(
                SubscriptionSpec(
                    id = "presence-${Random.nextLong().toULong().toString(16)}",
                    filters = listOf(NostrFilter(kinds = listOf(BUZZ_PRESENCE_KIND), authors = targets)),
                    target = RelayTarget.Explicit(setOf(ref.relayUrl)),
                    behavior = SubscriptionBehavior.Live,
                    replayCachedEvents = false,
                ),
            )
        }
        if (!presenceActive) {
            NostrRepository.closeSessionInBackground(opened)
            return
        }
        presenceSession = opened
        presenceSessionJob = launch {
            opened.signals.collect { signal ->
                if (signal is SubscriptionSignal.Event && signal.event.pubkey in presenceTargets) {
                    presenceBook.receiveLive(signal.event, nowMillis())
                    refreshPresence()
                }
            }
        }
    }

    /** 一括取得を（5秒の間隔を守って）すぐ行い、その後は60秒ごとに繰り返す。確認なしで署名できないときは配信だけで見る。 */
    private fun restartPresenceSnapshots() {
        val account = account ?: return
        val relayKey = relayPubkey ?: return
        if (!account.signsSilently) return
        presenceSnapshotJob?.cancel()
        presenceSnapshotJob = launch {
            delay((lastSnapshotAt + PRESENCE_SNAPSHOT_GAP_MILLIS - nowMillis()).coerceAtLeast(0))
            while (true) {
                val targets = presenceTargets
                val requestedAt = nowMillis()
                lastSnapshotAt = requestedAt
                val values = account.fetchPresenceSnapshot(ref.relayUrl, targets, relayKey)
                if (values != null) {
                    presenceBook.applySnapshot(values, requestedAt, nowMillis())
                    refreshPresence()
                }
                delay(com.nostr.torinos.chat.PRESENCE_RENEW_MILLIS + Random.nextLong(com.nostr.torinos.chat.PRESENCE_RENEW_JITTER_MILLIS + 1))
            }
        }
    }

    /** 表示する在席を出し直し、次に誰かの在席が不明に戻る時刻にもう一度呼ぶ。 */
    private fun refreshPresence() {
        if (!presenceActive) return
        val now = nowMillis()
        val targets = presenceTargets.toSet()
        val visible = presenceBook.snapshot(now).filterKeys { it in targets }
        if (visible != _state.value.presence) _state.update { it.copy(presence = visible) }
        presenceExpiryJob?.cancel()
        presenceExpiryJob = presenceBook.nextExpiryAt(now)?.let { at ->
            launch {
                delay(at - now)
                refreshPresence()
            }
        }
    }

    /**
     * チャンネルの画面で、スレッドへの返信の行が指す元の発言のうち、読めていないものを `ids` で読む。
     * 元の発言が読めている範囲より古いスレッドは、返信も読めている範囲より前にあるので、`#e` で読んで数をそろえる。
     * 届いた返信がまとまってから1回で読む。答えがそろわなかった分は、次に返信が届いたときに読み直す。
     */
    private fun scheduleThreadRoots() {
        if (mode != GroupRoomMode.Chat || flavor != GroupFlavor.Buzz) return
        if (threadRootsJob?.isActive == true) return
        threadRootsJob = launch {
            delay(THREAD_ROOTS_DEBOUNCE_MILLIS)
            val missing = timeline.missingThreadRoots() - requestedThreadRoots
            if (missing.isEmpty()) return@launch
            requestedThreadRoots += missing
            val result = fetchFiniteEvents(
                SubscriptionSpec(
                    id = "group-thread-roots-${Random.nextLong().toULong().toString(16)}",
                    // Buzz は #h の無い読み取りから unlisted・system チャンネルを外すので、グループを指定して読む。
                    filters = missing.chunked(100).flatMap { ids ->
                        listOf(
                            NostrFilter(ids = ids, hTags = listOf(ref.groupId)),
                            NostrFilter(kinds = chatKinds, hTags = listOf(ref.groupId), eTags = ids, limit = 500),
                        )
                    },
                    target = RelayTarget.Explicit(setOf(ref.relayUrl)),
                    behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                    replayCachedEvents = false,
                ),
            )
            val found = result.events.mapTo(mutableSetOf()) { it.id }
            if (!result.allAnswered) requestedThreadRoots -= (missing - found)
            if (result.events.isEmpty()) return@launch
            timeline.addThreadRoots(result.events.filter { it.id in missing })
            timeline.addThreadReplies(result.events.filter { it.id !in missing })
            publishTimeline()
            val mentioned = result.events.flatMap { com.nostr.torinos.chat.buzzProfileTargets(it) }
            val authors = result.events.map { com.nostr.torinos.chat.groupMessageAuthor(it, flavor, relayPubkey) }.toSet() + mentioned
            launch { ProfileRepository.ensureProfiles(authors, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
        }
    }

    fun loadOlder() {
        if (mode is GroupRoomMode.Thread) return
        val current = _state.value
        // 最初の取得が終わる前は遡らない（同じ範囲を二重に取るだけになる）。
        if (current.isLoading || current.isLoadingOlder || !pager.canLoadOlder) return
        val until = pager.until() ?: return
        _state.update { it.copy(isLoadingOlder = true) }
        launch {
            try {
                val kinds = if (mode == GroupRoomMode.Forum) listOf(postKind) else chatKinds
                val result = fetchFiniteEvents(
                    SubscriptionSpec(
                        id = "group-older-${Random.nextLong().toULong().toString(16)}",
                        filters = listOf(NostrFilter(kinds = kinds, hTags = listOf(ref.groupId), until = until, limit = PAGE_SIZE)),
                        target = RelayTarget.Explicit(setOf(ref.relayUrl)),
                        behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                        replayCachedEvents = false,
                    ),
                )
                val freshEvents = pager.onPage(result.events, result.allAnswered)
                onEvents(result.events)
                // 古いページのメッセージへのリアクションと削除は、配信中の購読（新しい順の上限つき）に入らないことがある。
                if (freshEvents.isNotEmpty()) loadEngagementFor(freshEvents.map { it.id })
                _state.update { it.copy(canLoadOlder = pager.canLoadOlder) }
            } finally {
                _state.update { it.copy(isLoadingOlder = false) }
            }
        }
    }

    /**
     * スレッドの発言へのリアクション・削除・編集を、発言を指定して読む。
     * 配信中の購読はグループ全体の新しい順 500 件なので、リアクションの多いチャンネルでは
     * 少し前のスレッドの分が入らない（Buzz 本家もスレッドは `#e` で読む）。
     */
    private fun loadThreadEngagement() {
        val rootId = (mode as? GroupRoomMode.Thread)?.rootId ?: return
        val ids = timeline.threadEventIds(rootId)
        if (ids.isEmpty()) return
        launch { loadEngagementFor(ids) }
    }

    private suspend fun loadEngagementFor(eventIds: List<String>) {
        val kinds = listOf(7, 5, GROUP_DELETE_EVENT_KIND) + if (flavor == GroupFlavor.Buzz) listOf(BUZZ_EDIT_KIND) else emptyList()
        val result = fetchFiniteEvents(
            SubscriptionSpec(
                id = "group-older-engagement-${Random.nextLong().toULong().toString(16)}",
                // Buzz は `h` の無いリアクションも保存しているので、`#h` を付けると取れない（buzz-app も `#e` だけで読む）。
                filters = eventIds.chunked(100).map { ids ->
                    NostrFilter(kinds = kinds, hTags = if (flavor == GroupFlavor.Buzz) null else listOf(ref.groupId), eTags = ids)
                },
                target = RelayTarget.Explicit(setOf(ref.relayUrl)),
                behavior = SubscriptionBehavior.Fetch(timeoutMillis = 10_000),
                replayCachedEvents = false,
            ),
        )
        onEvents(result.events)
    }

    /** ユーザーが NIP-42 の認証を許可した。 */
    fun allowAuth() {
        val account = account ?: return
        launch {
            account.allowAuth(ref.relayUrl)
            _state.update { it.copy(authConsentNeeded = false, isLoading = true) }
            subscribe()
        }
    }

    fun setReplyTo(message: GroupMessage?) {
        _state.update { it.copy(replyTo = message, editing = null) }
    }

    fun startEditing(message: GroupMessage?) {
        _state.update { it.copy(editing = message, replyTo = null) }
    }

    fun consumeSent() {
        _state.update { it.copy(sentEventId = null) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * 本文を送る。画面の種類と流儀で、メッセージ・返信・コメント・投稿・編集を作り分ける。
     * [mentions] は Buzz のメンションの宛先。今のメンバーだけを `p` タグにする（編集には付けない）。
     */
    fun send(text: String, title: String = "", mentions: List<String> = emptyList(), onSent: () -> Unit) {
        val account = account ?: return
        val current = _state.value
        if ((text.isBlank() && title.isBlank() && current.attachments.isEmpty()) || current.isSending || current.isUploading) return
        _state.update { it.copy(isSending = true) }
        launch {
            try {
                flavorKnown.await()
                val attachments = if (current.editing == null) current.attachments else emptyList()
                // 本文が空で添付だけのときは、添付を本文にする（作る関数は空の本文を受け付けない）。
                val body = text.trim().ifEmpty { attachments.joinToString("\n\n") { it.markdown } }
                val draft = runCatching {
                    val base = buildDraft(current, body, title.trim())
                    val withAttachments = if (text.isBlank()) base.copy(tags = base.tags + attachments.map { it.imeta }) else base.withAttachments(attachments)
                    val members = memberListOrder(current, ownPubkey).toSet()
                    val recipients = mentions.filter { it in members && it != ownPubkey }
                    if (flavor == GroupFlavor.Buzz && current.editing == null && recipients.isNotEmpty()) {
                        withAttachments.withMentionTags(recipients)
                    } else {
                        withAttachments
                    }
                }.getOrElse {
                    notice("送れない内容です: ${it.message}")
                    return@launch
                }
                when (val outcome = account.publishToGroup(ref, draft)) {
                    is GroupPublishOutcome.Published -> {
                        onEvents(listOf(outcome.event))
                        val sentId = outcome.event.id.takeIf { current.editing == null }
                        _state.update { it.copy(replyTo = null, editing = null, attachments = emptyList(), sentEventId = sentId) }
                        onSent()
                    }
                    is GroupPublishOutcome.Rejected -> notice("送信できませんでした: ${outcome.reason}")
                    is GroupPublishOutcome.Failed -> notice("送信できませんでした: ${outcome.reason}")
                }
            } finally {
                _state.update { it.copy(isSending = false) }
            }
        }
    }

    private fun buildDraft(state: GroupChatUiState, text: String, title: String): GroupEventDraft {
        state.editing?.let { return buildBuzzEdit(ref.groupId, it.event, text) }
        return when (mode) {
            GroupRoomMode.Chat -> if (flavor == GroupFlavor.Buzz) {
                buildBuzzMessage(ref.groupId, text, threadRoot = null, parent = null)
            } else {
                buildGroupChatMessage(
                    groupId = ref.groupId,
                    text = text,
                    replyTo = state.replyTo?.let { GroupReplyTarget(it.event, ref.relayUrl) },
                    previous = previous.pick(3),
                )
            }
            is GroupRoomMode.Thread -> {
                val root = state.threadRoot?.event ?: error("スレッドのルートを読み込めていません")
                val parent = state.replyTo?.event
                when (root.kind) {
                    BUZZ_FORUM_POST_KIND -> buildForumComment(ref.groupId, text, root, parent)
                    GROUP_THREAD_KIND -> buildGroupComment(ref.groupId, text, root, parent, ref.relayUrl)
                    else -> buildBuzzMessage(ref.groupId, text, threadRoot = root, parent = parent ?: root)
                }
            }
            GroupRoomMode.Forum -> if (flavor == GroupFlavor.Buzz) {
                buildForumPost(ref.groupId, if (title.isBlank()) text else "$title\n$text")
            } else {
                buildGroupThread(ref.groupId, title, text)
            }
        }
    }

    /** 画像を選んだ。アップロードして、送るときに本文と `imeta` へ足す。 */
    fun attach(bytes: ByteArray, mimeType: String, previewBytes: ByteArray? = null) {
        val account = account ?: return
        _state.update { it.copy(isUploading = true) }
        launch {
            try {
                flavorKnown.await()
                val ext = mimeType.substringAfter('/').substringBefore(';').ifBlank { "jpg" }
                val name = "image-${kotlin.time.Clock.System.now().epochSeconds}.$ext"
                val attachment = account.uploadAttachment(ref.relayUrl, flavor, bytes, mimeType, name)
                val withPreview = attachment.copy(previewBytes = previewBytes ?: bytes)
                _state.update { it.copy(attachments = it.attachments + withPreview) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice("画像をアップロードできませんでした: ${e.message}")
            } finally {
                _state.update { it.copy(isUploading = false) }
            }
        }
    }

    fun removeAttachment(attachment: com.nostr.torinos.chat.MessageAttachment) {
        _state.update { it.copy(attachments = it.attachments - attachment) }
    }

    /**
     * リアクションを付ける。同じリアクションを自分が付けていれば取り消す。
     * 送信中のメッセージは付け外しを止める（付けると取り消しが入れ違わないように）。
     */
    fun react(message: GroupMessage, option: ReactionOption): GroupReactionResult {
        val account = account ?: return GroupReactionResult.Ignored
        val messageId = message.event.id
        if (messageId in _state.value.reactingMessageIds) return GroupReactionResult.Ignored
        val ownId = ownReactionIdFor(message.reactions, option)
        if (ownId == null && !isGroupReactionSendable(option, flavor)) {
            notice("この絵文字はこのグループでは使えません")
            return GroupReactionResult.Ignored
        }
        val draft = if (ownId != null) {
            buildGroupDeletion(ref.groupId, listOf(ownId))
        } else {
            runCatching { buildGroupReaction(ref.groupId, message.event, option, flavor) }.getOrElse {
                notice("この絵文字はこのグループでは使えません")
                return GroupReactionResult.Ignored
            }
        }
        _state.update { it.copy(reactingMessageIds = it.reactingMessageIds + messageId) }
        if (ownId == null) recordCommunityRecent(option)
        launch {
            try {
                when (val outcome = account.publishToGroup(ref, draft)) {
                    is GroupPublishOutcome.Published -> onEvents(listOf(outcome.event))
                    is GroupPublishOutcome.Rejected -> notice("リアクションできませんでした: ${outcome.reason}")
                    is GroupPublishOutcome.Failed -> notice("リアクションできませんでした: ${outcome.reason}")
                }
            } finally {
                _state.update { it.copy(reactingMessageIds = it.reactingMessageIds - messageId) }
            }
        }
        return if (ownId != null) GroupReactionResult.Removed else GroupReactionResult.Added
    }

    private fun recordCommunityRecent(option: ReactionOption) {
        val me = ownPubkey ?: return
        _state.update { it.copy(communityRecent = recordGroupRecent(it.communityRecent, option)) }
        launch {
            val key = groupRecentStorageKey(me, ref.relayUrl)
            val saved = groupRecentMutex.withLock {
                val next = recordGroupRecent(decodeGroupRecent(LocalSettingsStorage.getString(key)), option)
                LocalSettingsStorage.putString(key, encodeGroupRecent(next))
                next
            }
            _state.update { it.copy(communityRecent = saved) }
        }
    }

    private fun loadCommunityRecent() {
        val me = ownPubkey ?: return
        launch {
            val stored = decodeGroupRecent(LocalSettingsStorage.getString(groupRecentStorageKey(me, ref.relayUrl)))
            _state.update { state ->
                state.copy(communityRecent = (state.communityRecent + stored).distinctBy { it.key }.take(GROUP_RECENT_REACTION_LIMIT))
            }
        }
    }

    /**
     * Buzz のコミュニティの絵文字を読む（ピッカーを開いたときと、タグの無い `:shortcode:` のリアクションを出すとき）。
     * 読めなかったら前の一覧を残す。
     */
    fun ensureCommunityEmojis() {
        val account = account ?: return
        if (_state.value.communityEmojis.isLoading) return
        _state.update { it.copy(communityEmojis = it.communityEmojis.copy(isLoading = true)) }
        launch {
            try {
                flavorKnown.await()
                if (flavor != GroupFlavor.Buzz) return@launch
                val cached = account.cachedCommunityEmojis(ref.relayUrl)
                if (cached != null) _state.update { it.copy(communityEmojis = it.communityEmojis.copy(palette = cached)) }
                val palette = account.fetchCommunityEmojis(ref.relayUrl, COMMUNITY_EMOJI_MAX_AGE_MILLIS)
                _state.update { state ->
                    val previous = state.communityEmojis.palette
                    state.copy(communityEmojis = CommunityEmojiState(palette = palette ?: previous, failed = palette == null && previous == null))
                }
            } finally {
                _state.update { it.copy(communityEmojis = it.communityEmojis.copy(isLoading = false)) }
            }
        }
    }

    fun delete(message: GroupMessage) {
        val account = account ?: return
        if (message.event.pubkey != ownPubkey) return
        launch {
            when (val outcome = account.publishToGroup(ref, buildGroupDeletion(ref.groupId, listOf(message.event.id)))) {
                is GroupPublishOutcome.Published -> onEvents(listOf(outcome.event))
                is GroupPublishOutcome.Rejected -> notice("削除できませんでした: ${outcome.reason}")
                is GroupPublishOutcome.Failed -> notice("削除できませんでした: ${outcome.reason}")
            }
        }
    }

    /** Buzz で自分の投稿を編集できるか。 */
    fun canEdit(message: GroupMessage): Boolean = flavor == GroupFlavor.Buzz && message.event.pubkey == ownPubkey

    fun join(inviteCode: String? = null) {
        val account = account ?: return
        launch {
            // 流儀が決まる前に送ると、Buzz のチャンネルを kind 10009 に書いてしまう。
            flavorKnown.await()
            when (val outcome = account.publishToGroup(ref, buildGroupJoinRequest(ref.groupId, inviteCode, ""))) {
                is GroupPublishOutcome.Published -> {
                    // 受け付けられた。リレーの追加（kind 9000）を確かめるまでは承認待ちとして持つ。
                    if (flavor == GroupFlavor.Standard) account.markPending(ref)
                    notice("参加要求を送りました")
                    resubscribeAfterJoin()
                }
                is GroupPublishOutcome.Rejected -> when (classifyJoinRejection(outcome.reason)) {
                    JoinRejection.AlreadyMember -> {
                        if (flavor == GroupFlavor.Standard) {
                            runCatching { account.recordJoined(JoinedGroup(ref, _state.value.metadata?.name), userInitiated = true) }
                        }
                        notice("参加済みです")
                        resubscribeAfterJoin()
                    }
                    JoinRejection.PendingReview -> {
                        if (flavor == GroupFlavor.Standard) account.markPending(ref)
                        notice("参加の承認待ちです: ${outcome.reason}")
                    }
                    JoinRejection.Refused -> notice("参加できませんでした: ${outcome.reason}")
                }
                is GroupPublishOutcome.Failed -> notice("参加できませんでした: ${outcome.reason}")
            }
        }
    }

    /**
     * 参加したあと購読を張り直す。メンバーだけが読めるグループでは、参加前の購読が拒否されて閉じているので、
     * 張り直さないと発言も自分の追加（kind 9000）も届かない。
     */
    private suspend fun resubscribeAfterJoin() {
        _state.update { it.copy(readRestricted = false, isLoading = true) }
        subscribe()
    }

    /**
     * 退出する。送信と参加中の一覧の更新はアカウント側で最後まで行うので、待つ間に画面を閉じても退出は完了する。
     * 退出できたら [onLeft] で画面を閉じる。一覧を更新できなかったときは知らせてから閉じない（理由を読めるように）。
     */
    fun leave(onLeft: () -> Unit) {
        val account = account ?: return
        if (_state.value.isLeaving) return
        _state.update { it.copy(isLeaving = true) }
        launch {
            try {
                flavorKnown.await()
                when (val outcome = account.leaveGroup(ref, flavor)) {
                    GroupLeaveOutcome.Left -> {
                        _state.update { it.copy(membership = GroupMembership.NotMember, locallyJoined = false) }
                        onLeft()
                    }
                    is GroupLeaveOutcome.LeftButListNotUpdated -> {
                        _state.update { it.copy(membership = GroupMembership.NotMember, locallyJoined = false) }
                        notice("退出しましたが、参加中の一覧を更新できませんでした: ${outcome.reason}")
                    }
                    is GroupLeaveOutcome.NotLeft -> notice("退出できませんでした: ${outcome.reason}")
                }
            } finally {
                _state.update { it.copy(isLeaving = false) }
            }
        }
    }

    // ---- グループ情報の編集 ----

    fun openEdit() {
        val current = _state.value.metadata?.takeIf { _state.value.canEdit } ?: return
        val buzz = flavor == GroupFlavor.Buzz
        _state.update { it.copy(editSheet = GroupEditSheet(current, groupEditOf(current), canChangeVisibility = buzz, canSetUnlisted = buzz && supportsUnlisted)) }
    }

    fun closeEdit() {
        _state.update { if (it.editSheet?.isSaving == true) it else it.copy(editSheet = null) }
    }

    fun updateEdit(transform: (GroupEdit) -> GroupEdit) {
        _state.update { state ->
            val sheet = state.editSheet?.takeIf { !it.isSaving } ?: return@update state
            state.copy(editSheet = sheet.copy(edit = transform(sheet.edit), error = null))
        }
    }

    /** 編集を送る（kind 9002）。Buzz は変えた項目だけ、標準の NIP-29 は今の内容をすべて送る。 */
    fun saveEdit() {
        val account = account ?: return
        val sheet = _state.value.editSheet?.takeIf { it.canSave } ?: return
        val draft = (if (flavor == GroupFlavor.Buzz) buildBuzzEditMetadata(sheet.current, sheet.edit) else buildStandardEditMetadata(sheet.current, sheet.edit)) ?: return
        _state.update { it.copy(editSheet = sheet.copy(isSaving = true, error = null)) }
        launch {
            when (val outcome = account.publishToGroup(ref, draft)) {
                is GroupPublishOutcome.Published -> {
                    val edited = sheet.current.withEdit(sheet.edit, flavor)
                    savedEdit = sheet.current.createdAt to edited
                    _state.update { it.copy(editSheet = null, metadata = edited) }
                    notice("グループ情報を更新しました")
                }
                is GroupPublishOutcome.Rejected -> {
                    val message = when (classifyCreateRejection(outcome.reason)) {
                        CreateRejection.NameTaken -> "この名前は既に使われています"
                        CreateRejection.InvalidName -> "この名前は使えません"
                        CreateRejection.NotAllowed -> "編集する権限がありません: ${outcome.reason}"
                        else -> "更新できませんでした: ${outcome.reason}"
                    }
                    _state.update { it.copy(editSheet = sheet.copy(isSaving = false, error = message)) }
                }
                is GroupPublishOutcome.Failed ->
                    _state.update { it.copy(editSheet = sheet.copy(isSaving = false, error = "更新できませんでした: ${outcome.reason}")) }
            }
        }
    }

    // ---- 管理者の操作（Buzz、docs/buzz-admin-design.md） ----

    /** 役割を変える（kind 9000）。 */
    fun changeRole(pubkey: String, role: ChannelRole) {
        val me = ownPubkey ?: return
        val state = _state.value
        if (!canChangeRole(state.authority, state.roles, me, pubkey) || role !in roleChoicesFor(state.authority, state.roles.getValue(pubkey))) return
        sendMemberChange(pubkey, buildChannelPutUser(ref.groupId, pubkey, role), role)
    }

    /** チャンネルから外す（kind 9001）。確認は画面で済ませてから呼ぶ。 */
    fun removeMember(pubkey: String) {
        val me = ownPubkey ?: return
        val state = _state.value
        if (!canRemoveMember(state.authority, state.roles, me, pubkey)) return
        sendMemberChange(pubkey, buildChannelRemoveUser(ref.groupId, pubkey), newRole = null)
    }

    private fun sendMemberChange(pubkey: String, draft: GroupEventDraft, newRole: ChannelRole?) {
        val account = account ?: return
        if (pubkey in _state.value.busyMembers) return
        _state.update { it.copy(busyMembers = it.busyMembers + pubkey, memberError = null) }
        launch {
            try {
                when (val outcome = account.publishToGroup(ref, draft)) {
                    is GroupPublishOutcome.Published -> {
                        pendingRoles = pendingRoles + (pubkey to (newRole to latestMemberListId(metadataEvents, ref.groupId, relayPubkey)))
                        recomputeState()
                    }
                    is GroupPublishOutcome.Rejected -> _state.update { it.copy(memberError = adminRejectionMessage(outcome.reason)) }
                    is GroupPublishOutcome.Failed -> _state.update { it.copy(memberError = "送信できませんでした: ${outcome.reason}") }
                }
            } finally {
                _state.update { it.copy(busyMembers = it.busyMembers - pubkey) }
            }
        }
    }

    fun dismissMemberError() {
        _state.update { it.copy(memberError = null) }
    }

    /** 加えるシートを開く。候補はコミュニティのメンバー（kind 13534）のうち、まだチャンネルにいない人。 */
    fun openAddMembers() {
        val account = account ?: return
        if (!_state.value.authority.canAdd) return
        _state.update { it.copy(addMembers = AddMembersSheet()) }
        launch {
            val roster = account.fetchCommunityRoster(ref.relayUrl)
            val existing = _state.value.roles.keys + _state.value.members.orEmpty()
            val candidates = (roster as? CommunityRosterLoad.Loaded)?.members.orEmpty()
                .map { it.pubkey }
                .filter { it != ownPubkey && it !in existing }
            if (candidates.isNotEmpty()) {
                launch { ProfileRepository.ensureProfiles(candidates.take(ADD_CANDIDATE_PROFILE_LIMIT).toSet(), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
            }
            _state.update { state ->
                val sheet = state.addMembers ?: return@update state
                state.copy(addMembers = sheet.copy(candidates = candidates, isLoadingCandidates = false))
            }
        }
    }

    fun closeAddMembers() {
        _state.update { if (it.addMembers?.isSubmitting == true) it else it.copy(addMembers = null) }
    }

    fun onAddQueryChange(query: String) {
        val typed = com.nostr.torinos.ui.post.parsePubkeyInput(query.trim())
            ?.takeIf { it != ownPubkey && it !in _state.value.roles }
        _state.update { state ->
            val sheet = state.addMembers?.takeIf { !it.isSubmitting } ?: return@update state
            state.copy(addMembers = sheet.copy(query = query, typedPubkey = typed, error = null))
        }
        if (typed != null) {
            launch { ProfileRepository.ensureProfiles(setOf(typed), ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
        }
    }

    fun toggleAddCandidate(pubkey: String) {
        _state.update { state ->
            val sheet = state.addMembers?.takeIf { !it.isSubmitting } ?: return@update state
            val picked = if (pubkey in sheet.picked) sheet.picked - pubkey else sheet.picked + pubkey
            // 選んだら、入力した検索語は消す。
            state.copy(addMembers = sheet.copy(picked = picked, query = if (pubkey in sheet.picked) sheet.query else "", typedPubkey = null, error = null))
        }
    }

    fun setAddRole(role: ChannelRole) {
        if (role !in _state.value.authority.addableRoles) return
        _state.update { state ->
            val sheet = state.addMembers?.takeIf { !it.isSubmitting } ?: return@update state
            state.copy(addMembers = sheet.copy(role = role))
        }
    }

    /** 選んだ人を1人ずつ加える（kind 9000）。断られた人はシートに残して理由を出す。 */
    fun submitAddMembers() {
        val account = account ?: return
        val sheet = _state.value.addMembers?.takeIf { it.canSubmit } ?: return
        _state.update { it.copy(addMembers = sheet.copy(isSubmitting = true, error = null)) }
        launch {
            val failed = mutableListOf<String>()
            var lastError: String? = null
            sheet.picked.forEach { pubkey ->
                when (val outcome = account.publishToGroup(ref, buildChannelPutUser(ref.groupId, pubkey, sheet.role))) {
                    is GroupPublishOutcome.Published -> {
                        pendingRoles = pendingRoles + (pubkey to (sheet.role to latestMemberListId(metadataEvents, ref.groupId, relayPubkey)))
                    }
                    is GroupPublishOutcome.Rejected -> { failed += pubkey; lastError = adminRejectionMessage(outcome.reason) }
                    is GroupPublishOutcome.Failed -> { failed += pubkey; lastError = "送信できませんでした: ${outcome.reason}" }
                }
            }
            recomputeState()
            if (failed.isEmpty()) {
                _state.update { it.copy(addMembers = null) }
                notice("${sheet.picked.size}人を加えました")
            } else {
                _state.update { state ->
                    val current = state.addMembers ?: sheet
                    state.copy(addMembers = current.copy(picked = failed, isSubmitting = false, error = "${failed.size}人を加えられませんでした: $lastError"))
                }
            }
        }
    }

    /** アーカイブ・解除（kind 9002）。確認は画面で済ませてから呼ぶ。 */
    fun setArchived(archived: Boolean) {
        val account = account ?: return
        val state = _state.value
        val current = state.metadata ?: return
        if (state.isAdminBusy || !(if (archived) state.authority.canArchive else state.authority.canUnarchive)) return
        _state.update { it.copy(isAdminBusy = true) }
        launch {
            try {
                when (val outcome = account.publishToGroup(ref, buildChannelArchive(ref.groupId, archived))) {
                    is GroupPublishOutcome.Published -> {
                        val edited = current.copy(isArchived = archived)
                        savedEdit = current.createdAt to edited
                        _state.update { it.copy(metadata = edited) }
                        recomputeState()
                        notice(if (archived) "アーカイブしました" else "アーカイブを解除しました")
                    }
                    is GroupPublishOutcome.Rejected -> notice("変更できませんでした: ${adminRejectionMessage(outcome.reason)}")
                    is GroupPublishOutcome.Failed -> notice("変更できませんでした: ${outcome.reason}")
                }
            } finally {
                _state.update { it.copy(isAdminBusy = false) }
            }
        }
    }

    /** チャンネルを削除する（kind 9008、owner だけ）。確認は画面で済ませてから呼ぶ。削除できたら [onDeleted] で画面を閉じる。 */
    fun deleteChannel(onDeleted: () -> Unit) {
        val account = account ?: return
        val state = _state.value
        if (state.isAdminBusy || !state.authority.canDelete) return
        _state.update { it.copy(isAdminBusy = true) }
        launch {
            try {
                when (val outcome = account.publishToGroup(ref, buildChannelDelete(ref.groupId))) {
                    is GroupPublishOutcome.Published -> {
                        account.markChannelDeleted(ref)
                        onDeleted()
                    }
                    is GroupPublishOutcome.Rejected -> notice("削除できませんでした: ${adminRejectionMessage(outcome.reason)}")
                    is GroupPublishOutcome.Failed -> notice("削除できませんでした: ${outcome.reason}")
                }
            } finally {
                _state.update { it.copy(isAdminBusy = false) }
            }
        }
    }

    /** 他人の発言を管理者として消せるか（Buzz、owner / admin）。 */
    fun canAdminDelete(message: GroupMessage): Boolean =
        flavor == GroupFlavor.Buzz && _state.value.authority.canRemove && message.event.pubkey != ownPubkey

    /** 他人の発言を消す（kind 9005）。確認は画面で済ませてから呼ぶ。 */
    fun adminDelete(message: GroupMessage) {
        val account = account ?: return
        if (!canAdminDelete(message)) return
        launch {
            when (val outcome = account.publishToGroup(ref, buildAdminDeletion(ref.groupId, message.event.id))) {
                is GroupPublishOutcome.Published -> onEvents(listOf(outcome.event))
                is GroupPublishOutcome.Rejected -> notice("削除できませんでした: ${adminRejectionMessage(outcome.reason)}")
                is GroupPublishOutcome.Failed -> notice("削除できませんでした: ${outcome.reason}")
            }
        }
    }

    fun shareLink(): String? = when (flavor) {
        GroupFlavor.Buzz -> "buzz://channel/${ref.groupId}"
        GroupFlavor.Standard -> (relayPubkey ?: linkPubkey)?.let { com.nostr.torinos.chat.groupShareLink(ref, it) }
    }

    /** メッセージのリンク（標準は nevent、Buzz は `buzz://message`）。 */
    fun messageLink(message: GroupMessage): String = when (flavor) {
        GroupFlavor.Buzz -> buildString {
            append("buzz://message?channel=${ref.groupId}&id=${message.event.id}")
            if (mode is GroupRoomMode.Thread && message.event.id != mode.rootId) append("&thread=${mode.rootId}")
        }
        GroupFlavor.Standard -> "nostr:" + com.nostr.torinos.model.encodeNevent(message.event.id, listOf(ref.relayUrl), message.event.pubkey)
    }

    /** リアクションした人の一覧を開いた。まだ知らない人のプロフィールを、グループのリレーを先にして読む。 */
    fun ensureProfiles(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        launch { ProfileRepository.ensureProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000), relayHint = ref.relayUrl) }
    }

    /** Buzz の外部署名器で、画像を読むための署名を許可した。 */
    fun unlockImages() {
        _state.update { it.copy(imagesUnlocked = true) }
    }

    /**
     * 画像を読むときの `Authorization`。Buzz のコミュニティの `/media/` だけに付ける。
     * 署名の確認が要るとき（外部署名器で未許可）は null（画面は「タップして読み込む」を出す）。
     */
    suspend fun imageAuthorization(url: String): ImageAuth {
        val account = account ?: return ImageAuth.NotNeeded
        val host = buzzMediaHost(url, setOf(ref.relayUrl)) ?: return ImageAuth.NotNeeded
        if (!_state.value.imagesUnlocked) return ImageAuth.NeedsConsent
        return runCatching { ImageAuth.Header(account.blossomAuthorization(host)) }.getOrElse { ImageAuth.Failed }
    }

    private fun notice(message: String) {
        _state.update { it.copy(notice = message) }
    }

    override fun onCleared() {
        subscriptionJob?.cancel()
        typingTimerJob?.cancel()
        typingSendJob?.cancel()
        releasePresence?.invoke()
        releasePresence = null
        stopPresenceReading()
        session?.let { old -> NostrRepository.closeSessionInBackground(old) }
        super.onCleared()
    }
}

/** 画像の読み込みに必要な認証。 */
sealed interface ImageAuth {
    data object NotNeeded : ImageAuth
    data object NeedsConsent : ImageAuth
    data object Failed : ImageAuth
    data class Header(val authorization: String) : ImageAuth
}

private fun NostrEvent.firstTagValue(name: String): String? =
    tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

/** 加えるシートの候補のうち、プロフィールを先に取る人数。 */
private const val ADD_CANDIDATE_PROFILE_LIMIT = 200

/** 一覧に出さない設定（`unlisted`）を扱える Buzz のリレーが名乗る拡張。 */
internal const val BUZZ_UNLISTED_EXTENSION = "buzz-unlisted-channels"

/** メンバー一覧に、送った変更（null は外した）を重ねる。加えた人は末尾に足す。 */
internal fun membersWithPending(members: List<String>, pending: Map<String, ChannelRole?>): List<String> {
    if (pending.isEmpty()) return members
    val removed = pending.filterValues { it == null }.keys
    val added = pending.filterValues { it != null }.keys.filter { it !in members }
    return members.filter { it !in removed } + added
}
