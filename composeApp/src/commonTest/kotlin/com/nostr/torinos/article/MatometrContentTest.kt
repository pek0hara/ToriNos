package com.nostr.torinos.article

import com.nostr.torinos.model.*
import com.nostr.torinos.ui.article.buildArticleTags
import kotlin.test.*

class MatometrContentTest {
    private val first = MatometrReference("11".repeat(32), "aa".repeat(32), listOf("wss://relay.example", "wss://" + "x".repeat(300)))
    private val second = MatometrReference("22".repeat(32), "bb".repeat(32))
    @Test fun articlePublicationIsLimitedToTenThousandCharactersIncludingGeneratedReferences() {
        val regular = ArticleDraft(ownerPubkey = "a", title = "title", bodyMarkdown = "あ".repeat(10_000))
        assertNull(validateArticleDraft(regular))
        assertEquals("生成後の本文は10,000文字までです", validateArticleDraft(regular.copy(bodyMarkdown = "あ".repeat(10_001))))
        assertEquals("生成後の本文は10,000文字までです", validateArticleDraft(regular.copy(mode = ArticleDraftMode.MatometrStructured,
            bodyMarkdown = "あ".repeat(9_999), references = listOf(first))))
    }
    @Test fun publicationLimitsAlsoApplyToFreeMarkdownAndInvalidReferences() {
        val refs = (1..51).map { MatometrReference(it.toString(16).padStart(64, '0')) }
        val structured = ArticleDraft(ownerPubkey = "a", mode = ArticleDraftMode.MatometrStructured, references = refs)
        assertEquals("まとめる投稿は50件までです", validateMatometr(structured))
        assertEquals("まとめる投稿は50件までです", validateMatometr(structured.copy(mode = ArticleDraftMode.MatometrMarkdown,
            references = emptyList(), bodyMarkdown = refs.joinToString("\n\n") { it.uri })))
        assertEquals("本文のコードブロックを閉じてください", validateMatometr(structured.copy(mode = ArticleDraftMode.MatometrMarkdown,
            references = emptyList(), bodyMarkdown = first.uri + "\n\n~~~")))
        assertEquals("引用の投稿IDが不正です", validateMatometr(structured.copy(references = listOf(first.copy(eventId = "invalid")))))
    }
    @Test fun serializeKeepsOrderAndCommentaryWithTagOnlyInMetadata() {
        val d = ArticleDraft(ownerPubkey = "aa".repeat(32), mode = ArticleDraftMode.MatometrStructured,
            bodyMarkdown = "導入", references = listOf(second.copy(commentaryMarkdown = "二番の説明"), first.copy(commentaryMarkdown = "一番の説明")))
        assertEquals(listOf(second.eventId, first.eventId), parseArticleMarkdown(d.content, emptyList()).sections.flatMap { it.quoteIds })
        assertTrue(d.content.indexOf("二番の説明") < d.content.indexOf("一番の説明"))
        assertEquals("導入\n\n${second.uri}\n\n二番の説明\n\n${first.uri}\n\n一番の説明", d.content)
        assertFalse(d.content.contains("#matometr"))
        assertEquals(null, validateMatometr(d))
        val topics = buildArticleTags("d", "title", "", "", (1..20).joinToString(" ") { "topic$it" } + " #matometr", 1, true)
            .filter { it.first() == "t" }.map { it[1] }
        assertEquals(20, topics.size)
        assertEquals(1, topics.count { it == "matometr" })
        assertEquals("matometr", topics.first())
        assertEquals(listOf("wss://relay.example"), extractNostrEventReferences(first.uri).single().relayUrls)
    }
    @Test fun freeMarkdownDoesNotGainAnAutomaticTagAndKeepsWrittenHashtags() {
        val body = "導入\n\n${first.uri}"
        val draft = ArticleDraft(ownerPubkey = "a", mode = ArticleDraftMode.MatometrMarkdown, bodyMarkdown = body)
        assertEquals(body, draft.content)
        assertEquals(body, draft.copy(matometrTagEnabled = false).content)
        assertEquals("$body\n\n#matometr", draft.copy(bodyMarkdown = "$body\n\n#matometr").content)
    }
    @Test fun serializationPreservesMarkdownIndentation() {
        val indented = "    ${first.uri}"
        assertEquals(indented, ArticleDraft(ownerPubkey = "a", bodyMarkdown = indented).content)
        assertNotNull(validateMatometr(ArticleDraft(ownerPubkey = "a", mode = ArticleDraftMode.MatometrMarkdown, bodyMarkdown = indented)))
        val structured = ArticleDraft(ownerPubkey = "a", mode = ArticleDraftMode.MatometrStructured, bodyMarkdown = "    code", references = listOf(first))
        assertTrue(structured.content.startsWith("    code"))
        assertEquals(listOf(first.eventId), parseArticleMarkdown(structured.content, emptyList()).sections.flatMap { it.quoteIds })
    }
    @Test fun fencesUseMatchingCharacterLengthAndMaximumThreeSpaces() {
        assertFalse(ArticleMarkdownBoundaryValidator.isClosed("````kt\n```"))
        assertFalse(ArticleMarkdownBoundaryValidator.isClosed("~~~\n```"))
        assertTrue(ArticleMarkdownBoundaryValidator.isClosed("   ~~~~kt\nbody\n   ~~~~~ "))
        assertTrue(ArticleMarkdownBoundaryValidator.isClosed("    ```"))
        assertEquals(emptyList(), parseArticleMarkdown("~~~\n${first.uri}\n~~~", emptyList()).sections.flatMap { it.quoteIds })
        assertEquals(emptyList(), parseArticleMarkdown("````\n${first.uri}\n```\n${second.uri}\n````", emptyList()).sections.flatMap { it.quoteIds })
    }
    @Test fun inlineCodeImagesAndIndentedCodeDoNotBecomeQuotes() {
        val body = "``${first.uri}``\n![alt ${second.uri}](https://example.com/a.png)\n    ${second.uri}\n\t${second.uri}"
        assertEquals(emptyList(), parseArticleMarkdown(body, emptyList()).sections.flatMap { it.quoteIds })
        assertTrue((parseArticleMarkdown("``${first.uri}``", emptyList()).sections.single().block as MarkdownBlock.Paragraph).text.contains(first.uri))
    }
    @Test fun unfinishedMarkdownCanBeSavedButStructuredPublicationIsBlocked() {
        val d = ArticleDraft(ownerPubkey = "aa".repeat(32), mode = ArticleDraftMode.MatometrStructured, bodyMarkdown = "~~~", references = listOf(first))
        assertEquals("導入文のコードブロックを閉じてください", validateMatometr(d))
        assertEquals("まとめる投稿を1件以上追加してください", validateMatometr(d.copy(bodyMarkdown = "", references = emptyList())))
        assertNotNull(validateMatometr(d.copy(mode = ArticleDraftMode.MatometrMarkdown, bodyMarkdown = "```${first.uri}```")))
    }
    @Test fun duplicatesAndAccountFreezeDoNotChangeTheSelection() {
        var editable = true
        val coordinator = MatometrSelectionCoordinator { editable }
        fun event(id: String) = NostrEvent(id, "aa".repeat(32), 1, 1, emptyList(), "post", "")
        coordinator.toggle(event(first.eventId))
        coordinator.toggle(event(second.eventId))
        coordinator.move(second.eventId, -1)
        assertEquals(listOf(second.eventId, first.eventId), coordinator.state.value.posts.map { it.event.id })
        val snapshot = coordinator.beginHandoff()!!
        coordinator.toggle(event(first.eventId))
        assertEquals(snapshot.posts, coordinator.state.value.posts)
        coordinator.failHandoff("storage failure")
        editable = false
        coordinator.cancel()
        assertTrue(coordinator.state.value.active)
        editable = true
        coordinator.toggle(event(first.eventId))
        assertEquals(listOf(second.eventId), coordinator.state.value.posts.map { it.event.id })
        coordinator.finishHandoff()
        assertFalse(coordinator.state.value.active)
    }
    @Test fun closingSelectionKeepsOrderAndArticleTargetUntilExplicitlyCleared() {
        val coordinator = MatometrSelectionCoordinator()
        fun event(id: String) = NostrEvent(id, "aa".repeat(32), 1, 1, emptyList(), "post", "")
        coordinator.start("existing-draft", 3)
        coordinator.toggle(event(first.eventId), first.relayHints)
        coordinator.toggle(event(second.eventId))
        coordinator.move(second.eventId, -1)
        val selected = coordinator.state.value

        coordinator.hide()
        assertFalse(coordinator.state.value.active)
        assertEquals(selected.posts, coordinator.state.value.posts)
        assertNull(coordinator.beginHandoff())
        coordinator.start()
        assertEquals(selected, coordinator.state.value)

        // Adding a post from its menu also reopens the same retained selection.
        coordinator.hide()
        val third = event("33".repeat(32))
        coordinator.toggle(third)
        assertEquals(listOf(second.eventId, first.eventId, third.id), coordinator.state.value.posts.map { it.event.id })
        assertEquals("existing-draft", coordinator.state.value.targetDraftId)
        assertEquals(3L, coordinator.state.value.targetRevision)
        assertEquals(selected.handoffId, coordinator.state.value.handoffId)

        coordinator.beginHandoff()
        coordinator.hide(); coordinator.clear()
        assertTrue(coordinator.state.value.active)
        assertEquals(3, coordinator.state.value.posts.size)
        coordinator.failHandoff("再試行してください")
        coordinator.clear()
        assertTrue(coordinator.state.value.active)
        assertTrue(coordinator.state.value.posts.isEmpty())
        assertNull(coordinator.state.value.error)
        coordinator.hide(); coordinator.start()
        assertTrue(coordinator.state.value.posts.isEmpty())
    }
}
