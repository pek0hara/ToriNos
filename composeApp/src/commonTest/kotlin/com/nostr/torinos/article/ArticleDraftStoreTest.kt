package com.nostr.torinos.article

import kotlin.test.*
import kotlinx.coroutines.test.runTest

internal class MemoryArticleStorage : ArticleStorage {
    val rows = mutableMapOf<String, String>()
    var failWrite = false
    var failRead = false
    override suspend fun read(owner: String): String? { check(!failRead) { "read failure" }; return rows[owner] }
    override suspend fun compareAndSet(owner: String, expected: String?, value: String): Boolean {
        check(!failWrite) { "memory failure" }
        if (rows[owner] != expected) return false
        rows[owner] = value
        return true
    }
}
class ArticleDraftStoreTest {
    @Test fun preservesOwnerAndRejectsStaleRevisionAcrossStoreInstances() = runTest {
        val storage = MemoryArticleStorage()
        val a = ArticleDraftStore(storage)
        val otherTab = ArticleDraftStore(storage)
        val draft = a.save(ArticleDraft(ownerPubkey = "a", title = "一版"), null)
        val updated = otherTab.save(draft.copy(bodyMarkdown = "別タブの編集"), draft.revision)
        assertFailsWith<ArticleStoreConflict> { a.save(draft.copy(title = "古い編集"), draft.revision) }
        assertEquals(updated, a.read("a").drafts[draft.draftId])
        assertTrue(a.read("b").drafts.isEmpty())
        assertEquals(draft.articleIdentifier, updated.articleIdentifier)
    }
    @Test fun memoryFailureAndReadFailureDoNotBecomeSuccessfulEmptyResults() = runTest {
        val storage = MemoryArticleStorage()
        val store = ArticleDraftStore(storage)
        val draft = store.save(ArticleDraft(ownerPubkey = "a", title = "旧版"), null)
        storage.failWrite = true
        assertFailsWith<IllegalStateException> { store.save(draft.copy(title = "新版"), draft.revision) }
        storage.failWrite = false
        assertEquals("旧版", store.read("a").drafts[draft.draftId]?.title)
        storage.failRead = true
        assertFailsWith<IllegalStateException> { store.read("a") }
    }
    @Test fun malformedDraftIsRetainedAndOtherDraftsRemainReadable() = runTest {
        val storage = MemoryArticleStorage()
        val store = ArticleDraftStore(storage)
        val draft = store.save(ArticleDraft(ownerPubkey = "a", title = "正常"), null)
        val raw = storage.rows.getValue("a")
        storage.rows["a"] = raw.replace("\"drafts\":{", "\"drafts\":{\"broken\":{\"schemaVersion\":99},")
        val data = store.read("a")
        assertEquals(draft, data.drafts[draft.draftId])
        assertTrue("drafts:broken" in data.corruptRecords)
        store.save(draft.copy(title = "更新"), draft.revision)
        assertTrue("drafts:broken" in store.read("a").corruptRecords)
    }
    @Test fun relaySaveFailureDoesNotCommitTheDraftOrClearInput() = runTest {
        var fail = true
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = emptyList<ArticleDraft>()
            override suspend fun save(draft: ArticleDraft) { check(!fail) { "relay unavailable" } }
            override suspend fun delete(draft: ArticleDraft) { check(!fail) { "relay unavailable" } }
        }
        val store = ArticleDraftStore(transport = transport)
        val draft = ArticleDraft(ownerPubkey = "a", title = "入力を保持")
        assertFailsWith<IllegalStateException> { store.save(draft, null) }
        assertTrue(store.read("a").drafts.isEmpty())
        fail = false
        val saved = store.save(draft, null)
        fail = true
        assertFailsWith<IllegalStateException> { store.delete("a", saved.draftId, saved.revision) }
        assertEquals(saved, store.read("a").drafts[saved.draftId])
    }
    @Test fun newSessionRestoresDraftsOnlyFromTheRelay() = runTest {
        val remote = mutableMapOf<String, ArticleDraft>()
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = remote.values.toList()
            override suspend fun save(draft: ArticleDraft) { remote[draft.draftId] = draft }
            override suspend fun delete(draft: ArticleDraft) { remote.remove(draft.draftId) }
        }
        val first = ArticleDraftStore(transport = transport)
        val saved = first.save(ArticleDraft(ownerPubkey = "a", title = "リレーに保存"), null)
        first.transaction("a") { it.copy(composers = mapOf("current" to com.nostr.torinos.ui.post.PostMemoData(
            text = "一時入力", imageUrls = emptyList(), replyToId = null, replyToPubkey = null, noteKind = 1, channelId = null, updatedAt = 1))) }
        val restarted = ArticleDraftStore(transport = transport)
        assertTrue(restarted.read("a").drafts.isEmpty())
        assertEquals(saved, restarted.loadDraft("a", saved.draftId))
        assertTrue(restarted.read("a").composers.isEmpty())
        assertTrue(restarted.read("a").attempts.isEmpty())
    }
}
