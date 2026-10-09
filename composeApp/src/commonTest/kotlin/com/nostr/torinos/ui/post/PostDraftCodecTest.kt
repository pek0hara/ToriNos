package com.nostr.torinos.ui.post

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class PostDraftCodecTest {
    private fun wrapper(kind: String = "1", owner: String = "owner") =
        NostrEvent("id", owner, 100, 31234, listOf(listOf("d", "memo"), listOf("k", kind)), "ciphertext", "sig")

    @Test fun standardEnvelopeAndEditorExtensionRoundTrip() {
        val memo = PostMemoPayload(text = "hello :bird:", imageUrls = listOf("https://image.example/a.png"),
            quotes = listOf(PostQuote("note", "nostr:note", "preview")),
            customEmojis = listOf(CustomEmoji("bird", "https://emoji.example/bird.png")),
            hasContentWarning = true, updatedAt = 100)
        val encoded = encodePostDraft(memo, "owner")
        val json = memoJson.parseToJsonElement(encoded).jsonObject
        assertEquals("owner", json["pubkey"]!!.jsonPrimitive.content)
        assertEquals(1, json["kind"]!!.jsonPrimitive.int)
        assertTrue(json["content"]!!.jsonPrimitive.content.contains("https://image.example/a.png"))
        assertEquals(memo, decodePostDraft(encoded, wrapper()))
        assertNull(json["sig"])
    }

    @Test fun legacyDraftIsReadableButDoesNotBypassStandardValidation() {
        val memo = PostMemoPayload(text = "legacy", updatedAt = 10)
        val legacy = memoJson.encodeToString(memo)
        val old = wrapper().copy(tags = listOf(listOf("d", "memo")))
        assertEquals(memo, decodePostDraft(legacy, old))
        val malformed = JsonObject(memoJson.parseToJsonElement(legacy).jsonObject + ("kind" to JsonPrimitive(1)))
        assertNull(decodePostDraft(malformed.toString(), old))
    }

    @Test fun externalStandardDraftRestoresContentWithoutDuplicateAttachment() {
        val event = PostDraftEvent("owner", 100, 1, listOf(listOf("content-warning")), "hello\nhttps://image.example/a.png")
        val restored = assertNotNull(decodePostDraft(memoJson.encodeToString(event), wrapper()))
        assertEquals(event.content, restored.text)
        assertTrue(restored.imageUrls.isEmpty())
        assertTrue(restored.hasContentWarning)
        assertEquals(event.content, postDraftEvent(restored, "owner").content)
    }

    @Test fun anotherAuthorsSameIdentifierDoesNotSuppressOwnDraft() {
        val own = wrapper().copy(createdAt = 99)
        val foreign = wrapper(owner = "other").copy(createdAt = 200)
        assertEquals(2, latestPostDraftEvents(listOf(own, foreign)).size)
        assertTrue(own in latestPostDraftEvents(listOf(own, foreign)))
    }

    @Test fun blankLatestDraftSuppressesOldContentBeforeDecrypting() {
        val old = wrapper().copy(id = "old", createdAt = 99, content = "encrypted old draft")
        val deleted = wrapper().copy(id = "deleted", content = "")
        assertEquals(listOf(deleted), latestPostDraftEvents(listOf(old, deleted)))
        assertNull(decodePostDraft("", deleted))
    }

    @Test fun mismatchedOwnerKindAndArticleAreRejected() {
        val encoded = encodePostDraft(PostMemoPayload(text = "hello", updatedAt = 100), "owner")
        assertNull(decodePostDraft(encoded, wrapper(owner = "other")))
        assertNull(decodePostDraft(encoded, wrapper(kind = "42")))
        val article = PostDraftEvent("owner", 100, 30023, emptyList(), "article")
        assertNull(decodePostDraft(memoJson.encodeToString(article), wrapper(kind = "30023")))
    }

    @Test fun externalAddressOnlyCommentRootIsPreservedWithoutBlankEventTag() {
        val event = PostDraftEvent("owner", 100, 1111, listOf(
            listOf("A", "30023:author:post"), listOf("K", "30023"), listOf("P", "author"),
            listOf("a", "30023:author:post"), listOf("e", "article-id"), listOf("k", "30023"), listOf("p", "author")), "comment")
        val restored = assertNotNull(decodePostDraft(memoJson.encodeToString(event), wrapper("1111")))
        val reply = assertNotNull(restored.toPostMemoData().restoreReplyTarget(com.nostr.torinos.model.NoteContext.Timeline))
        assertTrue(listOf("A", "30023:author:post", "") in reply.tags())
        assertTrue(reply.tags().none { it.firstOrNull() == "E" })
    }

    @Test fun inconsistentExtensionFallsBackToCanonicalContent() {
        val encoded = encodePostDraft(PostMemoPayload(text = "hello", updatedAt = 100), "owner")
        val altered = JsonObject(memoJson.parseToJsonElement(encoded).jsonObject + ("content" to JsonPrimitive("canonical")))
        assertEquals("canonical", decodePostDraft(altered.toString(), wrapper())!!.text)
    }

    @Test fun channelAndArticleCommentReplyRoundTrip() {
        val channel = PostMemoPayload(text = "reply", noteKind = 42, channelId = "channel", replyToId = "parent",
            replyToPubkey = "author", replyRootRelayUrl = "wss://source", updatedAt = 100)
        assertEquals(channel, decodePostDraft(encodePostDraft(channel, "owner"), wrapper("42")))
        val comment = PostMemoPayload(text = "comment", noteKind = COMMENT_EVENT_KIND, replyToId = "article-id",
            replyToPubkey = "author", replyParentKind = 30023, replyRootKind = 30023, replyRootId = "article-id",
            replyRootPubkey = "author", replyRootAddress = "30023:author:post", replyParentAddress = "30023:author:post", updatedAt = 100)
        assertEquals(comment, decodePostDraft(encodePostDraft(comment, "owner"), wrapper("1111")))
        val external = memoJson.encodeToString(postDraftEvent(comment, "owner"))
        val restored = assertNotNull(decodePostDraft(external, wrapper("1111")))
        assertEquals(comment.replyRootAddress, restored.replyRootAddress)
        assertEquals(comment.replyParentKind, restored.replyParentKind)
    }
}
