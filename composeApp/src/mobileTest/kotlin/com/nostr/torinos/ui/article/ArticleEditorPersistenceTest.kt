package com.nostr.torinos.ui.article

import androidx.lifecycle.viewModelScope
import com.nostr.torinos.account.*
import com.nostr.torinos.article.*
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.toArticleMeta
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleEditorPersistenceTest {
    private fun session() = AccountSession("test", "aa".repeat(32), object : AccountSigner {
        override val pubkey = "aa".repeat(32)
        override fun encryptToSelf(plaintext: String) = error("unused")
        override fun decrypt(content: String, peerPubkey: String) = error("unused")
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?): NostrEvent = error("must not publish")
    }, AccountSessionResources())
    @Test fun savesAfterDebounceAndClearedNewDraftDoesNotRemainInList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val vm = ArticleEditorViewModel(session, store)
        try {
            runCurrent()
            vm.onTitleChange("途中の記事")
            advanceTimeBy(9_999); runCurrent()
            assertTrue(store.read(session.pubkey).drafts.isEmpty())
            advanceTimeBy(1); runCurrent()
            assertEquals("途中の記事", store.read(session.pubkey).drafts.values.single().title)
            assertEquals(ArticleSaveState.Saved, vm.state.value.saveState)
            vm.onTitleChange("")
            advanceTimeBy(10_000); runCurrent()
            assertTrue(store.read(session.pubkey).drafts.isEmpty())
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun pastedPostsAreAddedOnlyOnceFetchedAndSelectable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val note = NostrEvent("11".repeat(32), "cc".repeat(32), 1, 1, emptyList(), "まとめたい投稿", "")
        val added = NostrEvent("22".repeat(32), "cc".repeat(32), 1, 1, emptyList(), "追加済み", "")
        val reaction = NostrEvent("33".repeat(32), "cc".repeat(32), 1, 7, emptyList(), "+", "")
        listOf(note, added, reaction).forEach { ArticleMemoryCache.putEvent(null, it) }
        val saved = store.save(ArticleDraft(ownerPubkey = session.pubkey, mode = ArticleDraftMode.MatometrStructured,
            references = listOf(MatometrReference(added.id))), null)
        val vm = ArticleEditorViewModel(session, store, saved.draftId)
        try {
            runCurrent()
            vm.openAddSheet()
            vm.onPasteInput(listOf(note, added, reaction).joinToString("\n") { "https://njump.me/" + com.nostr.torinos.model.encodeNevent(it.id) })
            advanceTimeBy(299); runCurrent()
            assertTrue(vm.state.value.addSheet!!.posts.isEmpty())
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf(PastedPostStatus.Ready, PastedPostStatus.AlreadyAdded, PastedPostStatus.Unsupported),
                vm.state.value.addSheet!!.posts.map { it.status })
            // The fetched author completes the reference written into the article.
            assertEquals(note.pubkey, vm.state.value.addSheet!!.ready.single().reference.authorPubkey)
            vm.commitAdd()
            assertNull(vm.state.value.addSheet)
            assertEquals(listOf(added.id, note.id), vm.state.value.draft!!.references.map { it.eventId })
            assertEquals(ArticleSaveState.Unsaved, vm.state.value.saveState)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun discardedNewInputIsNotSavedWhenTheScreenCloses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val vm = ArticleEditorViewModel(session, store)
        try {
            runCurrent()
            vm.onTitleChange("破棄する記事")
            var closed = false
            vm.discard { closed = true }; runCurrent()
            assertTrue(closed)
            // Closing the screen triggers ON_STOP, which calls saveNow.
            vm.saveNow(); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertTrue(store.read(session.pubkey).drafts.isEmpty())
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun editUndoneBeforeSavingIsNotSentAgain() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val vm = ArticleEditorViewModel(session, store)
        try {
            runCurrent()
            vm.onTitleChange("記事")
            advanceTimeBy(10_000); runCurrent()
            val saved = store.read(session.pubkey).drafts.values.single()
            vm.onTitleChange("記事を")
            vm.onTitleChange("記事")
            assertEquals(ArticleSaveState.Unsaved, vm.state.value.saveState)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ArticleSaveState.Saved, vm.state.value.saveState)
            assertEquals(saved.revision, store.read(session.pubkey).drafts.values.single().revision)
            vm.onTitleChange("記事2")
            vm.saveNow(); runCurrent()
            assertEquals("記事2", store.read(session.pubkey).drafts.values.single().title)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun storageFailureRetainsInputAndStopsLeaveAndAccountTransition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val storage = MemoryArticleStorage()
        val vm = ArticleEditorViewModel(session, ArticleDraftStore(storage))
        try {
            runCurrent(); storage.failWrite = true
            vm.onContentChange("消してはいけない本文")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ArticleSaveState.Failed, vm.state.value.saveState)
            var left = false
            vm.leave { left = true }; runCurrent()
            assertFalse(left)
            assertFailsWith<IllegalStateException> { session.prepareTransition() }
            assertFalse(session.inputFrozen.value)
            assertEquals("消してはいけない本文", vm.state.value.content)
            storage.failWrite = false
            vm.leave { left = true }; runCurrent()
            assertTrue(left)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun oldSaveCompletionDoesNotMarkNewInputAsSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val memory = MemoryArticleStorage()
        val unblock = CompletableDeferred<Unit>()
        var hold = true
        val storage = object : ArticleStorage {
            override suspend fun read(owner: String) = memory.read(owner)
            override suspend fun compareAndSet(owner: String, expected: String?, value: String): Boolean {
                if (hold) unblock.await()
                return memory.compareAndSet(owner, expected, value)
            }
        }
        val store = ArticleDraftStore(storage)
        val vm = ArticleEditorViewModel(session, store)
        try {
            runCurrent(); vm.onTitleChange("最初")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ArticleSaveState.Saving, vm.state.value.saveState)
            vm.onContentChange("保存中の追加入力")
            hold = false; unblock.complete(Unit); runCurrent()
            // Typing while a save is sending must not cancel it.
            assertEquals("最初", store.read(session.pubkey).drafts.values.single().title)
            assertEquals(ArticleSaveState.Unsaved, vm.state.value.saveState)
            advanceTimeBy(10_000); runCurrent()
            assertEquals("保存中の追加入力", store.read(session.pubkey).drafts.values.single().bodyMarkdown)
            assertEquals(ArticleSaveState.Saved, vm.state.value.saveState)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun clearingSavedInputReportsDeletionFailureAndCanRetryBeforeLeaving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        var failDelete = true
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = emptyList<ArticleDraft>()
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) {
                check(!failDelete) { "リレーへの削除に失敗しました" }
            }
        }
        val store = ArticleDraftStore(MemoryArticleStorage(), transport)
        val vm = ArticleEditorViewModel(session, store)
        try {
            runCurrent(); vm.onTitleChange("保存済み")
            advanceTimeBy(10_000); runCurrent()
            vm.onTitleChange("")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ArticleSaveState.Failed, vm.state.value.saveState)
            assertEquals("リレーへの削除に失敗しました", vm.state.value.error)
            assertEquals("保存済み", store.read(session.pubkey).drafts.values.single().title)
            var left = false
            vm.leave { left = true }; runCurrent()
            assertFalse(left)
            failDelete = false
            vm.leave { left = true }; runCurrent()
            assertTrue(left)
            assertEquals(ArticleSaveState.Saved, vm.state.value.saveState)
            assertNull(vm.state.value.error)
            assertTrue(store.read(session.pubkey).drafts.isEmpty())
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun deletingEmptyDraftDoesNotOverwriteInputTypedDuringDeletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val unblock = CompletableDeferred<Unit>()
        val transport = object : ArticleDraftTransport {
            override suspend fun load() = emptyList<ArticleDraft>()
            override suspend fun save(draft: ArticleDraft) = Unit
            override suspend fun delete(draft: ArticleDraft) { unblock.await() }
        }
        val store = ArticleDraftStore(MemoryArticleStorage(), transport)
        val vm = ArticleEditorViewModel(session, store)
        try {
            runCurrent(); vm.onTitleChange("保存済み")
            advanceTimeBy(10_000); runCurrent()
            vm.onTitleChange("")
            advanceTimeBy(10_000); runCurrent()
            assertEquals(ArticleSaveState.Saving, vm.state.value.saveState)
            vm.onContentChange("削除中に入力した本文")
            unblock.complete(Unit); runCurrent()
            assertEquals("削除中に入力した本文", vm.state.value.content)
            assertEquals(ArticleSaveState.Unsaved, vm.state.value.saveState)
            advanceTimeBy(10_000); runCurrent()
            assertEquals("削除中に入力した本文", store.read(session.pubkey).drafts.values.single().bodyMarkdown)
            assertEquals(ArticleSaveState.Saved, vm.state.value.saveState)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    private fun publishedArticle(pubkey: String): com.nostr.torinos.model.ArticleItem {
        val event = NostrEvent("ef".repeat(32), pubkey, 200, 30023, listOf(listOf("d", "published"), listOf("title", "公開済みの記事"),
            listOf("published_at", "150")), "公開版の本文", "sig")
        return com.nostr.torinos.model.ArticleItem(event, assertNotNull(event.toArticleMeta()))
    }
    @Test fun openingAPublishedArticleWithoutEditingCreatesNoDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val article = publishedArticle(session.pubkey)
        val vm = ArticleEditorViewModel(session, store, editPubkey = session.pubkey, editIdentifier = "published",
            fetchLatest = { _, _, _ -> article })
        try {
            runCurrent()
            assertTrue(vm.state.value.isEditing)
            assertFalse(vm.state.value.updateConflict)
            // Leaving and ON_STOP both flush; neither may store the untouched article as a draft.
            vm.saveNow(); runCurrent()
            var left = false
            vm.leave { left = true }; runCurrent()
            assertTrue(left)
            advanceTimeBy(60_000); runCurrent()
            assertTrue(store.read(session.pubkey).drafts.isEmpty())
            vm.onTitleChange("直した題")
            vm.saveNow(); runCurrent()
            val saved = store.read(session.pubkey).drafts.values.single()
            assertEquals("直した題", saved.title)
            assertEquals(article.event.id, saved.baseEventId)
            assertEquals("published", saved.articleIdentifier)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun editingAnArticleWithAnExistingDraftReopensThatDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val article = publishedArticle(session.pubkey)
        val existing = store.save(ArticleDraft(ownerPubkey = session.pubkey, articleIdentifier = "published", baseEventId = article.event.id,
            originalPublishedAt = 150, title = "書きかけの更新", bodyMarkdown = "途中"), null)
        val vm = ArticleEditorViewModel(session, store, editPubkey = session.pubkey, editIdentifier = "published",
            fetchLatest = { _, _, _ -> article })
        try {
            runCurrent()
            assertEquals(existing.draftId, vm.state.value.draft!!.draftId)
            assertEquals("書きかけの更新", vm.state.value.title)
            assertTrue(vm.state.value.isEditing)
            assertFalse(vm.state.value.updateConflict)
            vm.onContentChange("途中の続き")
            vm.saveNow(); runCurrent()
            val saved = store.read(session.pubkey).drafts.values.single()
            assertEquals(existing.draftId, saved.draftId)
            assertEquals(existing.revision + 1, saved.revision)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun draftOfAPublishedArticleWithoutItsBaseOpensAsAnUpdateNeedingConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val article = publishedArticle(session.pubkey)
        // A draft saved by another client keeps the article address and published_at but not the version it started from.
        val foreign = store.save(ArticleDraft(ownerPubkey = session.pubkey, articleIdentifier = "published",
            originalPublishedAt = 150, title = "他クライアントの下書き", bodyMarkdown = "本文"), null)
        val vm = ArticleEditorViewModel(session, store, foreign.draftId, fetchLatest = { _, identifier, _ ->
            article.takeIf { identifier == "published" } })
        try {
            runCurrent()
            assertTrue(vm.state.value.isEditing)
            assertTrue(vm.state.value.updateConflict)
            vm.confirmOverwrite(); runCurrent()
            assertFalse(vm.state.value.updateConflict)
            assertEquals(article.event.id, vm.state.value.draft!!.baseEventId)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
    @Test fun unpublishedDraftDoesNotLookUpAPublishedVersion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = session()
        val store = ArticleDraftStore(MemoryArticleStorage())
        val draft = store.save(ArticleDraft(ownerPubkey = session.pubkey, title = "新しい記事", bodyMarkdown = "本文"), null)
        var lookups = 0
        val vm = ArticleEditorViewModel(session, store, draft.draftId, fetchLatest = { _, _, _ -> lookups++; null })
        try {
            runCurrent()
            assertFalse(vm.state.value.isEditing)
            assertEquals(0, lookups)
        } finally { vm.detachInputGate(); vm.viewModelScope.cancel(); session.resources.close(); Dispatchers.resetMain() }
    }
}
