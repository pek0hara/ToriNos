package com.nostr.torinos.ui.article

import com.nostr.torinos.article.ArticleDraft
import com.nostr.torinos.article.ArticleDraftMode
import com.nostr.torinos.article.MatometrReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleTagEditorTest {
    @Test
    fun deletingMatometrKeepsReferencesAndDoesNotRestoreTheAutomaticTag() {
        val draft = ArticleDraft(ownerPubkey = "owner", mode = ArticleDraftMode.MatometrStructured,
            bodyMarkdown = "導入に #matometr を記載", references = listOf(MatometrReference("11".repeat(32))))
        val removed = updateArticleDraftTopics(draft, "nostr")
        assertFalse(removed.hasMatometrTag)
        assertTrue(removed.isMatometr)
        assertEquals(draft.references, removed.references)
        assertTrue(removed.content.startsWith("導入に #matometr を記載"))
        assertFalse(removed.content.endsWith("#matometr"))
        val edited = updateArticleDraftTopics(removed, "nostr 日記")
        assertFalse(edited.hasMatometrTag)
        assertEquals(listOf("nostr", "日記"), articlePublicationTopics(edited.topicsInput, edited.hasMatometrTag))
        assertTrue(updateArticleDraftTopics(edited, "nostr 日記 matometr").hasMatometrTag)
        assertTrue(ArticleEditorState(draft = removed, editingArticle = EditingArticle("owner", "id", null),
            topicsInput = "nostr", initialTopicsInput = "nostr").hasUnsavedChanges)
    }

    @Test
    fun deletingMatometrMetadataPreservesWrittenHashtagsInFreeMarkdown() {
        val draft = ArticleDraft(ownerPubkey = "owner", mode = ArticleDraftMode.MatometrMarkdown,
            bodyMarkdown = "導入に #matometr を記載\n\n${MatometrReference("11".repeat(32)).uri}\n\n#matometr")
        val removed = updateArticleDraftTopics(draft, "")
        assertFalse(removed.hasMatometrTag)
        assertTrue(removed.content.startsWith("導入に #matometr を記載"))
        assertEquals(draft.bodyMarkdown, removed.content)
        assertEquals(draft.bodyMarkdown, updateArticleDraftTopics(removed, "matometr").content)
    }

    @Test
    fun pendingTagRemainsPersistedButIsNotShownAsAChipUntilConfirmed() {
        val persisted = "nostr 日本語"
        assertEquals("nostr", articleCommittedTopicsInput(persisted, "日本語"))
        assertEquals(listOf("nostr", "日本語"), articlePublicationTopics(persisted, false))
        assertEquals("", articleCommittedTopicsInput("日本語", "日本語"))
        assertEquals("nostr", articleCommittedTopicsInput("nostr", "str"))
        assertEquals(persisted, articleCommittedTopicsInput(persisted, ""))
    }

    @Test
    fun automaticMatometrTagMatchesPublishedTagsAndIsNotDuplicated() {
        val topics = articlePublicationTopics("#matometr nostr matometr", true)
        assertEquals(listOf("matometr", "nostr"), topics)
        val eventTopics = buildArticleTags("id", "title", "", "", "#matometr nostr matometr", 123, true)
            .filter { it.first() == "t" }.map { it[1] }
        assertEquals(eventTopics, topics)
        assertEquals(listOf("matometr"), articlePublicationTopics("", true))
        assertEquals(emptyList(), articlePublicationTopics("", false))
    }

    @Test
    fun automaticTagReservesOneOfTheTwentyPublishedTags() {
        val input = (1..20).joinToString(" ") { "tag$it" }
        assertEquals(listOf("matometr") + (1..19).map { "tag$it" }, articlePublicationTopics(input, true))
        assertEquals(20, articlePublicationTopics(input, false).size)
        assertEquals(21, parseArticleTopics("$input extra", ARTICLE_TOPICS_MAX_COUNT + 1).size)
    }
}
