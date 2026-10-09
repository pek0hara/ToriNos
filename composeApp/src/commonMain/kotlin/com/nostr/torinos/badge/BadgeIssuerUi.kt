package com.nostr.torinos.badge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.Nip05Repository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.rememberOptimizedImagePickerLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface IssuerScreen {
    data object List : IssuerScreen
    data class Detail(val address: BadgeAddress) : IssuerScreen
    data class Edit(val address: BadgeAddress?) : IssuerScreen
    data class Award(val address: BadgeAddress) : IssuerScreen
}

/** Title of a page under the issued tab; the list itself has none (the tab row names it). */
internal fun issuerScreenTitle(screen: IssuerScreen, state: BadgeIssuerState): String? {
    fun name(address: BadgeAddress) = state.issued.firstOrNull { it.address == address }?.display?.name
    return when (screen) {
        IssuerScreen.List -> null
        is IssuerScreen.Detail -> name(screen.address) ?: "バッジ"
        is IssuerScreen.Edit -> if (screen.address == null) "新しいバッジ" else "バッジを編集"
        is IssuerScreen.Award -> name(screen.address)?.let { "「$it」を授与" } ?: "授与"
    }
}

/** Where the back arrow of an issued-tab page leads. */
internal fun issuerScreenParent(screen: IssuerScreen): IssuerScreen? = when (screen) {
    IssuerScreen.List -> null
    is IssuerScreen.Detail -> IssuerScreen.List
    is IssuerScreen.Edit -> screen.address?.let { IssuerScreen.Detail(it) } ?: IssuerScreen.List
    is IssuerScreen.Award -> IssuerScreen.Detail(screen.address)
}

/**
 * 「発行したバッジ」タブの中身。見出し・進捗・エラーと新規作成ボタンはシート側が出す。
 * 確認もシート内に表示し、モーダルを重ねない（Webでシートが操作不能になるため）。
 */
@Composable
internal fun IssuedBadgesPanel(session: AccountSession, screen: IssuerScreen, onScreen: (IssuerScreen) -> Unit, onUserClick: (String) -> Unit) {
    val issuer = session.badgeIssuer
    val state by issuer.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(issuer) { issuer.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val current = screen) {
            IssuerScreen.List -> {
                if (state.issued.isEmpty() && !state.busy) BadgeEmptyState("発行したバッジはありません", "右下の「新しいバッジ」から作成できます")
                state.issued.forEach { badge ->
                    BadgeListRow(badge.display, onClick = { onScreen(IssuerScreen.Detail(badge.address)) },
                        supporting = "${badge.awards.sumOf { it.recipients.size }}人に授与") {
                        syncLabel(badge.sync)?.let { BadgeStatusChip(it) }
                    }
                }
                if (state.canLoadMore) OutlinedButton(enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { issuer.loadMore() } }) { Text("授与履歴をさらに読み込む") }
                // Room for the floating "new badge" button so it never covers the last row.
                Spacer(Modifier.height(72.dp))
            }
            is IssuerScreen.Detail -> {
                val badge = state.issued.firstOrNull { it.address == current.address }
                if (badge == null) { BackToList("バッジが見つからないか、削除されました") { onScreen(IssuerScreen.List) }; return@Column }
                IssuedBadgeDetail(issuer, badge, onUserClick,
                    onBack = { onScreen(IssuerScreen.List) },
                    onEdit = { onScreen(IssuerScreen.Edit(badge.address)) },
                    onAward = { onScreen(IssuerScreen.Award(badge.address)) })
            }
            is IssuerScreen.Edit -> {
                val badge = current.address?.let { address -> state.issued.firstOrNull { it.address == address } }
                if (current.address != null && badge == null) { BackToList("バッジが見つからないか、削除されました") { onScreen(IssuerScreen.List) }; return@Column }
                BadgeDefinitionEditor(session, state, badge,
                    onCancel = { onScreen(issuerScreenParent(current) ?: IssuerScreen.List) },
                    onSaved = { onScreen(IssuerScreen.Detail(it)) })
            }
            is IssuerScreen.Award -> {
                val badge = state.issued.firstOrNull { it.address == current.address }
                if (badge == null) { BackToList("バッジが見つからないか、削除されました") { onScreen(IssuerScreen.List) }; return@Column }
                BadgeAwardEditor(session, badge, onDone = { onScreen(IssuerScreen.Detail(badge.address)) })
            }
        }
    }
}

private fun syncLabel(sync: BadgeSyncStatus) = when (sync) {
    BadgeSyncStatus.Synced -> null
    BadgeSyncStatus.Pending -> "送信待ち"
    BadgeSyncStatus.Partial -> "一部未同期"
}

