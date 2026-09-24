package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelEditDraftTest {
    @Test
    fun editUsesEffectiveMetadataAndKeepsUnchangedFields() {
        val base = ChannelMeta("old", "about", "https://old.example/icon", listOf("wss://old"))
        val draft = ChannelViewModel.EditThreadDialogState(
            title = " new ",
            description = "about",
            picture = "https://new.example/icon",
            candidateRelays = listOf("wss://old", "wss://new"),
            selectedRelays = setOf("wss://new"),
        )
        assertEquals(
            ChannelMeta("new", "about", "https://new.example/icon", listOf("wss://new")),
            editedChannelMeta(base, draft),
        )
    }

    @Test
    fun candidatesIncludeCurrentRecommendedAndWritableRelaysWithoutDuplicates() {
        assertEquals(
            listOf("wss://current", "wss://mine"),
            editRelayCandidates(listOf("wss://current/"), listOf("wss://mine", "wss://current")),
        )
    }

    @Test
    fun invalidPictureUploadAndTooManyRelaysBlockSave() {
        val base = ChannelViewModel.EditThreadDialogState(title = "channel")
        assertTrue(base.canSave)
        assertFalse(base.copy(picture = "http://example.com/icon").canSave)
        assertFalse(base.copy(picture = "https://").canSave)
        assertFalse(base.copy(isUploadingPicture = true).canSave)
        assertFalse(base.copy(selectedRelays = (1..11).map { "wss://$it" }.toSet()).canSave)
        assertTrue(base.copy(picture = "https://example.com/icon").canSave)
    }

    @Test
    fun oldUploadSessionCannotModifyNewEditDialog() {
        val ready = ChannelViewModel.UiState.Ready(
            editDialog = ChannelViewModel.EditThreadDialogState(sessionId = 2, picture = "new"),
        )
        assertEquals(ready, ready.withEditDialogSession(1) { it.copy(picture = "old-result") })
        assertEquals("new-result", ready.withEditDialogSession(2) { it.copy(picture = "new-result") }.editDialog?.picture)
    }
}
