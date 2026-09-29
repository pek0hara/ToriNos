package com.nostr.torinos.ui.post

import com.nostr.torinos.model.MediaMetadata
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.network.RelayPublishResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class PostViewModelTest {
    @Test
    fun failedImageOnlyDraftCannotSilentlyBecomeAnEmptyFirstPost() {
        val failed = PostState(images = listOf(ImageAttachment(1, null, null, false)))
        assertTrue(failed.hasFailedUpload)
        assertFalse(failed.canPost)
        assertFalse(failed.isUploadingAny)
        assertFalse(PostState().hasFailedUpload)
    }

    @Test
    fun draftContentRequiresNonBlankTextOrUploadedImage() {
        assertFalse(PostState().hasDraftContent)
        assertFalse(PostState(text = " \n ").hasDraftContent)
        assertFalse(
            PostState(
                images = listOf(
                    ImageAttachment(
                        id = 1,
                        previewBytes = null,
                        uploadedUrl = null,
                        isUploading = true,
                    ),
                ),
            ).hasDraftContent,
        )
        assertTrue(PostState(text = "draft").hasDraftContent)
        assertTrue(
            PostState(
                images = listOf(
                    ImageAttachment(
                        id = 1,
                        previewBytes = null,
                        uploadedUrl = "https://media.example/image.jpg",
                        isUploading = false,
                    ),
                ),
            ).hasDraftContent,
        )
    }

    @Test
    fun resetClearsPreviousComposerContent() {
        val viewModel = PostViewModel()
        viewModel.onTextChange("編集中の内容")
        viewModel.onContentWarningChange(true)

        viewModel.reset()

        assertEquals(PostState(), viewModel.state.value)
    }

    @Test
    fun postOverTheCharacterLimitIsRejectedBeforeSending() {
        val viewModel = PostViewModel()
        viewModel.onTextChange("あ".repeat(MAX_POST_CHARS + 1))

        viewModel.post()

        val state = viewModel.state.value
        assertEquals("本文は${MAX_POST_CHARS}文字以内にしてください", state.error)
        assertFalse(state.isPosting)
        assertEquals(MAX_POST_CHARS + 1, state.text.length)
    }

    @Test
    fun postAtTheCharacterLimitPassesTheLengthCheck() {
        val viewModel = PostViewModel()
        viewModel.onTextChange("あ".repeat(MAX_POST_CHARS))
        // 送信先が空なので、文字数の検査を通った後のリレー検査で止まる（ネットワークへは出ない）。
        viewModel.post(relayUrls = emptyList())

        assertEquals("送信先リレーを1つ以上選択してください", viewModel.state.value.error)
    }

    @Test
    fun postWithNoTargetRelayKeepsTheTextAndShowsTheError() {
        val viewModel = PostViewModel()
        viewModel.onTextChange("本文")

        viewModel.post(relayUrls = listOf(" ", ""))

        val state = viewModel.state.value
        assertEquals("送信先リレーを1つ以上選択してください", state.error)
        assertEquals("本文", state.text)
        assertFalse(state.isPosting)
    }

    @Test
    fun showErrorIsClearedByTheNextEdit() {
        val viewModel = PostViewModel()
        viewModel.onTextChange("本文")

        viewModel.showError("書き込み可能なリレーがありません")
        assertEquals("書き込み可能なリレーがありません", viewModel.state.value.error)

        viewModel.onTextChange("本文2")
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun consumingWithoutACompletionReturnsNull() {
        val viewModel = PostViewModel()

        assertNull(viewModel.consumeCompletion())
        assertNull(viewModel.consumeStaleFailure())
    }

    @Test
    fun successOfTheCurrentDraftClearsTheInputAndKeepsOnlyTheCompletion() {
        val typing = PostState(text = "送信した本文", isPosting = true)

        val next = reducePostSuccess(typing, isCurrent = true, completion = completion(fromCurrentDraft = true))

        assertEquals(PostState(completion = completion(fromCurrentDraft = true)), next)
    }

    @Test
    fun successAfterTheDraftWasResetDoesNotOverwriteWhatIsBeingTypedNow() {
        // 送信中に簡易欄を閉じて別の入力を始めた状態。
        val typingNow = PostState(text = "いま入力中の本文", error = "別のエラー")

        val next = reducePostSuccess(typingNow, isCurrent = false, completion = completion(fromCurrentDraft = false))

        assertEquals("いま入力中の本文", next.text)
        assertEquals("別のエラー", next.error)
        assertEquals(completion(fromCurrentDraft = false), next.completion)
    }

    @Test
    fun successOfTheCurrentDraftKeepsAnUnconsumedStaleFailure() {
        // 以前に破棄した入力の送信失敗が、まだホストに消費されていない状態。
        val posting = PostState(text = "送信した本文", isPosting = true, staleFailure = "古い送信の失敗")

        val next = reducePostSuccess(posting, isCurrent = true, completion = completion(fromCurrentDraft = true))

        assertEquals("", next.text)
        assertEquals("古い送信の失敗", next.staleFailure)
        assertEquals(completion(fromCurrentDraft = true), next.completion)
    }

    @Test
    fun failureOfTheCurrentDraftKeepsTheTextAndShowsTheError() {
        val posting = PostState(text = "送信した本文", isPosting = true)

        val next = reducePostFailure(posting, isCurrent = true, message = "ポストに失敗しました")

        assertEquals("送信した本文", next.text)
        assertEquals("ポストに失敗しました", next.error)
        assertFalse(next.isPosting)
        assertNull(next.staleFailure)
    }

    @Test
    fun failureAfterTheDraftWasResetIsReportedSeparatelyFromTheCurrentInput() {
        val typingNow = PostState(text = "いま入力中の本文")

        val next = reducePostFailure(typingNow, isCurrent = false, message = "ポストに失敗しました")

        assertEquals("いま入力中の本文", next.text)
        assertNull(next.error)
        assertEquals("ポストに失敗しました", next.staleFailure)
    }

    private fun completion(fromCurrentDraft: Boolean) = PostCompletion(
        eventId = "event",
        replyToId = "parent",
        noteContext = NoteContext.Timeline,
        publishResult = RelayPublishResult(setOf("wss://relay.example"), emptyMap()),
        warning = null,
        fromCurrentDraft = fromCurrentDraft,
    )

    @Test
    fun contentWarningUsesTheNip36Tag() {
        assertEquals(listOf(listOf("content-warning")), contentWarningTags(true))
        assertEquals(emptyList(), contentWarningTags(false))
    }

    @Test
    fun nextMemoUpdatedAtIsNewerThanThePreviousMemo() {
        assertEquals(101, nextMemoUpdatedAt(now = 100, previousUpdatedAt = 100))
        assertEquals(101, nextMemoUpdatedAt(now = 90, previousUpdatedAt = 100))
    }

    @Test
    fun nextMemoUpdatedAtUsesCurrentTimeForANewMemo() {
        assertEquals(100, nextMemoUpdatedAt(now = 100, previousUpdatedAt = null))
        assertEquals(100, nextMemoUpdatedAt(now = 100, previousUpdatedAt = 90))
    }

    @Test
    fun draftDeletionReferencesEventAndReplaceableAddress() {
        assertEquals(
            listOf(
                listOf("e", "event-id"),
                listOf("a", "$MEMO_EVENT_KIND:author:torinos-post-memo-100"),
                listOf("k", MEMO_EVENT_KIND.toString()),
                listOf("client", "ToriNos"),
            ),
            draftDeletionTags(
                eventId = "event-id",
                pubkey = "author",
                identifier = "torinos-post-memo-100",
            ),
        )
    }

    @Test
    fun createsNip92ImetaFromUploadedNip94Metadata() {
        val url = "https://media.example/image.jpg"
        val tags = imetaTagsForAttachments(
            content = "本文\n$url",
            attachments = listOf(
                ImageAttachment(
                    id = 1,
                    previewBytes = null,
                    uploadedUrl = url,
                    isUploading = false,
                    mediaMetadata = MediaMetadata(
                        url = url,
                        mimeType = "image/jpeg",
                        width = 1200,
                        height = 800,
                        thumbnailUrl = "https://media.example/thumb.jpg",
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                listOf(
                    "imeta",
                    "url $url",
                    "m image/jpeg",
                    "dim 1200x800",
                    "thumb https://media.example/thumb.jpg",
                ),
            ),
            tags,
        )
    }

    @Test
    fun memoRoundTripPreservesMediaMetadataForImeta() {
        val metadata = MediaMetadata(
            url = "https://media.example/image.jpg",
            mimeType = "image/jpeg",
            width = 1200,
            height = 800,
        )
        val payload = PostMemoPayload(
            text = "draft",
            hasContentWarning = true,
            imageUrls = listOf(metadata.url),
            imageMetadata = listOf(metadata),
            updatedAt = 10,
        )

        val restored = memoJson.decodeFromString<PostMemoPayload>(memoJson.encodeToString(payload))
            .toPostMemoData()
        val viewModel = PostViewModel()
        viewModel.restoreMemo(restored)

        assertEquals(listOf(metadata), restored.imageMetadata)
        assertTrue(restored.hasContentWarning)
        assertTrue(viewModel.state.value.hasContentWarning)
        assertEquals(
            listOf(
                listOf(
                    "imeta",
                    "url ${metadata.url}",
                    "m image/jpeg",
                    "dim 1200x800",
                ),
            ),
            imetaTagsForAttachments(
                content = "draft\n${metadata.url}",
                attachments = viewModel.state.value.images,
            ),
        )
    }
}
