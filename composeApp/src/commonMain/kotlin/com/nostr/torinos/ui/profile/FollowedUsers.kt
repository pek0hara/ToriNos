package com.nostr.torinos.ui.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus

/** 概要で重ねて表示するアイコンの数。 */
internal const val FOLLOWED_USERS_SUMMARY_COUNT = 5

/** この人数を超えたら、一覧に絞り込み欄を出す。 */
private const val FILTER_THRESHOLD = 10

private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L

/** 端末のキャッシュにあるプロフィールを追従する。通信はしない。 */
@Composable
internal fun rememberCachedProfiles(pubkeys: Collection<String>): Map<String, NostrProfile> {
    val keys = remember(pubkeys) { pubkeys.toSet() }
    val flow = remember(keys) { ProfileRepository.observe(keys) }
    val initial = remember(keys) { ProfileRepository.getCached(keys) }
    val profiles by flow.collectAsState(initial = initial)
    return profiles
}

/** 画面に出す人のプロフィールだけを取得する。数百人を一度に取りに行かない。 */
@Composable
private fun RequestProfiles(pubkeys: Set<String>) {
    LaunchedEffect(pubkeys) {
        if (pubkeys.isNotEmpty()) {
            ProfileRepository.ensureProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS))
        }
    }
}

/** 表示名順に並べる。名前を取得できていない人は後ろにまとめる。 */
internal fun sortFollowedUsers(
    pubkeys: List<String>,
    profiles: Map<String, NostrProfile>,
): List<String> = pubkeys.sortedWith(
    compareBy<String> { profiles[it]?.bestName == null }
        .thenBy { profiles[it]?.bestName?.lowercase().orEmpty() }
        .thenBy { it },
)

/** 名前・NIP-05 のどれかに含まれる人だけに絞る。 */
internal fun filterFollowedUsers(
    pubkeys: List<String>,
    profiles: Map<String, NostrProfile>,
    query: String,
): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return pubkeys
    return pubkeys.filter { pubkey ->
        val profile = profiles[pubkey] ?: return@filter false
        listOfNotNull(profile.displayName, profile.name, profile.nip05)
            .any { it.contains(trimmed, ignoreCase = true) }
    }
}

/** 先頭数人のアイコンを重ねて見せ、全員の一覧への入口にする。 */
@Composable
internal fun FollowedUsersSummary(
    title: String,
    users: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** アイコンの縁取り。背景色に合わせると重なりが見やすい。 */
    avatarOutline: Color = MaterialTheme.colorScheme.surface,
) {
    val cached = rememberCachedProfiles(users)
    val shown = remember(users, cached) {
        sortFollowedUsers(users, cached).take(FOLLOWED_USERS_SUMMARY_COUNT)
    }
    // 一覧を開くまでは、重ねて表示する数人分だけ取得する。並びが変わっても取り直さない。
    val requested = remember(users) { shown.toSet() }
    RequestProfiles(requested)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "すべて表示", onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                shown.forEachIndexed { index, pubkey ->
                    val profile = cached[pubkey]
                    AvatarCircle(
                        pubkey = pubkey,
                        name = profile?.bestName,
                        pictureUrl = profile?.picture,
                        size = 28,
                        modifier = Modifier
                            .offset(x = (-8 * index).dp)
                            .border(2.dp, avatarOutline, CircleShape),
                    )
                }
                val rest = users.size - shown.size
                if (rest > 0) {
                    Text(
                        text = "+$rest",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = (-8 * shown.size + 4).dp),
                    )
                }
            }
        }
        Text(
            text = "すべて",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * フォロー先の一覧。行をタップするとプロフィールを開く。
 * [onBack] があれば見出しに戻るボタンを出す。
 */
@Composable
internal fun FollowedUsersPage(
    title: String,
    users: List<String>,
    onOpenProfile: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val profiles = rememberCachedProfiles(users)
    RequestProfiles(remember(users) { users.toSet() })
    var query by remember { mutableStateOf("") }
    val visibleUsers = remember(users, profiles, query) {
        filterFollowedUsers(sortFollowedUsers(users, profiles), profiles, query)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = if (onBack != null) 4.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
        }
        if (users.size > FILTER_THRESHOLD) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .avoidStaleAccessibilityFocus(),
                placeholder = { Text("名前で絞り込み") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )
        }
        LazyColumn {
            items(visibleUsers, key = { it }) { pubkey ->
                val profile = profiles[pubkey]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProfile(pubkey) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AvatarCircle(
                        pubkey = pubkey,
                        name = profile?.bestName,
                        pictureUrl = profile?.picture,
                        size = 36,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ProfileNameText(
                            profile = profile,
                            fallback = shortPubkey(pubkey),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        profile?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                            Text(
                                text = nip05,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (visibleUsers.isEmpty()) {
                item(key = "users-empty") {
                    Text(
                        text = "一致する人はいません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** フォロー先の一覧だけを出すシート。行をタップすると閉じてからプロフィールを開く。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowedUsersSheet(
    title: String,
    users: List<String>,
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FollowedUsersPage(
            title = title,
            users = users,
            onOpenProfile = { pubkey ->
                // シートはプロフィールより手前に出るため、閉じてから開く。
                onDismiss()
                onOpenProfile(pubkey)
            },
        )
    }
}

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "…" + pubkey.takeLast(8)
