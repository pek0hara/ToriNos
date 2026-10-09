package com.nostr.torinos.ui.post

import com.nostr.torinos.article.ArticleDraftStore
import com.nostr.torinos.article.ArticleStorage
import com.nostr.torinos.article.MemoryArticleStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PendingPostInputsTest {
    private fun memo(text: String, time: Long = 0) = PostMemoData(text, emptyList(), null, null, 1, null, time)

    @Test fun offersOnlyParkedInputsFromThisAccountAndExcludesActiveRecovery() = runTest {
        val store = ArticleDraftStore()
        store.transaction("a") { it.copy(composers = mapOf("current" to memo("current"),
            "handoff" to memo("quote"), "pending-old" to memo("old", 1),
            "pending-new" to memo("new", 2), "pending-active" to memo("active", 3))) }
        val inputs = PendingPostInputs(store, "a")
        assertEquals(listOf("pending-new", "pending-old"), inputs.list("pending-active").map { it.id })
        assertTrue(PendingPostInputs(store, "b").list(null).isEmpty())
    }

    @Test fun applyingPreservesCurrentInputAndRestoresAllFieldsBeforeConsumingSource() = runTest {
        val store = ArticleDraftStore()
        val parked = memo("保留").copy(replyToId = "parent", replyToPubkey = "author", channelId = "channel", noteKind = 42,
            imageUrls = listOf("https://image.example/a"), hasContentWarning = true,
            selectedRelayUrls = setOf("wss://relay.example"), cursorStart = 1, cursorEnd = 2, quoteEventId = "quote")
        val current = memo("編集中")
        store.transaction("a") { it.copy(composers = mapOf("pending-source" to parked, "handoff" to current), handoffs = setOf("handoff")) }
        val inputs = PendingPostInputs(store, "a")
        var restored: PostMemoData? = null
        inputs.apply(inputs.list("handoff").single(), current, "handoff") { restored = it }
        assertEquals(parked, restored)
        assertEquals(parked, store.read("a").composers["current"])
        assertFalse("handoff" in store.read("a").handoffs)
        assertFalse("pending-source" in store.read("a").composers)
        assertEquals(current, inputs.list("current").single().memo)
    }

    @Test fun discardingRemovesOnlyChosenPendingInputAndDoesNotRestoreAnything() = runTest {
        val store = ArticleDraftStore()
        val current = memo("編集中")
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to memo("A"), "pending-b" to memo("B"), "current" to current)) }
        val inputs = PendingPostInputs(store, "a")
        inputs.discard(inputs.list("current").first { it.id == "pending-a" })
        assertEquals(mapOf("pending-b" to memo("B"), "current" to current), store.read("a").composers)
    }

    @Test fun failedMemoryWriteDoesNotTouchEditorAndFailedRestorationKeepsSource() = runTest {
        val memory = MemoryArticleStorage()
        val store = ArticleDraftStore(memory)
        val parked = memo("保留")
        val current = memo("編集中")
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to parked)) }
        val inputs = PendingPostInputs(store, "a")
        val input = inputs.list(null).single()
        memory.failWrite = true
        var restored = false
        assertFailsWith<IllegalStateException> { inputs.apply(input, current, null) { restored = true } }
        assertFalse(restored)
        memory.failWrite = false
        assertFailsWith<IllegalStateException> { inputs.apply(input, current, null) { error("cannot restore") } }
        assertEquals(parked, store.read("a").composers[input.id])
        assertTrue(inputs.list(null).any { it.memo == current })
    }

    @Test fun changedPendingInputCannotBeAppliedOrDiscardedFromStaleDialog() = runTest {
        val store = ArticleDraftStore()
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to memo("old"))) }
        val inputs = PendingPostInputs(store, "a")
        val old = inputs.list(null).single()
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to memo("new"))) }
        assertFailsWith<IllegalStateException> { inputs.apply(old, null, null) { error("must not restore") } }
        assertFailsWith<IllegalStateException> { inputs.discard(old) }
        assertEquals("new", store.read("a").composers[old.id]?.text)
    }

    @Test fun failedConsumptionKeepsBothInputsAndRetryDoesNotDuplicateActiveSource() = runTest {
        val memory = MemoryArticleStorage()
        var writesUntilFailure = Int.MAX_VALUE
        val storage = object : ArticleStorage {
            override suspend fun read(owner: String) = memory.read(owner)
            override suspend fun compareAndSet(owner: String, expected: String?, value: String): Boolean {
                check(--writesUntilFailure != 0) { "memory failure" }
                return memory.compareAndSet(owner, expected, value)
            }
        }
        val store = ArticleDraftStore(storage)
        val parked = memo("保留")
        val current = memo("編集中")
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to parked)) }
        val inputs = PendingPostInputs(store, "a")
        val input = inputs.list(null).single()
        var restored: PostMemoData? = null
        writesUntilFailure = 2
        assertFailsWith<IllegalStateException> { inputs.apply(input, current, null) { restored = it } }
        assertEquals(parked, restored)
        assertEquals(parked, store.read("a").composers[input.id])
        assertTrue(inputs.list(input.id).any { it.memo == current })
        writesUntilFailure = Int.MAX_VALUE
        inputs.apply(input, restored, input.id) { restored = it }
        assertEquals(listOf(current), inputs.list("current").map { it.memo })
        assertEquals(parked, store.read("a").composers["current"])
    }

    @Test fun repeatedRestoreFailuresParkOutgoingInputOnlyOnceDespiteNewSnapshotTimestamp() = runTest {
        val store = ArticleDraftStore()
        val current = memo("編集中", 1).copy(replyToId = "reply", selectedRelayUrls = setOf("wss://relay.example"))
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to memo("保留"))) }
        val inputs = PendingPostInputs(store, "a")
        val input = inputs.list(null).single()
        repeat(3) { retry ->
            assertFailsWith<IllegalStateException> {
                inputs.apply(input, current.copy(updatedAt = retry.toLong()), null) { error("restore failure") }
            }
        }
        assertEquals(2, inputs.list(null).size)
        inputs.apply(input, current.copy(updatedAt = 5), null) { }
        assertEquals(1, inputs.list("current").size)
        assertEquals(current.text, inputs.list("current").single().memo.text)
    }

    @Test fun editedOutgoingInputIsPreservedSeparatelyFromPreviouslyParkedContent() = runTest {
        val store = ArticleDraftStore()
        store.transaction("a") { it.copy(composers = mapOf("pending-a" to memo("保留"))) }
        val inputs = PendingPostInputs(store, "a")
        val input = inputs.list(null).single()
        assertFailsWith<IllegalStateException> { inputs.apply(input, memo("変更前"), null) { error("restore failure") } }
        inputs.apply(input, memo("変更後"), null) { }
        assertEquals(setOf("変更前", "変更後"), inputs.list("current").map { it.memo.text }.toSet())
    }
}
