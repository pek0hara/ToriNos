package com.nostr.torinos.ui.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class HeldEventIndexTest {
    @Test
    fun referencedIdStaysWhileAnyHolderRemains() {
        val index = HeldEventIndex()
        index.add("a", listOf("quoted"))
        index.add("b", listOf("quoted"))

        index.remove("a")

        assertEquals(setOf("b", "quoted"), index.snapshot().resolve())
        index.remove("b")
        assertEquals(emptySet(), index.snapshot().resolve())
    }

    @Test
    fun rememberedSetIsReusedUntilMembershipChanges() {
        val index = HeldEventIndex()
        index.add("a", listOf("parent"))
        val snapshot = index.snapshot()
        val resolved = snapshot.resolve()
        index.remember(snapshot, resolved)

        // 既に含まれる ID の参照が増えても、集合は変わらないので使い回す
        index.add("b", emptyList())
        index.remove("b")
        index.add("c", listOf("parent"))
        index.remove("c")
        assertNotSame(resolved, index.snapshot().resolve())

        val again = index.snapshot()
        val againResolved = again.resolve()
        index.remember(again, againResolved)
        assertSame(againResolved, index.snapshot().resolve())
    }

    @Test
    fun staleSnapshotIsNotRemembered() {
        val index = HeldEventIndex()
        index.add("a", emptyList())
        val snapshot = index.snapshot()
        index.add("b", emptyList())

        index.remember(snapshot, snapshot.resolve())

        assertEquals(setOf("a", "b"), index.snapshot().resolve())
    }

    @Test
    fun addingTheSameEventTwiceDoesNotDoubleCount() {
        val index = HeldEventIndex()
        index.add("a", listOf("parent"))
        index.add("a", listOf("parent"))

        index.remove("a")

        assertEquals(emptySet(), index.snapshot().resolve())
    }
}
