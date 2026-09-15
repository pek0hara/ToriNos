package com.nostr.torinos.ui.post

import com.nostr.torinos.model.MediaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class PostViewModelTest {
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

        viewModel.reset()

        assertEquals(PostState(), viewModel.state.value)
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
            imageUrls = listOf(metadata.url),
            imageMetadata = listOf(metadata),
            updatedAt = 10,
        )

        val restored = memoJson.decodeFromString<PostMemoPayload>(memoJson.encodeToString(payload))
            .toPostMemoData()
        val viewModel = PostViewModel()
        viewModel.restoreMemo(restored)

        assertEquals(listOf(metadata), restored.imageMetadata)
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
