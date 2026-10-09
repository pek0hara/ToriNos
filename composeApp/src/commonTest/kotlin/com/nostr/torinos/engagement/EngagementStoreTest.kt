package com.nostr.torinos.engagement

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngagementStoreTest {
    private val owner = Any()

    @Test
    fun keepsEventsOnlyForRetainedTargets() {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))

        store.observe(reaction("r1", TARGET), "wss://a")
        store.observe(reaction("r2", OTHER), "wss://a")

        assertEquals(setOf("r1"), store.snapshot(TARGET)?.reactions?.keys)
        assertNull(store.snapshot(OTHER))
    }

    @Test
    fun sameEventObservedTwiceIsKeptOnceWithEveryRelay() {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))

        store.observe(reaction("r1", TARGET), "wss://a")
        store.observe(reaction("r1", TARGET), "wss://b")

        val engagement = store.snapshot(TARGET)!!
        assertEquals(1, engagement.reactions.size)
        assertEquals(setOf("wss://a", "wss://b"), engagement.sourceRelays.getValue("r1"))
    }

    @Test
    fun classifiesRepliesRepostsAndQuotesByTheirTarget() {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))

        store.observe(event("reply", 1, listOf(listOf("e", TARGET, "", "root"))), null)
        store.observe(event("repost", 6, listOf(listOf("e", TARGET), listOf("p", "author"))), null)
        store.observe(event("generic", 16, listOf(listOf("e", TARGET), listOf("k", "1"))), null)
        store.observe(event("quote", 1, listOf(listOf("q", TARGET))), null)
        // 対応外の kind 1111（ルートが kind 1 でない等）は返信として数えない。
        store.observe(event("comment", 1111, listOf(listOf("e", TARGET))), null)

        val engagement = store.snapshot(TARGET)!!
        assertEquals(setOf("reply"), engagement.replies.keys)
        assertEquals(setOf("repost", "generic"), engagement.reposts.keys)
        assertEquals(setOf("quote"), engagement.quotes.keys)
    }

    @Test
    fun deletionByTheAuthorRemovesTheEventAndKeepsItOut() {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))
        store.observe(reaction("mine", TARGET, pubkey = "me"), "wss://a")
        store.observe(reaction("theirs", TARGET, pubkey = "them"), "wss://a")

        store.observe(event("del", 5, listOf(listOf("e", "mine")), pubkey = "me"), "wss://a")
        // 作者以外の削除要求は無視する。
        store.observe(event("del2", 5, listOf(listOf("e", "theirs")), pubkey = "me"), "wss://a")
        // 削除が届いていないリレーから、同じリアクションがもう一度届いても入れない。
        store.observe(reaction("mine", TARGET, pubkey = "me"), "wss://b")

        assertEquals(setOf("theirs"), store.snapshot(TARGET)?.reactions?.keys)
    }

    @Test
    fun deletionArrivingBeforeTheEventStillKeepsItOut() {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))

        store.observe(event("del", 5, listOf(listOf("e", "mine"), listOf("k", "7")), pubkey = "me"), "wss://a")
        store.observe(reaction("mine", TARGET, pubkey = "me"), "wss://b")
        store.observe(reaction("forged", TARGET, pubkey = "them"), "wss://b")

        assertEquals(setOf("forged"), store.snapshot(TARGET)?.reactions?.keys)
    }

    @Test
    fun eventsObservedBeforeTheTargetIsRetainedAreAttachedWhenRetained() {
        // フィードはリポスト（タイムライン）を、リポスト先の投稿を追跡し始める前に受け取る。
        val store = EngagementStore()
        store.observe(event("repost", 6, listOf(listOf("e", TARGET))), "wss://a")
        store.observe(reaction("r1", TARGET), "wss://b")

        store.retain(owner, setOf(TARGET))

        val engagement = store.snapshot(TARGET)!!
        assertEquals(setOf("repost"), engagement.reposts.keys)
        assertEquals(setOf("r1"), engagement.reactions.keys)
        assertEquals(setOf("wss://a"), engagement.sourceRelays.getValue("repost"))
    }

    @Test
    fun eventsForUnretainedTargetsAreKeptOnlyUpToTheLimit() {
        val store = EngagementStore(maxUnassignedEvents = 2)
        store.observe(reaction("old", TARGET), null)
        store.observe(reaction("mid", TARGET), null)
        store.observe(reaction("new", TARGET), null)

        store.retain(owner, setOf(TARGET))

        assertEquals(setOf("mid", "new"), store.snapshot(TARGET)?.reactions?.keys)
    }

    @Test
    fun deletionAlsoRemovesEventsWaitingForTheirTarget() {
        val store = EngagementStore()
        store.observe(reaction("mine", TARGET, pubkey = "me"), null)
        store.observe(event("del", 5, listOf(listOf("e", "mine")), pubkey = "me"), null)

        store.retain(owner, setOf(TARGET))

        assertEquals(emptySet(), store.snapshot(TARGET)?.reactions?.keys)
    }

    @Test
    fun releasingTheLastOwnerDropsTheTarget() {
        val store = EngagementStore()
        val second = Any()
        store.retain(owner, setOf(TARGET))
        store.retain(second, setOf(TARGET))
        store.observe(reaction("r1", TARGET), null)

        store.release(owner)
        assertEquals(1, store.snapshot(TARGET)?.reactions?.size)
        store.release(second)

        assertNull(store.snapshot(TARGET))
    }

    @Test
    fun retainReplacesTheOwnersTargets() {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))
        store.observe(reaction("r1", TARGET), null)

        store.retain(owner, setOf(OTHER))

        assertNull(store.snapshot(TARGET))
        assertEquals(0, store.snapshot(OTHER)?.reactions?.size)
    }

    @Test
    fun notifiesChangedTargets() = runTest {
        val store = EngagementStore()
        store.retain(owner, setOf(TARGET))
        val received = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.changes.collect { received += it } }

        store.observe(reaction("r1", TARGET), null)
        store.observe(reaction("r1", TARGET), null)
        store.observe(reaction("r2", OTHER), null)

        // 変化がなかった受信（同じイベント・保持していない投稿）では通知しない。
        assertEquals(listOf(setOf(TARGET)), received)
    }

    private fun reaction(id: String, target: String, pubkey: String = "reactor-$id") =
        event(id, 7, listOf(listOf("e", target), listOf("p", "author")), content = "+", pubkey = pubkey)

    private fun event(
        id: String,
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        pubkey: String = "someone",
        createdAt: Long = 100,
    ) = NostrEvent(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind, tags = tags, content = content, sig = "sig")

    private companion object {
        const val TARGET = "target"
        const val OTHER = "other"
    }
}
