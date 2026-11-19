package com.nostr.torinos.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.chat.ChatAvatar
import kotlinx.coroutines.flow.flowOf

/**
 * リアクションのチップの長押しで出す、そのリアクションをした人の一覧。タップでその人のプロフィールを開く。
 * Buzz とフィードで同じ見た目にする。
 *
 * - [onOpenEmojiSet] があれば、先頭に絵文字セットを開く項目を出す（以前の長押しの動き）。
 * - [totalCount] は件数の表示に使う。フィードでは誰が押したか全部はそろわないことがあるので、
 *   分かっている人より多いときは「ほか N 人」と、[onOpenAll] があれば一覧を開く項目を添える。
 * - [onNeedProfiles] が無ければ、キャッシュ優先でプロフィールを取りに行く。
 */
@Composable
internal fun ReactorsMenu(
    reactors: List<String>,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit,
    onOpenEmojiSet: (() -> Unit)?,
    totalCount: Int = reactors.size,
    onNeedProfiles: ((Set<String>) -> Unit)? = null,
    onOpenAll: (() -> Unit)? = null,
) {
    val pubkeys = remember(reactors) { reactors.toSet() }
    LaunchedEffect(pubkeys) {
        if (pubkeys.isEmpty()) return@LaunchedEffect
        if (onNeedProfiles != null) {
            onNeedProfiles(pubkeys)
        } else {
            ProfileRepository.ensureProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(maxAgeMillis = 3_600_000))
        }
    }
    val profiles by remember(pubkeys) {
        if (pubkeys.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(pubkeys)
    }.collectAsState(initial = emptyMap())
    val shownCount = maxOf(totalCount, reactors.size)
    val unknownCount = shownCount - reactors.size
    ActionDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (onOpenEmojiSet != null) {
            ActionDropdownMenuItem(label = "絵文字セットを開く", icon = Icons.Outlined.EmojiEmotions, onClick = onOpenEmojiSet)
            HorizontalDivider()
        }
        Text(
            "リアクションした人 $shownCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        // 多いときはこの中だけスクロールする（メニュー自体の高さは画面に収まる）。
        Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            reactors.forEach { pubkey ->
                val profile = profiles[pubkey]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onUserClick(pubkey) }.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    ChatAvatar(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 28)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        profile?.bestName ?: (pubkey.take(8) + "…"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (unknownCount > 0) {
                Text(
                    if (reactors.isEmpty()) "${unknownCount}人分はまだ取得できていません" else "ほか ${unknownCount}人",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        if (unknownCount > 0 && onOpenAll != null) {
            HorizontalDivider()
            ActionDropdownMenuItem(label = "リアクションをすべて見る", icon = Icons.Outlined.People, onClick = onOpenAll)
        }
    }
}
