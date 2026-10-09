package com.nostr.torinos.article

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.extractNostrEventReferences
import com.nostr.torinos.ui.post.composeNoteContent
import kotlin.test.*

class MatometrReplySelectionTest {
    private val author = "aa".repeat(32)
    private val root = "ff".repeat(32)
    private fun event(number: Int, kind: Int, tags: List<List<String>> = emptyList()) =
        NostrEvent(number.toString(16).padStart(64, '0'), author, 1, kind, tags, "投稿$number", "")
    private fun reply(number: Int, rootKind: Int = 1) = event(number, COMMENT_EVENT_KIND, listOf(
        listOf("E", root, "", author), listOf("K", rootKind.toString()), listOf("P", author),
        listOf("e", root, "", author), listOf("k", rootKind.toString()), listOf("p", author),
    ))

    @Test fun notesLegacyRepliesAndCommentsCanBeSelectedInOneOrderedList() {
        val coordinator = MatometrSelectionCoordinator()
        val note = event(1, 1)
        val legacyReply = event(2, 1, listOf(listOf("e", root, "", "reply")))
        val comment = reply(3)
        val articleComment = reply(4, 30023)
        listOf(note, legacyReply, comment, articleComment).forEach {
            assertTrue(it.canSelectForMatometr())
            coordinator.toggle(it)
        }
        assertEquals(listOf(note, legacyReply, comment, articleComment), coordinator.state.value.posts.map { it.event })
        coordinator.move(comment.id, -1)
        assertEquals(listOf(note.id, comment.id, legacyReply.id, articleComment.id), coordinator.state.value.posts.map { it.event.id })
        coordinator.hide(); coordinator.start()
        coordinator.toggle(comment)
        assertEquals(listOf(note.id, legacyReply.id, articleComment.id), coordinator.state.value.posts.map { it.event.id })
    }

    @Test fun unrelatedEventKindsAreStillExcluded() {
        val coordinator = MatometrSelectionCoordinator()
        listOf(4, 7, 14, 42, 1059, 30023).forEach { kind ->
            val unsupported = event(kind, kind)
            assertFalse(unsupported.canSelectForMatometr())
            coordinator.toggle(unsupported)
        }
        assertFalse(coordinator.state.value.active)
        assertTrue(coordinator.state.value.posts.isEmpty())
    }

    @Test fun articleAndQuoteCreationReferenceTheReplyInsteadOfItsParent() {
        val coordinator = MatometrSelectionCoordinator()
        val comment = reply(1)
        coordinator.toggle(comment, listOf("wss://relay.example"))
        val selection = coordinator.beginHandoff()!!
        val selected = selection.posts.single()
        assertEquals(comment.id, selected.reference.eventId)
        val article = ArticleDraft(ownerPubkey = author, mode = ArticleDraftMode.MatometrStructured,
            references = selection.posts.map { it.reference })
        assertNull(validateMatometr(article))
        assertEquals(listOf(comment.id), extractNostrEventReferences(article.content).map { it.eventId })
        val memo = buildMatometrQuoteMemo(null, selection.posts)
        assertEquals(comment.content, memo.quotes.single().displayPreview)
        val published = composeNoteContent(memo.text, emptyList(), emptyList(), quotes = memo.quotes)!!
        assertEquals(listOf(comment.id), extractNostrEventReferences(published.content).map { it.eventId })
        assertTrue(listOf("q", comment.id, "wss://relay.example") in published.tags)
        assertTrue(published.tags.none { it.first() == "e" || it.first() == "E" })
    }

    @Test fun repliesRespectBusyFrozenCommittedAndSelectionLimitGuards() {
        var editable = true
        val coordinator = MatometrSelectionCoordinator { editable }
        (1..50).forEach { coordinator.toggle(reply(it)) }
        coordinator.toggle(reply(51))
        assertEquals(50, coordinator.state.value.posts.size)
        assertEquals("選択できる投稿は50件までです", coordinator.state.value.error)
        coordinator.beginHandoff()
        coordinator.toggle(reply(1))
        assertEquals(50, coordinator.state.value.posts.size)
        coordinator.failHandoff("retry")
        editable = false
        coordinator.toggle(reply(1))
        assertEquals(50, coordinator.state.value.posts.size)
        editable = true
        coordinator.markCommitted("committed")
        coordinator.toggle(reply(1))
        assertEquals(50, coordinator.state.value.posts.size)
    }
}
