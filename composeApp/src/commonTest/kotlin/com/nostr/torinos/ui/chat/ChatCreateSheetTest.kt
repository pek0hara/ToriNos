package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.CreateRejection
import com.nostr.torinos.chat.GroupFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCreateSheetTest {
    private val target = ChatRelaySummary("wss://png.example.com", "png", GroupFlavor.Buzz)
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val names = mapOf(a to "Tanaka", b to "sato")

    @Test
    fun channelCanBeSubmittedOnlyWithAValidName() {
        val sheet = CreateSheetState.Channel(targets = listOf(target), target = target)

        assertFalse(sheet.canSubmit)
        assertFalse(sheet.copy(name = " # ").canSubmit)
        assertTrue(sheet.copy(name = "#general").canSubmit)
        assertFalse(sheet.copy(name = "a".repeat(256)).canSubmit)
        assertFalse(sheet.copy(name = "general", isSubmitting = true).canSubmit)
    }

    @Test
    fun dmNeedsARecipientAndStopsAtEight() {
        val sheet = CreateSheetState.Dm(targets = listOf(target), target = target)

        assertFalse(sheet.canSubmit)
        assertTrue(sheet.copy(picked = listOf(a)).canSubmit)
        assertTrue(sheet.copy(picked = List(7) { it.toString().repeat(64) }).canPickMore)
        assertFalse(sheet.copy(picked = List(8) { it.toString().repeat(64) }).canPickMore)
    }

    @Test
    fun candidatesAreFilteredByNameOrKeyPrefixAndSortedByName() {
        fun name(pubkey: String) = names[pubkey]

        assertEquals(listOf(b, a, c), filterDmCandidates(listOf(a, b, c), emptyList(), "", ::name))
        assertEquals(listOf(a), filterDmCandidates(listOf(a, b, c), emptyList(), "TANA", ::name))
        assertEquals(listOf(c), filterDmCandidates(listOf(a, b, c), emptyList(), "cccc", ::name))
        assertEquals(listOf(a, c), filterDmCandidates(listOf(a, b, c), listOf(b), "", ::name))
        assertEquals(listOf(b), filterDmCandidates(listOf(a, b, c), emptyList(), "", ::name, limit = 1))
    }

    @Test
    fun rejectionMessagesNameTheCause() {
        assertEquals("この名前は既に使われています", createRejectionMessage(CreateRejection.NameTaken, "invalid: …"))
        assertTrue(createRejectionMessage(CreateRejection.NotAllowed, "restricted: guests").contains("restricted: guests"))
        assertTrue(createRejectionMessage(CreateRejection.Other, "error: db").contains("error: db"))
    }
}
