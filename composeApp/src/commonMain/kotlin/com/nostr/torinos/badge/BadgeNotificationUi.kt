package com.nostr.torinos.badge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The address of a kind 8 award that is valid for [recipient], or null. */
internal fun badgeAwardAddress(award: NostrEvent, recipient: String): BadgeAddress? {
    val address = award.tags.singleOrNull { it.firstOrNull() == "a" }?.getOrNull(1)?.let(BadgeAddress::parse) ?: return null
    return address.takeIf { isBadgeAwardFor(award, it, recipient) }
}

/** Body of a badge award notification: the badge, and adding it to the recipient's own display list. */
@Composable
internal fun BadgeAwardNotice(award: NostrEvent, recipient: String, onUserClick: (String) -> Unit) {
    val repository = LocalBadgeRepository.current
    val store = LocalAccountSession.current?.takeIf { it.pubkey == recipient }?.badges
    val address = remember(award.id, recipient) { badgeAwardAddress(award, recipient) }
    val item = remember(address, award.id) { address?.let { BadgeSelectionItem(listOf(listOf("a", it.value), listOf("e", award.id))) } }
    var display by remember(award.id) { mutableStateOf<BadgeDisplayItem?>(null) }
    var resolved by remember(award.id) { mutableStateOf(false) }
    LaunchedEffect(repository, item) {
        if (repository == null || item == null) { resolved = true; return@LaunchedEffect }
        repository.ingest(listOf(award))
        display = repository.display(item, recipient)
        // Also confirms the definition still exists and neither it nor the award was deleted.
        try { repository.resolveItems(listOf(item)) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        display = repository.display(item, recipient)
        resolved = true
    }
    val badge = display
    if (badge == null) {
        Text(if (resolved) "このバッジは取り消されたか、取得できませんでした" else "バッジを取得しています…",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var open by remember(award.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BadgeImage(badge, 32)
        Text(badge.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (open) BadgeDetailDialog(badge, { open = false }, onUserClick, action = store?.let { { AddToDisplayButton(it, address!!, award.id) } })
}

@Composable
private fun AddToDisplayButton(store: AccountBadgeStore, address: BadgeAddress, awardId: String) {
    val scope = rememberCoroutineScope()
    val management by store.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val displayed = remember(management.latest?.id, address) { store.isDisplayed(address.value) }
    Button(enabled = !displayed && !adding, onClick = {
        adding = true; failed = false
        scope.launch { try { failed = !store.addToDisplay(address, awardId) } finally { adding = false } }
    }) { Text(if (displayed) "表示中" else if (adding) "追加しています…" else "プロフィールに表示") }
    if (failed) management.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
