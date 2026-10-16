package com.nostr.torinos.article

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable internal data class ArticleStoreData(
    val schemaVersion: Int = 1,
    val generation: Long = 0,
    val drafts: Map<String, ArticleDraft> = emptyMap(),
    val attempts: Map<String, ArticlePublishAttempt> = emptyMap(),
    val archives: Map<String, String> = emptyMap(),
    val identities: Map<String, ArticleIdentity> = emptyMap(),
    val publishLeases: Map<String, ArticlePublishLease> = emptyMap(),
    val composers: Map<String, com.nostr.torinos.ui.post.PostMemoData> = emptyMap(),
    val handoffs: Set<String> = emptySet(),
    val corruptRecords: Map<String, JsonElement> = emptyMap(),
    val relayDraftIssues: List<ArticleDraftLoadIssue> = emptyList(),
    val incompleteDraftRelays: List<String> = emptyList(),
)
internal class ArticleStoreConflict : IllegalStateException("他の画面で更新されています。保存済みの内容を開き直してください")
internal class ArticleStoreCorrupt : IllegalStateException("編集中のデータを読み込めません")
internal interface ArticleStorage {
    suspend fun read(owner: String): String?
    /** Session memory only; no disk, IndexedDB, or cross-tab persistence. */
    suspend fun compareAndSet(owner: String, expected: String?, value: String): Boolean
}
internal val articleJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal class SessionArticleMemory : ArticleStorage {
    private val rows = mutableMapOf<String, String>()
    override suspend fun read(owner: String) = rows[owner]
    override suspend fun compareAndSet(owner: String, expected: String?, value: String): Boolean {
        if (rows[owner] != expected) return false
        rows[owner] = value
        return true
    }
}
internal class ArticleDraftStore(
    private val storage: ArticleStorage = SessionArticleMemory(),
    private val transport: ArticleDraftTransport? = null,
) {
    private val mutex = Mutex()
    private fun decode(raw: String?, owner: String): ArticleStoreData {
        if (raw == null) return ArticleStoreData()
        val root = try { articleJson.parseToJsonElement(raw).jsonObject.toMutableMap() }
            catch (e: Exception) { throw ArticleStoreCorrupt() }
        if (root["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) throw ArticleStoreCorrupt()
        val corrupt = root["corruptRecords"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        fun filter(group: String, valid: (JsonElement) -> Boolean) {
            val records = root[group]?.jsonObject ?: return
            root[group] = JsonObject(records.filter { (id, value) ->
                val readable = runCatching { valid(value) }.getOrDefault(false)
                if (!readable) corrupt["$group:$id"] = value
                readable
            })
        }
        filter("drafts") { articleJson.decodeFromJsonElement<ArticleDraft>(it).let { d -> d.schemaVersion == 1 && d.ownerPubkey == owner && d.references.all { ref -> ref.hasValidIds() } } }
        filter("attempts") { articleJson.decodeFromJsonElement<ArticlePublishAttempt>(it).let { a -> a.draft.schemaVersion == 1 && a.draft.ownerPubkey == owner && a.event.pubkey == owner && a.draft.references.all { ref -> ref.hasValidIds() } } }
        filter("identities") { articleJson.decodeFromJsonElement<ArticleIdentity>(it); true }
        filter("archives") { it.jsonPrimitive.isString }
        filter("composers") { articleJson.decodeFromJsonElement<com.nostr.torinos.ui.post.PostMemoData>(it); true }
        root["corruptRecords"] = JsonObject(corrupt)
        return try { articleJson.decodeFromJsonElement<ArticleStoreData>(JsonObject(root)) }
            catch (e: Exception) { throw ArticleStoreCorrupt() }
    }
    suspend fun read(owner: String): ArticleStoreData = mutex.withLock { decode(storage.read(owner), owner) }
    /**
     * Reloads drafts from the relays. [onProgress] gets the list each time a relay finishes; until the load
     * completes, drafts already in memory stay listed. The lock is held only to apply results, so the
     * editor can open and save while a slow relay is still being waited for.
     */
    suspend fun refreshDrafts(owner: String, onProgress: suspend (ArticleStoreData) -> Unit = {}) {
        val relay = transport ?: return
        var applied = read(owner).drafts
        // A draft saved or deleted here during the load is newer than anything the load started from.
        fun localChanges(current: Map<String, ArticleDraft>) =
            (current.keys + applied.keys).filter { current[it] != applied[it] }.associateWith { current[it] }
        suspend fun apply(drafts: List<ArticleDraft>, final: Boolean): ArticleStoreData = mutex.withLock {
            check(drafts.all { it.ownerPubkey == owner })
            val raw = storage.read(owner)
            val current = decode(raw, owner)
            val loaded = (if (final) emptyMap() else current.drafts) + drafts.associateBy { it.draftId }
            val merged = localChanges(current.drafts).entries.fold(loaded) { acc, (id, draft) ->
                if (draft == null) acc - id else acc + (id to draft)
            }
            val refreshed = current.copy(drafts = merged, generation = current.generation + 1).let {
                if (final) it.copy(relayDraftIssues = relay.loadIssues, incompleteDraftRelays = relay.incompleteRelays) else it
            }
            if (!storage.compareAndSet(owner, raw, articleJson.encodeToString(refreshed))) throw ArticleStoreConflict()
            applied = merged
            refreshed
        }
        val drafts = relay.loadProgressively { partial -> onProgress(apply(partial, final = false)) }
        apply(drafts, final = true)
    }
    suspend fun loadPublishedIdentifiers(): Set<String> = transport?.loadPublishedIdentifiers().orEmpty()
    suspend fun loadDraft(owner: String, id: String): ArticleDraft? {
        read(owner).drafts[id]?.let { return it }
        refreshDrafts(owner)
        return read(owner).drafts[id]
    }
    suspend fun transaction(owner: String, transform: (ArticleStoreData) -> ArticleStoreData): ArticleStoreData = mutex.withLock {
        require(owner.isNotEmpty())
        val raw = storage.read(owner)
        val before = decode(raw, owner)
        val after = transform(before).copy(generation = before.generation + 1)
        check(after.drafts.values.all { it.ownerPubkey == owner })
        check(after.attempts.values.all { it.draft.ownerPubkey == owner && it.event.pubkey == owner })
        val encoded = articleJson.encodeToString(after)
        // Only article drafts leave the device, through their own encrypted relay records.
        // Publish attempts, editor archives, composer handoffs, and locks stay in session memory.
        transport?.let { relay ->
            after.drafts.forEach { (id, draft) -> if (before.drafts[id] != draft) relay.save(draft) }
            before.drafts.forEach { (id, draft) -> if (id !in after.drafts) relay.delete(draft) }
        }
        if (!storage.compareAndSet(owner, raw, encoded)) throw ArticleStoreConflict()
        after
    }
    suspend fun save(draft: ArticleDraft, expectedRevision: Long?): ArticleDraft {
        var saved = draft.copy(revision = (expectedRevision ?: 0) + 1)
        transaction(draft.ownerPubkey) { data ->
            if (data.drafts[draft.draftId]?.revision != expectedRevision) throw ArticleStoreConflict()
            saved = saved.copy(originalPublishedAt = draft.originalPublishedAt ?: data.identities[draft.articleIdentifier]?.publishedAt)
            data.copy(drafts = data.drafts + (draft.draftId to saved))
        }
        return saved
    }
    suspend fun delete(owner: String, id: String, expectedRevision: Long) {
        transaction(owner) { data ->
            if (data.drafts[id]?.revision != expectedRevision) throw ArticleStoreConflict()
            check(data.attempts.values.none { it.draft.draftId == id && !it.complete && it.supersededBy == null }) {
                "公開結果が未確定のため削除できません。受理状況を確認してください"
            }
            data.copy(drafts = data.drafts - id)
        }
    }
    /** Called once any relay accepted the article; failure here only retains the draft. */
    suspend fun cleanPublishedDraft(owner: String, attemptId: String): ArticlePublishAttempt {
        val data = transaction(owner) { current ->
            val attempt = current.attempts[attemptId] ?: error("公開履歴が見つかりません")
            check(attempt.accepted) { "記事を受理したリレーがありません" }
            // Compare the whole draft: once deleted, a later edit restarts at revision 1 under the same ID.
            current.copy(
                drafts = if (current.drafts[attempt.draft.draftId] == attempt.draft)
                    current.drafts - attempt.draft.draftId else current.drafts,
                attempts = current.attempts + (attemptId to attempt.copy(draftCleanupError = null)),
            )
        }
        return data.attempts.getValue(attemptId)
    }
}