@Composable
private fun BackToList(message: String, onBack: () -> Unit) {
    Text(message)
    TextButton(onClick = onBack) { Text("一覧へ戻る") }
}

@Composable
private fun PubkeyName(pubkey: String, modifier: Modifier = Modifier) {
    val flow = remember(pubkey) { ProfileRepository.observe(pubkey) }
    val profile by flow.collectAsState(initial = null)
    LaunchedEffect(pubkey) { ProfileRepository.ensureProfiles(setOf(pubkey), ProfileFetchPolicy.CacheFirst(300_000)) }
    Text(profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun IssuedBadgeDetail(
    issuer: AccountBadgeIssuer,
    badge: IssuedBadge,
    onUserClick: (String) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAward: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by issuer.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var includeAwards by remember { mutableStateOf(true) }
    var revoking by remember { mutableStateOf<IssuedAward?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BadgeTile(badge.display, 112)
        if (badge.display.description.isNotBlank()) Text(badge.display.description, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("識別子: ${badge.address.identifier}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            syncLabel(badge.sync)?.let { BadgeStatusChip(it) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !state.busy, modifier = Modifier.weight(1f), onClick = onAward) { Text("授与") }
        OutlinedButton(enabled = !state.busy, modifier = Modifier.weight(1f), onClick = onEdit) { Text("編集") }
        TextButton(enabled = !state.busy, onClick = { confirmDelete = true }) { Text("削除", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) {
        DeletionNotice("「${badge.display.name}」を削除しますか？受け取った人のプロフィールにも表示されなくなります。")
        if (badge.awards.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeAwards, onCheckedChange = { includeAwards = it })
            Text("授与（${badge.awards.size}件）もまとめて取り消す")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                scope.launch { if (issuer.deleteDefinition(badge.address, includeAwards)) onBack() }
            }) { Text("削除する") }
            TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") }
        }
    }
    HorizontalDivider()
    Text("授与した人", style = MaterialTheme.typography.titleSmall)
    if (badge.awards.isEmpty()) Text("まだ授与していません")
    badge.awards.forEach { award ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                award.recipients.forEach { recipient -> PubkeyName(recipient, Modifier.clickable { onUserClick(recipient) }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatTimestamp(award.event.createdAt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    syncLabel(award.sync)?.let { BadgeStatusChip(it) }
                }
            }
            TextButton(enabled = !state.busy, onClick = { revoking = award }) { Text("取り消す") }
        }
        if (revoking?.event?.id == award.event.id) {
            DeletionNotice(if (award.recipients.size > 1) "この授与を取り消すと、${award.recipients.size}人全員から外れます。" else "この授与を取り消しますか？")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                    scope.launch { if (issuer.revokeAward(award.event)) revoking = null }
                }) { Text("取り消す") }
                TextButton(onClick = { revoking = null }) { Text("キャンセル") }
            }
        }
    }
}

