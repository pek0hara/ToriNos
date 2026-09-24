package com.nostr.torinos.network

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.channel.JoinedChannelRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinedChannelListTest {
    private fun list(id: String, at: Long, tags: List<List<String>>, content: String = "", by: String = "me") =
        NostrEvent(id, by, at, 10005, tags, content, "sig")

    @Test
    fun latestUsesCreatedAtThenSmallestIdAndIgnoresOthers() {
        val a = list("b", 10, emptyList())
        val b = list("a", 10, emptyList())
        val older = list("z", 5, emptyList())
        val foreign = list("x", 99, emptyList(), by = "other")
        assertEquals("a", JoinedChannelList.latest(listOf(a, b, older, foreign), "me")?.id)
    }

    @Test
    fun joiningKeepsExistingTagsAndPrivateContent() {
        val latest = list("l", 100, listOf(listOf("e", "c1"), listOf("t", "keep")), content = "encrypted-private")
        val update = JoinedChannelList.next(latest, "c2", join = true, relayHint = "wss://r", nowSeconds = 50)!!
        assertEquals(
            listOf(listOf("e", "c1"), listOf("t", "keep"), listOf("e", "c2", "wss://r"), listOf("client", "ToriNos")),
            update.tags,
        )
        assertEquals("encrypted-private", update.content)
        assertEquals(101, update.createdAt) // 前のリストより必ず後
    }

    @Test
    fun leavingRemovesOnlyThatChannelAndNoOpsAreSkipped() {
        val latest = list("l", 100, listOf(listOf("e", "c1"), listOf("e", "c2")))
        assertEquals(listOf(listOf("e", "c2"), listOf("client", "ToriNos")), JoinedChannelList.next(latest, "c1", false, null, 200)!!.tags)
        assertNull(JoinedChannelList.next(latest, "c1", join = true, relayHint = null, nowSeconds = 200))
        assertNull(JoinedChannelList.next(latest, "c9", join = false, relayHint = null, nowSeconds = 200))
    }

    @Test
    fun repositoryNeverPublishesWhenNoRelayAnswered() = runTest {
        var published = 0
        val repo = JoinedChannelRepository(
            ownerPubkey = "me",
            signer = FakeSigner(),
            fetchLatest = { JoinedChannelRepository.FetchResult(emptyList(), answered = false) },
            publish = { published++; RelayPublishResult(setOf("wss://r"), emptyMap()) },
        )
        assertTrue(repo.setJoined("c1", join = true, relayHint = null).isFailure)
        assertEquals(0, published)
    }

    @Test
    fun repositoryPublishesMergedListBasedOnLatestFetch() = runTest {
        val sent = mutableListOf<NostrEvent>()
        val remote = list("l", 100, listOf(listOf("e", "fromOtherClient")))
        val repo = JoinedChannelRepository(
            ownerPubkey = "me",
            signer = FakeSigner(),
            fetchLatest = { JoinedChannelRepository.FetchResult(listOf(remote), answered = true) },
            publish = { sent += it; RelayPublishResult(setOf("wss://r"), emptyMap()) },
            now = { 50 },
        )
        val joined = repo.setJoined("c1", join = true, relayHint = null).getOrThrow()
        assertEquals(setOf("fromOtherClient", "c1"), joined)
        assertEquals(listOf(listOf("e", "fromOtherClient"), listOf("e", "c1"), listOf("client", "ToriNos")), sent.single().tags)
        assertEquals(101, sent.single().createdAt)
    }

    @Test
    fun amethystStyleListKeepsAltAndEncryptedContentButReplacesClient() {
        // テスト用アカウントに実在した形(公開 e タグ無し、非公開項目は暗号化 content)。
        val latest = list("l", 100, listOf(listOf("alt", "Public Chat List"), listOf("client", "Amethyst")), content = "nip44-ciphertext")
        val update = JoinedChannelList.next(latest, "c1", join = true, relayHint = null, nowSeconds = 50)!!
        assertEquals(
            listOf(listOf("alt", "Public Chat List"), listOf("e", "c1"), listOf("client", "ToriNos")),
            update.tags,
        )
        assertEquals("nip44-ciphertext", update.content)
        assertEquals(emptyList(), JoinedChannelList.joinedIds(latest))
    }

    private class FakeSigner : AccountSigner {
        override val pubkey = "me"
        override fun encryptToSelf(plaintext: String) = plaintext
        override fun decrypt(content: String, peerPubkey: String) = content
        override fun sign(content: String, kind: Int, tags: List<List<String>>, createdAt: Long?) =
            NostrEvent("new", pubkey, createdAt ?: 1, kind, tags, content, "sig")
    }
}
