package com.nostr.torinos.article

import kotlin.test.*
import kotlinx.coroutines.async
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
    @Test fun progressShowsRelayDraftsWithoutDroppingKnownOnesUntilTheLoadCompletes() = runTest {
        val known = ArticleDraft(ownerPubkey = "a", title = "前回の一覧", revision = 1)
        val fromRelay = ArticleDraft(ownerPubkey = "a", title = "届いた下書き", revision = 1)
        var results = listOf(known)
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = results
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) = Unit
            override suspend fun loadProgressively(onProgress: suspend (List<ArticleDraft>) -> Unit): List<ArticleDraft> {
                if (results != listOf(known)) onProgress(listOf(fromRelay))
                return results
            }
        }
        val store = ArticleDraftStore(transport = transport)
        store.refreshDrafts("a")
        assertEquals(listOf("前回の一覧"), store.read("a").drafts.values.map { it.title })
        results = listOf(fromRelay)
        val progress = mutableListOf<Set<String>>()
        store.refreshDrafts("a") { data -> progress += data.drafts.values.map { it.title }.toSet() }
        assertEquals(listOf(setOf("前回の一覧", "届いた下書き")), progress)
        assertEquals(listOf("届いた下書き"), store.read("a").drafts.values.map { it.title })
    }
    @Test fun slowRelayLoadDoesNotBlockOpeningOrSavingAndDoesNotRevertTheSave() = runTest {
        val loaded = ArticleDraft(ownerPubkey = "a", title = "リレーの版", revision = 1)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : ArticleDraftTransport {
            var first = true
            override suspend fun load(): List<ArticleDraft> {
                if (first) { first = false; return listOf(loaded) }
                release.await(); return listOf(loaded)
            }
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) = Unit
        }
        val store = ArticleDraftStore(transport = transport)
        store.refreshDrafts("a")
        val refresh = async { store.refreshDrafts("a") }
        testScheduler.runCurrent()
        assertFalse(refresh.isCompleted)
        assertEquals(loaded, store.loadDraft("a", loaded.draftId))
        val edited = store.save(loaded.copy(title = "取得中に保存"), loaded.revision)
        val created = store.save(ArticleDraft(ownerPubkey = "a", title = "取得中に作成"), null)
        release.complete(Unit)
        refresh.await()
        assertEquals(edited, store.read("a").drafts[loaded.draftId])
        assertEquals(created, store.read("a").drafts[created.draftId])
    }
}