@Composable
private fun DeletionNotice(text: String) {
    Text(text)
    Text("削除要求を送りますが、すべてのリレー・クライアントから消える保証はありません。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BadgeDefinitionEditor(
    session: AccountSession,
    state: BadgeIssuerState,
    editing: IssuedBadge?,
    onCancel: () -> Unit,
    onSaved: (BadgeAddress) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Keyed by address, not version: a newer version arriving mid-edit surfaces as a conflict on save
    // instead of silently discarding what the user typed.
    var draft by remember(editing?.address) { mutableStateOf(editing?.draft ?: BadgeDefinitionDraft(identifier = "", name = "")) }
    var identifierEdited by remember(editing?.address) { mutableStateOf(editing != null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var notSquare by remember { mutableStateOf(false) }
    var confirmRevive by remember { mutableStateOf(false) }
    val pickImage = rememberOptimizedImagePickerLauncher { image ->
        if (image == null) return@rememberOptimizedImagePickerLauncher
        uploading = true; uploadError = null
        scope.launch {
            try {
                val main = ImageUploader.uploadMedia(image.uploadBytes, image.mimeType, session.signer).getOrThrow()
                // The picker falls back to the full image when it cannot shrink it; then there is no separate thumbnail.
                val thumb = if (image.previewBytes.contentEquals(image.uploadBytes)) null
                    else ImageUploader.uploadMedia(image.previewBytes, "image/jpeg", session.signer).getOrNull()
                fun size(width: Int?, height: Int?) = if (width != null && height != null) "${width}x$height" else null
                notSquare = main.width != null && main.height != null && main.width != main.height
                draft = draft.copy(imageUrl = main.url, imageSize = size(main.width, main.height),
                    thumbUrl = thumb?.url, thumbSize = thumb?.let { size(it.width, it.height) })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { uploadError = "画像をアップロードできませんでした" }
            finally { uploading = false }
        }
    }
    val error = validateBadgeDefinition(draft, if (editing == null) state.usedIdentifiers - state.deletedIdentifiers else emptySet())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (draft.imageUrl != null) BadgeImage(BadgeDisplayItem(address = "", awardId = "", name = draft.name, description = "",
            issuer = session.pubkey, recipient = "", awardedAt = 0, imageUrl = draft.imageUrl, thumbnails = emptyList()), 64, detail = true)
        OutlinedButton(enabled = !uploading, onClick = pickImage) { Text(if (draft.imageUrl == null) "画像を選ぶ" else "画像を変更") }
        if (draft.imageUrl != null) TextButton(onClick = { draft = draft.copy(imageUrl = null, imageSize = null, thumbUrl = null, thumbSize = null) }) { Text("画像を外す") }
    }
    if (uploading) Text("アップロード中…")
    uploadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (notSquare) Text("正方形の画像を推奨します。他のクライアントでは切り取られて表示されることがあります。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = draft.name, singleLine = true, label = { Text("名前") }, modifier = Modifier.fillMaxWidth(), onValueChange = { name ->
        draft = draft.copy(name = name, identifier = if (identifierEdited) draft.identifier else badgeIdentifierFrom(name, state.usedIdentifiers))
    })
    OutlinedTextField(value = draft.description, label = { Text("説明") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        onValueChange = { draft = draft.copy(description = it) })
    OutlinedTextField(value = draft.identifier, singleLine = true, enabled = editing == null, modifier = Modifier.fillMaxWidth(),
        label = { Text(if (editing == null) "識別子（作成後は変更できません）" else "識別子") }, onValueChange = { identifierEdited = true; draft = draft.copy(identifier = it.trim()) })
    val reviving = editing == null && draft.identifier in state.deletedIdentifiers
    if (reviving) Text("この識別子は削除済みのバッジで使われていました。同じ識別子で作り直すと、取り消していない過去の授与が再び有効になります。",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (draft.name.isNotEmpty() || identifierEdited) error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = error == null && !uploading && !state.busy && draft != editing?.draft, onClick = {
            if (reviving && !confirmRevive) { confirmRevive = true; return@Button }
            scope.launch {
                if (session.badgeIssuer.saveDefinition(draft, allowRevive = reviving)) onSaved(BadgeAddress(30009, session.pubkey, draft.identifier))
            }
        }) { Text(if (reviving && !confirmRevive) "作り直す" else if (reviving) "作り直しを確定" else "保存") }
        TextButton(onClick = onCancel) { Text("キャンセル") }
    }
}

@Composable
private fun BadgeAwardEditor(session: AccountSession, badge: IssuedBadge, onDone: () -> Unit) {
    val issuer = session.badgeIssuer
    val scope = rememberCoroutineScope()
    val state by issuer.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var recipients by remember { mutableStateOf(listOf<String>()) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val awarded = remember(badge) { badge.awards.flatMap { it.recipients }.toSet() }
    val follows by session.followRepository.followedPubkeys.collectAsState()
    val followsLoaded by session.followRepository.loaded.collectAsState()
    val profilesFlow = remember(follows) { ProfileRepository.observe(follows) }
    val profiles by profilesFlow.collectAsState(initial = remember(follows) { ProfileRepository.getCached(follows) })
    // Names are needed for search; profiles already cached are not fetched again.
    LaunchedEffect(follows) { if (follows.isNotEmpty()) ProfileRepository.ensureProfiles(follows, ProfileFetchPolicy.CacheFirst(300_000)) }
    fun rejection(pubkey: String): String? = when {
        pubkey in recipients -> "すでに追加しています"
        pubkey in awarded -> "この人にはすでに授与しています"
        recipients.size >= BADGE_AWARD_RECIPIENTS_MAX -> "一度に授与できるのは${BADGE_AWARD_RECIPIENTS_MAX}人までです"
        else -> null
    }
    fun add(pubkey: String) {
        inputError = rejection(pubkey)
        if (inputError == null) { recipients = recipients + pubkey; input = "" }
    }
    // Rows show a checkbox, so a second tap on a chosen person removes them instead of reporting an error.
    fun toggle(pubkey: String) { if (pubkey in recipients) recipients = recipients - pubkey else add(pubkey) }
    if (picking) {
        FollowPicker(follows, followsLoaded, profiles, recipients, awarded, onToggle = ::toggle, onDone = { picking = false; inputError = null })
        return
    }
    // Name search within follows; a pasted key needs no suggestions.
    val suggestions = remember(input, profiles, follows) {
        if (input.isBlank() || parseBadgeRecipient(input) is BadgeRecipientInput.Pubkey) emptyList()
        else searchFollows(input, follows, profiles, limit = 8)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = input, singleLine = true, modifier = Modifier.weight(1f), label = { Text("名前・npub・NIP-05") },
            onValueChange = { input = it; inputError = null })
        Button(enabled = input.isNotBlank() && !resolving, onClick = {
            when (val parsed = parseBadgeRecipient(input)) {
                is BadgeRecipientInput.Pubkey -> add(parsed.hex)
                is BadgeRecipientInput.Nip05 -> {
                    resolving = true
                    scope.launch {
                        try { Nip05Repository.resolve(parsed.address)?.let(::add) ?: run { inputError = "NIP-05 アドレスを確認できませんでした" } }
                        finally { resolving = false }
                    }
                }
                BadgeRecipientInput.Invalid -> suggestions.singleOrNull()?.let(::add)
                    ?: run { inputError = "候補から選ぶか、npub・公開鍵・NIP-05 アドレスを入力してください" }
            }
        }) { Text("追加") }
    }
    inputError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    suggestions.forEach { pubkey ->
        FollowRow(pubkey, profiles[pubkey], status = followStatus(pubkey, recipients, awarded), onClick = { toggle(pubkey) })
    }
    val typingKey = input.trim().removePrefix("nostr:").let { it.startsWith("npub1") || (it.length >= 8 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }) }
    if (input.isNotBlank() && !typingKey && suggestions.isEmpty() && parseBadgeRecipient(input) == BadgeRecipientInput.Invalid)
        Text("フォロー中に一致する人はいません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { picking = true }) { Text("フォローから選ぶ") }
    if (recipients.isNotEmpty()) Text("受賞者（${recipients.size}人）", style = MaterialTheme.typography.titleSmall)
    recipients.forEach { pubkey ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            PubkeyName(pubkey, Modifier.weight(1f))
            TextButton(onClick = { recipients = recipients - pubkey }) { Text("外す") }
        }
    }
    Text("受賞者ごとに授与イベントを送ります。受け取った人がプロフィールに表示するかは本人が選びます。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = recipients.isNotEmpty() && !state.busy, onClick = {
            scope.launch { if (issuer.award(badge.address, recipients)) onDone() }
        }) { Text("${recipients.size}人に授与") }
        TextButton(onClick = onDone) { Text("キャンセル") }
    }
}

