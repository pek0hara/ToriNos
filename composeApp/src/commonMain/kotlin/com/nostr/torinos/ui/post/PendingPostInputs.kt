package com.nostr.torinos.ui.post

import com.nostr.torinos.article.ArticleDraftStore
import com.nostr.torinos.article.articleLocalId

internal data class PendingPostInput(val id: String, val memo: PostMemoData)

/** Only parked inputs are offered; the active composer and handoff recovery are excluded. */
internal class PendingPostInputs(private val store: ArticleDraftStore, private val owner: String) {
    suspend fun list(activeId: String?): List<PendingPostInput> = store.read(owner).composers
        .filterKeys { it.startsWith("pending-") && it != activeId }
        .map { PendingPostInput(it.key, it.value) }
        .sortedWith(compareByDescending<PendingPostInput> { it.memo.updatedAt }.thenBy { it.id })

    suspend fun apply(input: PendingPostInput, current: PostMemoData?, currentId: String?, restore: (PostMemoData) -> Unit) {
        // Preserve the outgoing input before touching the editor. Keep the source until restoration succeeds.
        store.transaction(owner) { data ->
            check(data.composers[input.id] == input.memo) { "保留入力が更新されています。投稿を開き直してください" }
            val alreadyParked = current != null && data.composers.any { (id, memo) ->
                id.startsWith("pending-") && memo.copy(updatedAt = 0) == current.copy(updatedAt = 0)
            }
            data.copy(composers = if (current == null || currentId == input.id || alreadyParked) data.composers
                else data.composers + ("pending-${articleLocalId()}" to current))
        }
        restore(input.memo)
        store.transaction(owner) { data ->
            check(data.composers[input.id] == input.memo) { "保留入力が更新されています。投稿を開き直してください" }
            data.copy(composers = (data.composers - input.id - (currentId ?: "current")) + ("current" to input.memo),
                handoffs = data.handoffs - (currentId ?: "current"))
        }
    }

    suspend fun discard(input: PendingPostInput) {
        store.transaction(owner) { data ->
            check(data.composers[input.id] == input.memo) { "保留入力が更新されています。投稿を開き直してください" }
            data.copy(composers = data.composers - input.id)
        }
    }
}
