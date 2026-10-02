package com.nostr.torinos.ui.service

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.RelaySelector
import androidx.compose.runtime.Composable
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.profile.AvatarCircle

/** 他サービスと同じ構成で、絵文字の単一閲覧リレーを選択する。 */
@Composable
fun EmojiServiceHeader(
    ownPubkey: String?,
    ownProfile: NostrProfile?,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onServiceTabSelected: (ServiceTab) -> Unit,
) {
    val relays by RelayStore.relays.collectAsState(initial = emptyList())
    val selectedRelay by RelayStore.selectedEmojiRelayUrl.collectAsState()
    Column {
        AppTopBar(
            navigationIcon = {
                if (ownPubkey != null) {
                    IconButton(onClick = onOpenProfile) {
                        AvatarCircle(
                            pubkey = ownPubkey,
                            name = ownProfile?.bestName,
                            pictureUrl = ownProfile?.picture,
                            size = 32,
                        )
                    }
                }
            },
            title = {
                RelaySelector(
                    relays = relays,
                    selectedRelayUrl = selectedRelay,
                    onRelaySelected = RelayStore::setSelectedEmojiRelayUrl,
                    onOpenRelaySettings = onOpenRelaySettings,
                    menuDescription = "セットと公開登録情報の取得元",
                )
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "設定")
                }
            },
        )
        ServiceTabRow(selectedTab = ServiceTab.Emojis, onTabSelected = onServiceTabSelected)
    }
}