private enum class FollowStatus { Available, Selected, Awarded }

private fun followStatus(pubkey: String, recipients: List<String>, awarded: Set<String>) = when (pubkey) {
    in awarded -> FollowStatus.Awarded
    in recipients -> FollowStatus.Selected
    else -> FollowStatus.Available
}

@Composable
private fun FollowRow(pubkey: String, profile: NostrProfile?, status: FollowStatus, onClick: () -> Unit) {
    // One toggle per row, so screen readers announce a single checkbox instead of a button plus a checkbox.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = status == FollowStatus.Selected,
            enabled = status != FollowStatus.Awarded, role = Role.Checkbox, onValueChange = { onClick() }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarCircle(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 32)
        Column(Modifier.weight(1f)) {
            Text(profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            profile?.nip05?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (status) {
            FollowStatus.Awarded -> Text("授与済み", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FollowStatus.Selected -> Checkbox(checked = true, onCheckedChange = null)
            FollowStatus.Available -> Checkbox(checked = false, onCheckedChange = null)
        }
    }
}

/** Follows with their own search, shown in place of the award form (no stacked modal). */
@Composable
private fun FollowPicker(
    follows: Set<String>,
    loaded: Boolean,
    profiles: Map<String, NostrProfile>,
    recipients: List<String>,
    awarded: Set<String>,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, follows, profiles) { searchFollows(query, follows, profiles) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("フォローから選ぶ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(onClick = onDone) { Text("完了（${recipients.size}人）") }
    }
    OutlinedTextField(value = query, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("名前で絞り込む") },
        onValueChange = { query = it })
    when {
        !loaded && follows.isEmpty() -> Text("フォローを読み込んでいます…")
        follows.isEmpty() -> Text("フォローしている人がいません")
        shown.isEmpty() -> Text("一致する人はいません")
    }
    if (recipients.size >= BADGE_AWARD_RECIPIENTS_MAX) Text("一度に授与できるのは${BADGE_AWARD_RECIPIENTS_MAX}人までです",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    // Bounded height: the sheet scrolls as a whole, and a lazy list inside it needs a fixed size.
    LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
        items(shown, key = { it }) { pubkey ->
            FollowRow(pubkey, profiles[pubkey], followStatus(pubkey, recipients, awarded), onClick = { onToggle(pubkey) })
        }
    }
}
