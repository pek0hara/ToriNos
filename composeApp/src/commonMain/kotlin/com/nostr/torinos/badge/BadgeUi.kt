package com.nostr.torinos.badge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.buildNetworkImageRequest
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal val LocalBadgeRepository = staticCompositionLocalOf<BadgeRepository?> { null }

@Composable
internal fun BadgeHost(session: AccountSession?, content: @Composable () -> Unit) {
    // Ingest and recomputation run per received event, so keep them off the main thread.
    val anonymous = remember(session) {
        if (session == null) BadgeRepository(CoroutineScope(SupervisorJob() + Dispatchers.Default),
            readRelays = { RelayStore.enabledRelayUrlsSnapshot().toSet() }) else null
    }
    DisposableEffect(anonymous) { onDispose { anonymous?.close() } }
    CompositionLocalProvider(LocalBadgeRepository provides (session?.badgeRepository ?: anonymous), content = content)
}

@Composable
private fun BadgeDemand(repository: BadgeRepository?, pubkey: String) {
    DisposableEffect(repository, pubkey) {
        val release = repository?.acquire(pubkey)
        onDispose { release?.invoke() }
    }
}

@Composable
internal fun rememberBadgeProfile(pubkey: String): BadgeProfileState {
    val repository = LocalBadgeRepository.current
    val flow = remember(repository, pubkey) { repository?.observe(pubkey) ?: MutableStateFlow(BadgeProfileState()) }
    BadgeDemand(repository, pubkey)
    return flow.collectAsState().value
}

/**
 * Feed cards only show the badge list. Loading/partial/reference updates arrive several times per
 * refresh, so cards watch just the list and the list instance stays the same while it is equal.
 * Read the returned state where it is used so only that scope recomposes.
 */
@Composable
internal fun rememberBadges(pubkey: String): State<List<BadgeDisplayItem>> {
    val repository = LocalBadgeRepository.current
    // One observe() call: it takes the repository lock, which is not reentrant on iOS.
    val (flow, initial) = remember(repository, pubkey) {
        val source = repository?.observe(pubkey)
        (source?.let(::badgeListOf) ?: flowOf(emptyList())) to source?.value?.badges.orEmpty()
    }
    BadgeDemand(repository, pubkey)
    return flow.collectAsState(initial)
}

@Composable
internal fun BadgeImage(badge: BadgeDisplayItem, size: Int = 24, detail: Boolean = false) {
    val pixels = with(LocalDensity.current) { size.dp.roundToPx() }
    val url = if (detail) badge.imageUrl ?: badge.thumbnailUrl(pixels) else badge.thumbnailUrl(pixels)
    if (url == null) Icon(Icons.Default.EmojiEvents, contentDescription = badge.name, modifier = Modifier.size(size.dp))
    else {
        val context = LocalPlatformContext.current
        val request = remember(context, url, pixels) { buildNetworkImageRequest(context, url, maxDecodeSizePx = pixels, animate = false) }
        SubcomposeAsyncImage(model = request, contentDescription = "${badge.name}、発行者 ${badge.issuer.take(8)}",
            modifier = Modifier.size(size.dp),
            loading = { Icon(Icons.Default.EmojiEvents, badge.name) },
            error = { Icon(Icons.Default.EmojiEvents, badge.name) })
    }
}

@Composable
internal fun BadgeStrip(
    badges: List<BadgeDisplayItem>,
    layout: BadgeStripLayout,
    onBadge: (BadgeDisplayItem) -> Unit,
    onList: () -> Unit,
    itemHeight: Dp = 42.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        badges.take(layout.visibleCount).forEach { badge ->
            Box(Modifier.width(24.dp).height(itemHeight).clickable { onBadge(badge) }, contentAlignment = Alignment.Center) {
                BadgeImage(badge)
            }
        }
        if (layout.overflowCount > 0) {
            Box(Modifier.height(itemHeight).clickable(onClick = onList), contentAlignment = Alignment.Center) {
                Text("+${layout.overflowCount}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
internal fun BadgeAvatarOverlay(badge: BadgeDisplayItem, size: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surface, CircleShape).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)) {
            BadgeImage(badge, size - 4)
        }
    }
}

