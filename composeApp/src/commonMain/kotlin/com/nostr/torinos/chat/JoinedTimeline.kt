package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.TimeZone
import com.nostr.torinos.util.timelineTraceLog
import kotlin.time.TimeSource

/** タイムラインで1回に読む、リレーごとの投稿の数。 */
const val JOINED_TIMELINE_PAGE_SIZE = 100
private const val REPLIES_LIMIT = 500
private const val IDS_PER_FILTER = 100
private const val TARGETS_PER_FILTER = 200

/** タイムラインに含める参加中のグループ。 */
data class TimelineGroup(val ref: GroupRef, val flavor: GroupFlavor, val relayPubkey: String?)

/** 1回の読み込みの結果。[answered] はリレーが最後まで答えたか。 */
data class TimelineFetch(val events: List<NostrEvent>, val answered: Boolean)

/** タイムラインの1行（docs/chat-joined-timeline-design.md 1.1）。 */
data class JoinedTimelineEntry(val ref: GroupRef, val conversation: GroupConversation) {
    val key: String get() = ref.key + "/" + conversation.rootId

    /** 押したときにスレッドの画面を開くか。返信の無いチャットの投稿は、グループの画面を開く。 */
    val opensThread: Boolean
        get() {
            val root = conversation.root ?: return true
            if (conversation.latestReply != null) return true
            return root.event.kind == BUZZ_FORUM_POST_KIND || root.event.kind == GROUP_THREAD_KIND
        }
}

