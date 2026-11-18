package com.nostr.torinos.dm

import com.nostr.torinos.account.AccountSessionLease
import com.nostr.torinos.account.PrivateKeyAccountSigner
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.calculateEventId
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.NostrEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class Nip17Test {
    // BIP340 テストベクタの秘密鍵。
    private val aliceKey = "0000000000000000000000000000000000000000000000000000000000000003"
    private val bobKey = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef"
    private val carolKey = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9"
    private val alice = PrivateKeyAccountSigner(aliceKey, AccountSessionLease())
    private val bob = PrivateKeyAccountSigner(bobKey, AccountSessionLease())
    private val carol = PrivateKeyAccountSigner(carolKey, AccountSessionLease())
    private val ephemeralKey = "0b432b2677937381aef05bb02a66ecd012773062cf3fa2549e44f58ed2401710"
    private val now = 1_800_000_000L

    private fun rumor(content: String = "こんにちは", createdAt: Long = now) =
        buildDmRumor(alice.pubkey, listOf(bob.pubkey), content, createdAt)

    private suspend fun wrap(rumor: Rumor, recipient: String, random: Random = Random(1)) =
        wrapRumor(rumor, recipient, alice, now, random, ephemeralKey)

    @Test
    fun rumorIsUnsignedKind14WithRecipientTagsAndComputedId() {
        val r = rumor()
        assertEquals(DM_RUMOR_KIND, r.kind)
        assertEquals(alice.pubkey, r.pubkey)
        assertEquals(listOf(listOf("p", bob.pubkey)), r.tags)
        assertEquals(calculateEventId(NostrEvent(r.id, r.pubkey, r.createdAt, r.kind, r.tags, r.content, "")), r.id)
        // 署名を持たない（漏れても本人の証明にならない）。
        assertTrue("\"sig\"" !in r.toJson())
        assertEquals(r, parseRumorJson(r.toJson()))
    }

    @Test
    fun rumorDropsDuplicateAndSelfRecipients() {
        val r = buildDmRumor(alice.pubkey, listOf(bob.pubkey, bob.pubkey, alice.pubkey), "x", now)
        assertEquals(listOf(listOf("p", bob.pubkey)), r.tags)
    }

    @Test
    fun wrapAndUnwrapRoundTrip() = runTest {
        val r = rumor()
        val wrapped = wrap(r, bob.pubkey)
        assertEquals(GIFT_WRAP_KIND, wrapped.kind)
        assertEquals(listOf(listOf("p", bob.pubkey)), wrapped.tags)
        assertTrue(isValidEvent(wrapped))
        // 送り手は使い捨ての鍵で隠れる。
        assertNotEquals(alice.pubkey, wrapped.pubkey)

        val opened = assertIs<UnwrapResult.Opened>(unwrapGiftWrap(wrapped, bob))
        assertEquals(r, opened.rumor)
    }

    @Test
    fun selfCopyOpensWithSendersOwnKey() = runTest {
        val r = rumor()
        val copy = wrap(r, alice.pubkey)
        val opened = assertIs<UnwrapResult.Opened>(unwrapGiftWrap(copy, alice))
        assertEquals(r, opened.rumor)
    }

    @Test
    fun sealAndWrapTimesAreShiftedIntoThePastTwoDays() = runTest {
        repeat(20) { seed ->
            val wrapped = wrap(rumor(), bob.pubkey, Random(seed))
            assertTrue(wrapped.createdAt in (now - WRAP_TIME_JITTER_SECONDS)..now, "wrap ${wrapped.createdAt}")
            val seal = parseSignedEvent(Nip44.decrypt(wrapped.content, bobKey, wrapped.pubkey))
            assertTrue(seal.createdAt in (now - WRAP_TIME_JITTER_SECONDS)..now, "seal ${seal.createdAt}")
            assertEquals(DM_SEAL_KIND, seal.kind)
            assertEquals(emptyList(), seal.tags)
        }
    }

    @Test
    fun wrapAddressedToSomeoneElseIsRejected() = runTest {
        val wrapped = wrap(rumor(), bob.pubkey)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.NotForMe), unwrapGiftWrap(wrapped, carol))
    }

    @Test
    fun wrongKindIsRejected() = runTest {
        val wrapped = wrap(rumor(), bob.pubkey)
        val other = signEvent(ephemeralKey, wrapped.content, kind = 1, tags = wrapped.tags, createdAt = wrapped.createdAt)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.NotGiftWrap), unwrapGiftWrap(other, bob))
    }

    @Test
    fun brokenWrapContentIsRejected() = runTest {
        val broken = signEvent(ephemeralKey, "not-a-payload", kind = GIFT_WRAP_KIND, tags = listOf(listOf("p", bob.pubkey)), createdAt = now)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.BrokenWrap), unwrapGiftWrap(broken, bob))
    }

    /** 中身を差し替えた seal を、使い捨ての鍵で包み直す。 */
    private fun rewrap(seal: NostrEvent, recipientKeyPub: String): NostrEvent =
        signEvent(
            ephemeralKey,
            Nip44.encrypt(sealJson(seal), ephemeralKey, recipientKeyPub),
            kind = GIFT_WRAP_KIND,
            tags = listOf(listOf("p", recipientKeyPub)),
            createdAt = now,
        )

    @Test
    fun sealWithBadSignatureIsRejected() = runTest {
        val seal = signEvent(aliceKey, Nip44.encrypt(rumor().toJson(), aliceKey, bob.pubkey), kind = DM_SEAL_KIND, createdAt = now)
        val tampered = seal.copy(sig = seal.sig.replaceRange(0, 2, if (seal.sig.startsWith("00")) "11" else "00"))
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.BadSeal), unwrapGiftWrap(rewrap(tampered, bob.pubkey), bob))
    }

    @Test
    fun sealWithTagsIsRejected() = runTest {
        val seal = signEvent(aliceKey, Nip44.encrypt(rumor().toJson(), aliceKey, bob.pubkey), kind = DM_SEAL_KIND, tags = listOf(listOf("p", bob.pubkey)), createdAt = now)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.BadSeal), unwrapGiftWrap(rewrap(seal, bob.pubkey), bob))
    }

    @Test
    fun rumorWithWrongIdIsRejected() = runTest {
        val r = rumor()
        val forged = r.copy(content = "書き換え")
        val seal = signEvent(aliceKey, Nip44.encrypt(forged.toJson(), aliceKey, bob.pubkey), kind = DM_SEAL_KIND, createdAt = now)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.BadRumor), unwrapGiftWrap(rewrap(seal, bob.pubkey), bob))
    }

    @Test
    fun rumorAuthorDifferentFromSealSignerIsRejected() = runTest {
        // carol が「alice が書いた」rumor を自分の seal で送る（なりすまし）。
        val r = rumor()
        val seal = signEvent(carolKey, Nip44.encrypt(r.toJson(), carolKey, bob.pubkey), kind = DM_SEAL_KIND, createdAt = now)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.SenderMismatch), unwrapGiftWrap(rewrap(seal, bob.pubkey), bob))
    }

    @Test
    fun rumorOfOtherKindIsRejected() = runTest {
        val r = buildDmRumor(alice.pubkey, listOf(bob.pubkey), "x", now, kind = 1)
        val seal = signEvent(aliceKey, Nip44.encrypt(r.toJson(), aliceKey, bob.pubkey), kind = DM_SEAL_KIND, createdAt = now)
        assertEquals(UnwrapResult.Rejected(UnwrapFailure.BadRumor), unwrapGiftWrap(rewrap(seal, bob.pubkey), bob))
    }

    @Test
    fun conversationPeersExcludeMe() {
        val r = buildDmRumor(alice.pubkey, listOf(bob.pubkey, carol.pubkey), "x", now)
        assertEquals(setOf(bob.pubkey, carol.pubkey), conversationPeers(r, alice.pubkey))
        assertEquals(setOf(alice.pubkey, carol.pubkey), conversationPeers(r, bob.pubkey))
        // 自分宛ての写し（相手が自分だけ）は、自分との会話。
        val note = buildDmRumor(alice.pubkey, listOf(alice.pubkey), "memo", now)
        assertEquals(setOf(alice.pubkey), conversationPeers(note, alice.pubkey))
    }

    @Test
    fun sinceGoesBackTwoDaysAndAnHour() {
        assertEquals(null, giftWrapSince(null))
        assertEquals(now - 2 * 86_400 - 3_600, giftWrapSince(now))
    }
}