@Composable
internal fun BadgeDetailDialog(badge: BadgeDisplayItem, onDismiss: () -> Unit, onUserClick: (String) -> Unit) {
    val issuerFlow = remember(badge.issuer) { com.nostr.torinos.network.ProfileRepository.observe(badge.issuer) }
    val issuer by issuerFlow.collectAsState(initial = null)
    LaunchedEffect(badge.issuer) {
        com.nostr.torinos.network.ProfileRepository.ensureProfiles(setOf(badge.issuer), com.nostr.torinos.network.ProfileFetchPolicy.CacheFirst(300_000))
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(badge.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BadgeImage(badge, 160, detail = true)
                if (badge.description.isNotBlank()) Text(badge.description)
                Text("発行者: ${issuer?.bestName ?: (badge.issuer.take(8) + "…" + badge.issuer.takeLast(8))}")
                TextButton(onClick = { onDismiss(); onUserClick(badge.issuer) }) { Text("発行者のプロフィール") }
                Text("受賞者: ${badge.recipient.take(8)}…${badge.recipient.takeLast(8)}")
                Text("授与日時: ${com.nostr.torinos.ui.components.formatTimestamp(badge.awardedAt)}")
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BadgeListSheet(pubkey: String, onDismiss: () -> Unit, onUserClick: (String) -> Unit) {
    val profile = rememberBadgeProfile(pubkey)
    val repository = LocalBadgeRepository.current
    val scope = rememberCoroutineScope()
    val store = LocalAccountSession.current?.takeIf { it.pubkey == pubkey }?.badges
    val management = store?.state?.collectAsState()?.value
    var draft by remember(pubkey) { mutableStateOf<BadgeDraft?>(null) }
    var editing by remember(pubkey) { mutableStateOf(false) }
    var selected by remember(pubkey) { mutableStateOf<BadgeDisplayItem?>(null) }
    var tab by remember(pubkey) { mutableStateOf(0) }
    var error by remember(pubkey) { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        if (store != null) {
            try { store.refreshSelection(); store.loadAwards() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "取得できませんでした" }
        }
    }
    val detail = selected
    if (detail != null) {
        // Use one modal at a time; nested dialogs can leave the sheet inaccessible on Web.
        BadgeDetailDialog(detail, { selected = null }) { target -> onDismiss(); onUserClick(target) }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (store != null) "バッジを管理" else "表示バッジ", style = MaterialTheme.typography.titleLarge)
            if (profile.loading || management?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: management?.message ?: profile.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (profile.partial || management?.incomplete == true) Text("一部のみ取得しています")
            if (profile.partial) TextButton(onClick = { repository?.resolveMore(pubkey) }) { Text("表示バッジをさらに取得") }
            TextButton(onClick = {
                repository?.request(pubkey, force = true)
                if (store != null) scope.launch { try { store.refreshSelection(); store.loadAwards() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
            }) { Text("再読み込み") }
            if (store != null) {
                Row {
                    TextButton(onClick = { tab = 0 }) { Text("表示中") }
                    TextButton(onClick = { tab = 1 }) { Text("受け取ったバッジ") }
                }
                if (!editing) TextButton(enabled = management?.ready == true, onClick = { draft = store.draft(); editing = true }) { Text("表示バッジを編集") }
            }
            val known = (profile.badges + management?.awards.orEmpty()).associateBy { it.address }
            if (editing && tab == 0) {
                val current = draft ?: store!!.draft()
                if (current.items.isEmpty()) Text("表示するバッジはありません")
                current.items.forEachIndexed { index, item ->
                    Column {
                        Text(known[item.address?.value]?.name ?: if (item.address?.kind == 30008) "バッジセット: ${item.address?.identifier}" else "未取得: ${item.address?.identifier}")
                        Row {
                            TextButton(enabled = index > 0, onClick = { val next = current.items.toMutableList(); val moving = next.removeAt(index); next.add(index - 1, moving); draft = current.copy(items = next) }) { Text("↑") }
                            TextButton(enabled = index < current.items.lastIndex, onClick = { val next = current.items.toMutableList(); val moving = next.removeAt(index); next.add(index + 1, moving); draft = current.copy(items = next) }) { Text("↓") }
                            TextButton(enabled = index > 0, onClick = { val next = current.items.toMutableList(); val moving = next.removeAt(index); next.add(0, moving); draft = current.copy(items = next) }) { Text("先頭") }
                            TextButton(onClick = { draft = current.copy(items = current.items.filterIndexed { i, _ -> i != index }) }) { Text("表示解除") }
                        }
                    }
                }
            } else {
                val badges = if (tab == 1 && store != null) management?.awards.orEmpty() else profile.badges
                if (badges.isEmpty() && !profile.loading && management?.busy != true && profile.error == null && management?.message == null) Text(if (tab == 1) "受け取ったバッジはありません" else "表示バッジはありません")
                badges.forEach { badge ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selected = badge }) { BadgeImage(badge) }
                        Text(badge.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (store != null && tab == 1) {
                            val currentDraft = draft
                            val selectedItems = currentDraft?.let { current ->
                                current.source?.let { source -> repository?.expand(source.copy(tags = BadgeSelection.parse(source.tags).editedTags(current.items))) }
                                    ?: current.items
                            }
                            val chosen = selectedItems?.any { it.address?.value == badge.address } == true || (!editing && profile.badges.any { it.address == badge.address })
                            TextButton(enabled = !chosen, onClick = {
                                val current = draft ?: store.draft()
                                draft = current.copy(items = current.items + BadgeSelectionItem(listOf(listOf("a", badge.address), listOf("e", badge.awardId))))
                                editing = true
                            }) { Text(if (chosen) "選択済み" else "表示に追加") }
                        }
                    }
                }
                if (tab == 1 && store != null && management?.canLoadMore == true) TextButton(enabled = management.busy.not(), onClick = { scope.launch { store.loadAwards(reset = false) } }) { Text("さらに読み込む") }
            }
            if (editing && store != null) {
                Row {
                    Button(enabled = management?.busy != true && management?.ready == true, onClick = {
                        val current = draft ?: return@Button
                        scope.launch { if (store.save(current)) { editing = false; draft = null; tab = 0 } }
                    }) { Text("保存") }
                    TextButton(onClick = { draft = null; editing = false }) { Text("編集を取り消す") }
                }
                if (management?.latest?.id != draft?.baselineId) TextButton(onClick = { draft = store.draft(); tab = 0 }) { Text("最新版を読み込んで編集し直す") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