/** 「今日」と「昨日以前」に分ける（端末の日付）。 */
fun splitTodayEntries(
    entries: List<JoinedTimelineEntry>,
    nowEpochSeconds: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<List<JoinedTimelineEntry>, List<JoinedTimelineEntry>> {
    val today = chatLocalDate(nowEpochSeconds, zone)
    return entries.partition { chatLocalDate(it.conversation.lastActivity, zone) >= today }
}

/**
 * 参加中のグループの投稿をリレーごとに読み、会話にまとめる（docs/chat-joined-timeline-design.md 3章）。
 * [load] と [loadOlder] は同時に呼ばない（呼び出し側で順に呼ぶ）。
 */
class JoinedTimelineLoader(
    private val ownPubkey: String?,
    private val fetch: suspend (relayUrl: String, filters: List<NostrFilter>) -> TimelineFetch,
    private val pageSize: Int = JOINED_TIMELINE_PAGE_SIZE,
) {
    private inner class RelayState(val relayUrl: String, val groups: List<TimelineGroup>) {
        val flavor = groups.first().flavor
        val relayPubkey = groups.firstNotNullOfOrNull { it.relayPubkey }
        val groupIds = groups.map { it.ref.groupId }
        val timelines = groups.associate { it.ref.groupId to GroupTimeline(it.ref.groupId, ownPubkey, it.flavor, it.relayPubkey) }
        val pager = OlderPager(pageSize)
        val ids = mutableSetOf<String>()
        var oldest: Long? = null
        /** 1ページ目を（一部でも）読めた。 */
        var started = false
        /** 1ページ目の続き（ルート・返信・削除・編集）まで読み終えた。これより前は行に出さず、表示の下限にもしない。 */
        var shown = false
        /** bot（エージェントの発言 40002 を出した人と、メンバー一覧 39002 で役割が bot の人）。Buzz だけ。 */
        val bots = mutableSetOf<String>()

        val chatKinds = if (flavor == GroupFlavor.Buzz) {
            listOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND, BUZZ_FORUM_POST_KIND, BUZZ_FORUM_COMMENT_KIND)
        } else {
            listOf(GROUP_CHAT_KIND, GROUP_THREAD_KIND, GROUP_COMMENT_KIND)
        }

        /** まだ続きがあり、ほかのリレーと混ぜるときに下限になる。 */
        val bounding: Boolean get() = shown && pager.canLoadOlder && oldest != null
    }

    private var relays: List<RelayState> = emptyList()

    /** 直近の読み込みで答えなかったリレー。 */
    var failedRelays: Set<String> = emptySet()
        private set

    val canLoadOlder: Boolean get() = relays.any { it.bounding }

    /**
     * 読む。リレーごとに並行して読み、1つ読み終えるたびに [onRelayLoaded] を呼ぶ。そのときの [entries] は、
     * 読み終えたリレーの分だけで作る（docs/chat-joined-timeline-design.md 3.3）。
     */
    suspend fun load(groups: List<TimelineGroup>, onRelayLoaded: () -> Unit = {}) {
        val states = groups.groupBy { it.ref.relayUrl }.map { (relayUrl, list) -> RelayState(relayUrl, list) }
        relays = states
        val failed = coroutineScope {
            states.map { state ->
                async {
                    val answered = loadPage(state, until = null)
                    onRelayLoaded()
                    state.relayUrl.takeUnless { answered }
                }
            }.awaitAll()
        }.filterNotNull().toSet()
        failedRelays = failed
    }

    /** 表示の下限を決めているリレーだけ、さらに古いページを読む。 */
    suspend fun loadOlder() {
        val floor = floor() ?: return
        val targets = relays.filter { it.bounding && it.oldest == floor }
        val failed = coroutineScope {
            targets.map { state -> async { state.relayUrl.takeUnless { loadPage(state, until = state.pager.until()) } } }.awaitAll()
        }.filterNotNull().toSet()
        failedRelays = failed
    }

    /**
     * 最後の動きの新しい順。読めている範囲がそろっているところまで（3.1）。
     * [hideBots] では bot の投稿を出さない。bot の投稿に人の返信があれば、返信だけを残す（docs/chat-joined-timeline-design.md 2.1）。
     */
    fun entries(muted: Set<String> = emptySet(), hideBots: Boolean = false): List<JoinedTimelineEntry> {
        val floor = floor()
        return relays
            .filter { it.shown }
            .flatMap { state ->
                val hidden = if (hideBots) state.bots else emptySet()
                state.timelines.flatMap { (groupId, timeline) ->
                    timeline.conversations(muted, hidden).map { JoinedTimelineEntry(GroupRef(state.relayUrl, groupId), it) }
                }
            }
            .filter { floor == null || it.conversation.lastActivity >= floor }
            .sortedWith(compareByDescending<JoinedTimelineEntry> { it.conversation.lastActivity }.thenBy { it.key })
    }

    /** bot を隠すと出なくなる投稿の数（表示の範囲の中。ミュートした bot は数えない）。 */
    fun hiddenBotPosts(muted: Set<String> = emptySet()): Int {
        val floor = floor()
        return relays.filter { it.shown }.sumOf { state ->
            val bots = state.bots - muted
            state.timelines.values.sumOf { it.countPosts(bots, floor) }
        }
    }

    private fun floor(): Long? = relays.filter { it.bounding }.mapNotNull { it.oldest }.maxOrNull()

    /** 1ページを読み、続き（ルート・返信・削除・編集）も読む。リレーが最後まで答えたら true。 */
    private suspend fun loadPage(state: RelayState, until: Long?): Boolean {
        val started = TimeSource.Monotonic.markNow()
        val page = fetch(
            state.relayUrl,
            listOf(NostrFilter(kinds = state.chatKinds, hTags = state.groupIds, until = until, limit = pageSize)),
        )
        timelineTraceLog { "${host(state)} page until=$until n=${page.events.size} answered=${page.answered} ${started.elapsedNow().inWholeMilliseconds}ms" }
        if (!page.answered && page.events.isEmpty()) return false
        val fresh = state.pager.onPage(page.events, page.answered)
        page.events.minOfOrNull { it.createdAt }?.let { min -> state.oldest = minOf(state.oldest ?: min, min) }
        val firstPage = !state.started
        state.started = true
        add(state, fresh)
        val followUp = followUp(state, fresh, firstPage)
        state.shown = true
        timelineTraceLog { "${host(state)} done ${started.elapsedNow().inWholeMilliseconds}ms" }
        return page.answered && followUp
    }

    private suspend fun followUp(state: RelayState, fresh: List<NostrEvent>, firstPage: Boolean): Boolean {
        if (fresh.isEmpty()) return true
        val rootRefs = fresh.mapNotNull { groupThreadRootId(it, state.flavor) }.toSet()
        val rootIds = (fresh.filter { groupThreadRootId(it, state.flavor) == null }.map { it.id } + rootRefs).distinct()
        val missing = (rootRefs - state.ids).toList()
        val threadFilters = missing.chunked(IDS_PER_FILTER).map { NostrFilter(ids = it, hTags = state.groupIds) } +
            rootIds.chunked(IDS_PER_FILTER).map { chunk ->
                if (state.flavor == GroupFlavor.Buzz) {
                    NostrFilter(
                        kinds = listOf(GROUP_CHAT_KIND, BUZZ_AGENT_MESSAGE_KIND, BUZZ_FORUM_COMMENT_KIND),
                        eTags = chunk,
                        hTags = state.groupIds,
                        limit = REPLIES_LIMIT,
                    )
                } else {
                    NostrFilter(kinds = listOf(GROUP_COMMENT_KIND), rootEventTags = chunk, hTags = state.groupIds, limit = REPLIES_LIMIT)
                }
            }
        val relayKey = state.relayPubkey
        // 1ページ目の投稿の削除・編集と管理者・メンバー一覧は、続きの答えを待たずに同時に読む（3.3）。
        val pageExtraFilters = editFilters(fresh.map { it.id }) + listOfNotNull(
            relayKey?.takeIf { firstPage }?.let {
                // Buzz ではメンバー一覧も読み、役割が bot の人を知る（標準のリレーは一覧が大きくなりうるので読まない）。
                val kinds = if (state.flavor == GroupFlavor.Buzz) listOf(GROUP_ADMINS_KIND, GROUP_MEMBERS_KIND) else listOf(GROUP_ADMINS_KIND)
                NostrFilter(kinds = kinds, authors = listOf(it), dTags = state.groupIds)
            },
        )
        val (thread, extra) = coroutineScope {
            val thread = async { traced(state, "thread", threadFilters) }
            val extra = async { traced(state, "extra", pageExtraFilters) }
            thread.await() to extra.await()
        }
        val threadFresh = thread.events.filter { it.id !in state.ids }
        add(state, threadFresh)
        val admins = extra.events.filter { it.kind == GROUP_ADMINS_KIND }
        add(state, extra.events.filter { it.kind != GROUP_ADMINS_KIND && it.kind != GROUP_MEMBERS_KIND && it.id !in state.ids })
        if (firstPage) {
            setModerators(state, admins)
            addBotMembers(state, extra.events.filter { it.kind == GROUP_MEMBERS_KIND })
        }
        // 続きで新しく読めた投稿（ページより古い返信・ルート）の削除・編集。無ければ送らない。
        val threadEdits = traced(state, "thread-edits", editFilters(threadFresh.map { it.id }))
        add(state, threadEdits.events.filter { it.id !in state.ids })
        return thread.answered && extra.answered && threadEdits.answered
    }

    private fun editFilters(targets: List<String>): List<NostrFilter> = targets.chunked(TARGETS_PER_FILTER).map {
        NostrFilter(kinds = listOf(5, GROUP_DELETE_EVENT_KIND, BUZZ_EDIT_KIND), eTags = it)
    }

    private suspend fun traced(state: RelayState, label: String, filters: List<NostrFilter>): TimelineFetch {
        if (filters.isEmpty()) return TimelineFetch(emptyList(), answered = true)
        val started = TimeSource.Monotonic.markNow()
        val result = fetch(state.relayUrl, filters)
        timelineTraceLog { "${host(state)} $label filters=${filters.size} n=${result.events.size} answered=${result.answered} ${started.elapsedNow().inWholeMilliseconds}ms" }
        return result
    }

    private fun host(state: RelayState) = state.relayUrl.substringAfter("://").trimEnd('/')

    private fun add(state: RelayState, events: List<NostrEvent>) {
        if (events.isEmpty()) return
        events.forEach { state.ids.add(it.id) }
        events.filter { it.kind == BUZZ_AGENT_MESSAGE_KIND }
            .forEach { state.bots.add(groupMessageAuthor(it, state.flavor, state.relayPubkey)) }
        state.timelines.values.forEach { it.add(events) }
    }

    private fun addBotMembers(state: RelayState, memberLists: List<NostrEvent>) {
        if (state.flavor != GroupFlavor.Buzz) return
        state.groupIds.forEach { groupId ->
            channelRoles(memberLists, groupId, state.relayPubkey)
                ?.filterValues { it == ChannelRole.Bot }
                ?.keys
                ?.let(state.bots::addAll)
        }
    }

    private fun setModerators(state: RelayState, admins: List<NostrEvent>) {
        state.timelines.forEach { (groupId, timeline) ->
            val latest = admins
                .filter { event -> event.tags.any { it.firstOrNull() == "d" && it.getOrNull(1) == groupId } }
                .maxByOrNull { it.createdAt }
            timeline.setModerators(latest?.let { parseGroupAdmins(it, state.relayPubkey) }.orEmpty() + setOfNotNull(state.relayPubkey))
        }
    }
}

/** タイムラインの行に出す1行の本文。フォーラムの投稿・kind 11 は見出し、Buzz は Markdown の記号を外す。 */
fun timelineMessagePreview(message: GroupMessage, flavor: GroupFlavor): String {
    val event = message.event
    if (event.kind == BUZZ_FORUM_POST_KIND || event.kind == GROUP_THREAD_KIND) return groupPostTitle(event)
    return if (flavor == GroupFlavor.Buzz) {
        chatListPreview(message.body).orEmpty()
    } else {
        message.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }
}
