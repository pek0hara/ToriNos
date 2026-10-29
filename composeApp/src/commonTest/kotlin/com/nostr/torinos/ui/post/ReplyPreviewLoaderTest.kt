package com.nostr.torinos.ui.post

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.network.RelayTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyPreviewLoaderTest {
    private val parentId = "a".repeat(64)
    private val parent = ReplyEventReference(id = parentId, kind = 1, pubkey = "b".repeat(64))

    /** 問い合わせ先ごとに返す投稿を決める。問い合わせた先を順に記録する。 */
    private class FakeFetch(private val byTarget: Map<RelayTarget, List<NostrEvent>>) {
        val queried = mutableListOf<RelayTarget>()
        suspend fun fetch(ids: Set<String>, target: RelayTarget, onEvent: (NostrEvent) -> Unit) {
            queried += target
            byTarget[target].orEmpty().forEach(onEvent)
        }
    }

    private fun note(id: String = parentId, content: String = "返信先の本文") = NostrEvent(
        id = id,
        pubkey = "b".repeat(64),
        createdAt = 1,
        kind = 1,
        tags = emptyList(),
        content = content,
        sig = "signature",
    )

    @Test
    fun returnsContentFoundOnEnabledRelays() = runTest {
        val fake = FakeFetch(mapOf(RelayTarget.AllEnabled to listOf(note())))
        assertEquals(ReplyPreview.Loaded("返信先の本文"), loadReplyPreview(parent, fake::fetch))
        assertEquals(listOf<RelayTarget>(RelayTarget.AllEnabled), fake.queried)
    }

    @Test
    fun blankContentFallsBackToShortId() = runTest {
        val fake = FakeFetch(mapOf(RelayTarget.AllEnabled to listOf(note(content = " "))))
        assertEquals(ReplyPreview.Loaded("投稿 aaaaaaaa"), loadReplyPreview(parent, fake::fetch))
    }

    @Test
    fun asksHintedRelayWhenEnabledRelaysDoNotHaveIt() = runTest {
        val hint = "wss://hint.example"
        val fake = FakeFetch(mapOf(RelayTarget.Explicit(setOf(hint)) to listOf(note())))
        val result = loadReplyPreview(parent.copy(relayUrl = hint), fake::fetch)
        assertEquals(ReplyPreview.Loaded("返信先の本文"), result)
        assertEquals(listOf(RelayTarget.AllEnabled, RelayTarget.Explicit(setOf(hint))), fake.queried)
    }

    @Test
    fun unavailableWhenNotFoundAnywhere() = runTest {
        val fake = FakeFetch(emptyMap())
        assertEquals(ReplyPreview.Unavailable, loadReplyPreview(parent.copy(relayUrl = "wss://hint.example"), fake::fetch))
        assertEquals(2, fake.queried.size)
    }

    @Test
    fun doesNotAskHintWithoutUsableRelay() = runTest {
        val noHint = FakeFetch(emptyMap())
        assertEquals(ReplyPreview.Unavailable, loadReplyPreview(parent, noHint::fetch))
        assertEquals(listOf<RelayTarget>(RelayTarget.AllEnabled), noHint.queried)

        val badHint = FakeFetch(emptyMap())
        assertEquals(ReplyPreview.Unavailable, loadReplyPreview(parent.copy(relayUrl = "https://not-a-relay"), badHint::fetch))
        assertEquals(listOf<RelayTarget>(RelayTarget.AllEnabled), badHint.queried)
    }

    @Test
    fun invalidIdIsNotQueried() = runTest {
        val fake = FakeFetch(emptyMap())
        assertEquals(ReplyPreview.Unavailable, loadReplyPreview(parent.copy(id = "short"), fake::fetch))
        assertEquals(emptyList(), fake.queried)
    }

    @Test
    fun ignoresEventWithDifferentId() = runTest {
        val fake = FakeFetch(mapOf(RelayTarget.AllEnabled to listOf(note(id = "c".repeat(64)))))
        assertEquals(ReplyPreview.Unavailable, loadReplyPreview(parent, fake::fetch))
    }

    // --- 返信先の欄に何を出すか ---

    private val target = ReplyTarget.Timeline(root = parent, parent = parent)

    private fun draft(replyToId: String?) = PostMemoData(
        text = "下書き",
        imageUrls = emptyList(),
        replyToId = replyToId,
        replyToPubkey = replyToId?.let { "b".repeat(64) },
        noteKind = 1,
        channelId = null,
        updatedAt = 1,
    )

    @Test
    fun usesGivenPreviewWhenNoDraftIsSelected() {
        assertEquals(
            ReplyPreviewSource.Given("呼び出し元の本文"),
            replyPreviewSource(selectedDraft = null, replyToPreview = "呼び出し元の本文", replyTarget = target),
        )
    }

    @Test
    fun selectedReplyDraftFetchesItsTargetInsteadOfGivenPreview() {
        assertEquals(
            ReplyPreviewSource.Fetch(parent),
            replyPreviewSource(selectedDraft = draft(parentId), replyToPreview = "別の投稿の本文", replyTarget = target),
        )
    }

    @Test
    fun selectedNonReplyDraftShowsNoReplyTarget() {
        assertEquals(
            ReplyPreviewSource.None,
            replyPreviewSource(selectedDraft = draft(null), replyToPreview = "別の投稿の本文", replyTarget = null),
        )
    }

    @Test
    fun restoredDraftWithoutGivenPreviewFetchesTarget() {
        assertEquals(
            ReplyPreviewSource.Fetch(parent),
            replyPreviewSource(selectedDraft = null, replyToPreview = null, replyTarget = target),
        )
    }
}
