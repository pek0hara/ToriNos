package com.nostr.torinos.ui.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FollowerCountStoreTest {
    private class FakeStorage {
        val values = mutableMapOf<String, String?>()
        suspend fun read(key: String): String? = values[key]
        suspend fun write(key: String, value: String?) { values[key] = value }
    }

    private fun store(storage: FakeStorage, now: () -> Long, maxEntries: Int = 200) = FollowerCountStore(
        readStorage = storage::read,
        writeStorage = storage::write,
        now = now,
        maxEntries = maxEntries,
    )

    @Test
    fun recordedCountIsRestoredByAnotherInstance() = runTest {
        val storage = FakeStorage()
        store(storage, now = { 1_000L }).record("alice", count = 124, limited = false)

        val restored = store(storage, now = { 2_000L })
        restored.ensureLoaded()

        assertEquals(FollowerCountSnapshot(124, limited = false, countedAt = 1_000L), restored.counts.value["alice"])
    }

    @Test
    fun smallerCountOverwritesThePreviousOne() = runTest {
        val storage = FakeStorage()
        var clock = 1_000L
        val store = store(storage, now = { clock })
        store.record("alice", count = 124, limited = true)
        clock = 2_000L
        store.record("alice", count = 80, limited = false)

        assertEquals(FollowerCountSnapshot(80, limited = false, countedAt = 2_000L), store.counts.value["alice"])
    }

    @Test
    fun oldestEntriesAreDroppedBeyondTheLimit() = runTest {
        val storage = FakeStorage()
        var clock = 0L
        val store = store(storage, now = { clock }, maxEntries = 2)
        clock = 1L; store.record("a", 1, false)
        clock = 2L; store.record("b", 2, false)
        clock = 3L; store.record("a", 3, false) // a を数え直すと一番新しくなる
        clock = 4L; store.record("c", 4, false)

        assertEquals(setOf("a", "c"), store.counts.value.keys)
        val restored = store(storage, now = { 5L }, maxEntries = 2)
        restored.ensureLoaded()
        assertEquals(setOf("a", "c"), restored.counts.value.keys)
    }

    @Test
    fun brokenSavedValueIsTreatedAsEmpty() = runTest {
        val storage = FakeStorage()
        storage.values["follower_counts_v1"] = "{not json"
        val store = store(storage, now = { 1L })
        store.ensureLoaded()

        assertTrue(store.counts.value.isEmpty())
        store.record("alice", 3, false)
        assertEquals(3, store.counts.value["alice"]?.count)
    }

    @Test
    fun recordBeforeLoadKeepsEarlierSavedEntries() = runTest {
        val storage = FakeStorage()
        store(storage, now = { 1L }).record("alice", 10, false)

        val second = store(storage, now = { 2L })
        second.record("bob", 20, false)

        assertEquals(setOf("alice", "bob"), second.counts.value.keys)
    }

    @Test
    fun recordsOnlyFinishedNonEmptyCounts() {
        assertTrue(shouldRecordFollowerCount(cancelled = false, count = 1))
        assertFalse(shouldRecordFollowerCount(cancelled = false, count = 0))
        assertFalse(shouldRecordFollowerCount(cancelled = true, count = 50))
    }

    @Test
    fun limitedWhenReceivedEventsReachTheLimit() {
        assertFalse(isFollowerCountLimited(receivedEvents = 499, limit = 500))
        assertTrue(isFollowerCountLimited(receivedEvents = 500, limit = 500))
    }

    @Test
    fun ageLabelBoundaries() {
        val now = 100_000L
        assertEquals("たった今", followerCountAgeLabel(now - 59, now))
        assertEquals("1分前", followerCountAgeLabel(now - 60, now))
        assertEquals("59分前", followerCountAgeLabel(now - 3_599, now))
        assertEquals("1時間前", followerCountAgeLabel(now - 3_600, now))
        assertEquals("23時間前", followerCountAgeLabel(now - 86_399, now))
        assertEquals("1日前", followerCountAgeLabel(now - 86_400, now))
        assertEquals("たった今", followerCountAgeLabel(now + 30, now)) // 時計のずれで未来でも負にしない
    }

    @Test
    fun displayStates() {
        val stored = FollowerCountSnapshot(124, limited = true, countedAt = 10L)

        assertEquals(FollowerStatDisplay.Unknown, followerStatDisplay(stored = null, isLoading = false, liveCount = 0))
        assertEquals(FollowerStatDisplay.Counting(37), followerStatDisplay(stored = null, isLoading = true, liveCount = 37))
        assertEquals(
            FollowerStatDisplay.Known(124, limited = true, countedAt = 10L, refreshing = false),
            followerStatDisplay(stored = stored, isLoading = false, liveCount = 0),
        )
        // 数え直し中は届いた途中の数ではなく保存値を出す
        assertEquals(
            FollowerStatDisplay.Known(124, limited = true, countedAt = 10L, refreshing = true),
            followerStatDisplay(stored = stored, isLoading = true, liveCount = 12),
        )
    }

    @Test
    fun unknownStoreEntryIsNull() = runTest {
        val store = store(FakeStorage(), now = { 1L })
        store.ensureLoaded()
        assertNull(store.counts.value["nobody"])
    }

    @Test
    fun newFollowerAddsOneAndKeepsTheCountedTime() = runTest {
        val storage = FakeStorage()
        var clock = 1_000L
        val store = store(storage, now = { clock })
        store.record("me", count = 124, limited = false)
        clock = 9_000L
        store.recordNewFollower("me")

        assertEquals(FollowerCountSnapshot(125, limited = false, countedAt = 1_000L), store.counts.value["me"])
        val restored = store(storage, now = { clock })
        restored.ensureLoaded()
        assertEquals(125, restored.counts.value["me"]?.count)
    }

    @Test
    fun newFollowerWithoutAFullCountDoesNothing() = runTest {
        val store = store(FakeStorage(), now = { 1L })
        store.recordNewFollower("me")

        assertNull(store.counts.value["me"])
    }

    @Test
    fun fullCountPublishesTheFollowerPubkeys() = runTest {
        val store = store(FakeStorage(), now = { 1L })
        val received = mutableListOf<CompletedFollowerCount>()
        store.completedCounts.onEach { received += it }.launchIn(backgroundScope)
        runCurrent()

        store.record("me", count = 2, limited = false, followers = setOf("a", "b"))
        runCurrent()

        assertEquals(listOf(CompletedFollowerCount("me", setOf("a", "b"))), received)
    }
}
