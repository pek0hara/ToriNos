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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.buildNetworkImageRequest
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

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

/** プロフィールアイコンの下に置くバッジ。アイコンの幅に収まるよう2列で並べる。 */
@Composable
internal fun AvatarBadgeGrid(
    badges: List<BadgeDisplayItem>,
    onBadge: (BadgeDisplayItem) -> Unit,
    onList: () -> Unit,
) {
    val layout = avatarBadgeLayout(badges.size)
    if (layout.visibleCount == 0) return
    val slot = 20.dp
    Column(verticalArrangement = Arrangement.spacedBy(1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        badges.take(layout.visibleCount).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { badge ->
                    Box(Modifier.size(slot).clickable { onBadge(badge) }, contentAlignment = Alignment.Center) {
                        BadgeImage(badge, size = 20)
                    }
                }
            }
        }
        if (layout.overflowCount > 0) {
            // 欄を押し広げないよう、幅が足りなければはみ出して描く。
            Text("+${layout.overflowCount}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false,
                modifier = Modifier.clickable(onClick = onList).wrapContentWidth(unbounded = true))
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
internal fun BadgeDetailDialog(
    badge: BadgeDisplayItem,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    val issuerFlow = remember(badge.issuer) { com.nostr.torinos.network.ProfileRepository.observe(badge.issuer) }
    val issuer by issuerFlow.collectAsState(initial = null)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BadgeDetailContent(badge, issuer, onIssuerClick = { onDismiss(); onUserClick(it) })
                action?.invoke()
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
