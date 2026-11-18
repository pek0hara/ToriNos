package com.nostr.torinos.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.chat.buzzMediaHost
import com.nostr.torinos.ui.profile.AvatarCircle

/**
 * チャットの画面のアイコン。Buzz で作ったアカウントのアイコンはコミュニティの `/media/` にあり、
 * 読むのに Blossom の認証が要るので、参加中のコミュニティの画像なら認証を付けて読む。
 * 認証を付けられないときは頭文字のアイコンにする。
 */
@Composable
internal fun ChatAvatar(
    pubkey: String,
    name: String?,
    pictureUrl: String?,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val account = LocalAccountSession.current?.chat
    val url = pictureUrl?.takeIf { it.isNotBlank() }
    val communities = account?.buzzCommunities?.collectAsState()?.value.orEmpty()
    val needsAuth = url != null && account != null && buzzMediaHost(url, communities) != null
    // URL ごとに作り直す。前の URL の「認証なし」を引き継ぐと、認証の要る画像を認証なしで読みに行って 401 になる。
    var headers by remember(url, needsAuth) { mutableStateOf(if (needsAuth) null else emptyMap<String, String>()) }
    LaunchedEffect(url, needsAuth) {
        if (needsAuth && account != null && url != null) headers = account.avatarHeaders(url)
    }
    AvatarCircle(
        pubkey = pubkey,
        name = name,
        pictureUrl = url.takeIf { headers != null },
        size = size,
        modifier = modifier,
        headers = headers.orEmpty(),
    )
}
