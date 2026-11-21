package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuzzMentionsTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    @Test
    fun atAtTheStartOpensTheQuery() {
        assertEquals(MentionQuery(start = 0, query = ""), activeMentionQuery("@", cursor = 1))
        assertEquals(MentionQuery(start = 0, query = "al"), activeMentionQuery("@al", cursor = 3))
    }

    @Test
    fun atAfterSpaceOrPunctuationIsAQuery() {
        assertEquals(MentionQuery(start = 3, query = "b"), activeMentionQuery("hi @b", cursor = 5))
        assertEquals(MentionQuery(start = 3, query = ""), activeMentionQuery("はい。@", cursor = 4))
    }

    @Test
    fun atInsideAWordIsNotAQuery() {
        // メールアドレスなど。Buzz の表示も、文字（日本語を含む）や数字の直後の @ はメンションにしない。
        assertNull(activeMentionQuery("me@example", cursor = 10))
        assertNull(activeMentionQuery("a/@b", cursor = 4))
        assertNull(activeMentionQuery("こんにちは@", cursor = 6))
    }

    @Test
    fun spaceEndsTheQuery() {
        assertNull(activeMentionQuery("@al ", cursor = 4))
        assertNull(activeMentionQuery("@al\nx", cursor = 5))
    }

    @Test
    fun queryIsReadUpToTheCursor() {
        assertEquals(MentionQuery(start = 0, query = "a"), activeMentionQuery("@alice", cursor = 2))
        assertNull(activeMentionQuery("@alice", cursor = 0))
    }

    @Test
    fun candidatesMatchPrefixFirstThenContainsAndSkipSelf() {
        val names = mapOf(alice to "Alice", bob to "Malice", carol to "Carol")
        val result = mentionCandidates(listOf(bob, alice, carol), names::get, query = "ali", ownPubkey = carol)
        assertEquals(listOf(MentionCandidate(alice, "Alice"), MentionCandidate(bob, "Malice")), result)
    }

    @Test
    fun emptyQueryListsEveryMemberInOrder() {
        val names = mapOf(alice to "Alice", bob to "Bob")
        assertEquals(
            listOf(MentionCandidate(bob, "Bob"), MentionCandidate(alice, "Alice")),
            mentionCandidates(listOf(bob, alice), names::get, query = "", ownPubkey = null),
        )
    }

    @Test
    fun memberWithoutProfileUsesBuzzFallbackName() {
        // Buzz はプロフィールの無い人を pubkey の先頭10文字で表示し、本文の @名前 もそれで照合する。
        assertEquals(
            listOf(MentionCandidate(alice, alice.take(10))),
            mentionCandidates(listOf(alice), { null }, query = "", ownPubkey = null),
        )
    }

    @Test
    fun pickingReplacesTheQueryWithTheName() {
        val query = MentionQuery(start = 3, query = "al")
        assertEquals(MentionInsertion("hi @Alice ", cursor = 10), insertMention("hi @al", query, cursor = 6, name = "Alice"))
        // 後ろに文字があれば、間に空白を1つだけ置く。
        assertEquals(MentionInsertion("@Alice さん", cursor = 7), insertMention("@al さん", MentionQuery(0, "al"), cursor = 3, name = "Alice"))
    }

    @Test
    fun onlyNamesStillInTheTextBecomeRecipients() {
        val picked = listOf(MentionCandidate(alice, "Alice"), MentionCandidate(bob, "Bob"))
        assertEquals(listOf(alice), mentionedPubkeys("@Alice こんにちは", picked))
        assertEquals(listOf(alice, bob), mentionedPubkeys("@Alice と @Bob。", picked))
    }

    @Test
    fun aLongerNameIsNotAMentionOfAShorterOne() {
        assertEquals(emptyList(), mentionedPubkeys("@Bobby", listOf(MentionCandidate(bob, "Bob"))))
        assertEquals(emptyList(), mentionedPubkeys("x@Bob", listOf(MentionCandidate(bob, "Bob"))))
    }

    @Test
    fun recipientsAreUniqueAndAtMost32() {
        val many = (0 until 40).map { i -> MentionCandidate(i.toString(16).padStart(64, '0'), "n$i") }
        val text = many.joinToString(" ") { "@${it.name}" }
        assertEquals(32, mentionedPubkeys(text, many + many).size)
    }

    @Test
    fun mentionTagsAreAddedOnceEach() {
        val draft = GroupEventDraft(9, listOf(listOf("h", "g"), listOf("p", alice)), "@Alice @Bob")
        assertEquals(
            listOf(listOf("h", "g"), listOf("p", alice), listOf("p", bob)),
            draft.withMentionTags(listOf(alice, bob, bob)).tags,
        )
    }

    private fun names(vararg pairs: Pair<String, String>): Map<String, Set<String>> =
        mentionNameTable(pairs.map { it.first }) { pk -> pairs.firstOrNull { it.first == pk }?.second }

    @Test
    fun mentionTargetsComeFromPAndMentionTags() {
        val event = com.nostr.torinos.model.NostrEvent(
            id = "e", pubkey = carol, createdAt = 0, kind = 9,
            tags = listOf(listOf("h", "g"), listOf("p", alice), listOf("mention", bob), listOf("p", alice), listOf("p", "short")),
            content = "", sig = "",
        )
        assertEquals(listOf(alice, bob), buzzMentionTargets(event))
    }

    @Test
    fun knownNamesBecomeMentionSpans() {
        assertEquals(
            listOf(MentionSpan("こんにちは "), MentionSpan("@Alice", alice), MentionSpan(" さん")),
            splitMentions("こんにちは @Alice さん", names(alice to "Alice")),
        )
    }

    @Test
    fun longestNameWinsAndAShorterNameDoesNotTakeAPrefix() {
        val table = names(alice to "Al", bob to "Al Smith")
        assertEquals(listOf(MentionSpan("@Al Smith", bob), MentionSpan("!")), splitMentions("@Al Smith!", table))
        // 名前の直後に文字が続けば別の名前。
        assertEquals(listOf(MentionSpan("@Alx")), splitMentions("@Alx", names(alice to "Al")))
    }

    @Test
    fun atAfterALetterIsNotAMention() {
        assertEquals(listOf(MentionSpan("mail@Alice")), splitMentions("mail@Alice", names(alice to "Alice")))
    }

    @Test
    fun ambiguousNameStaysText() {
        assertEquals(listOf(MentionSpan("@Sam hi")), splitMentions("@Sam hi", names(alice to "Sam", bob to "Sam")))
    }

    @Test
    fun qualifiedMentionPicksTheNamedKey() {
        val table = names(alice to "Sam", bob to "Sam")
        assertEquals(
            listOf(MentionSpan("@sam ($bob)", bob), MentionSpan(" hi")),
            splitMentions("@sam ($bob) hi", table),
        )
    }

    @Test
    fun noMentionsLeavesTextWhole() {
        assertEquals(listOf(MentionSpan("@Alice")), splitMentions("@Alice", emptyMap()))
    }

    @Test
    fun pickedMentionRangesCoverEachMentionLeftInTheText() {
        val picked = listOf(MentionCandidate(alice, "Alice"), MentionCandidate(bob, "研究者"))
        assertEquals(listOf(0 until 6, 9 until 13), pickedMentionRanges("@Alice と @研究者 です", picked))
        // 同じ人を2回書けば2か所とも。
        assertEquals(listOf(0 until 6, 7 until 13), pickedMentionRanges("@Alice @Alice", picked))
    }

    @Test
    fun pickedMentionRangesSkipEditedOrEmbeddedNames() {
        val picked = listOf(MentionCandidate(bob, "Bob"))
        assertEquals(emptyList(), pickedMentionRanges("@Bobby", picked))
        assertEquals(emptyList(), pickedMentionRanges("me@Bob", picked))
        assertEquals(emptyList(), pickedMentionRanges("@Bo", picked))
        assertEquals(emptyList(), pickedMentionRanges("@Bob", emptyList()))
    }

    @Test
    fun pickedMentionRangesPreferTheLongerName() {
        val picked = listOf(MentionCandidate(alice, "ペコ"), MentionCandidate(bob, "ペコ 2"))
        assertEquals(listOf(0 until 5), pickedMentionRanges("@ペコ 2", picked))
    }
}
