package com.nostr.torinos.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostr.torinos.PhoneBrowser
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch

/** Google Play の ToriNos。 */
internal const val TORINOS_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.nostr.torinos"

/** App Store の ToriNos（URL が決まるまでは null。検索の案内を出す）。 */
internal val TORINOS_APP_STORE_URL: String? = null

/**
 * スマホのブラウザで招待リンクを開いたときの入口（docs/buzz-invite-link-design.md 3）。
 * スマホではアプリで参加してもらう。リンクをコピーしてからストアへ案内し、アプリの最初の画面で貼ってもらう。
 * この画面では、招待コードを使う通信も署名もしない。
 */
@Composable
internal fun PhoneInviteLanding(
    host: String,
    link: String,
    phone: PhoneBrowser,
    onContinueInBrowser: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("コミュニティに招待されています", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(host, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                "スマホでは ToriNos アプリから参加します。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Step("1. 招待リンクをコピーする")
            Button(
                onClick = { scope.launch { clipboard.setPlainText(link); copied = true } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (copied) "コピーしました" else "招待リンクをコピー") }

            Step("2. アプリを入れる")
            val storeUrl = when (phone) {
                PhoneBrowser.Android -> TORINOS_PLAY_STORE_URL
                PhoneBrowser.Ios -> TORINOS_APP_STORE_URL
            }
            if (storeUrl != null) {
                OutlinedButton(onClick = { uriHandler.openUri(storeUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (phone == PhoneBrowser.Android) "Google Play で入手" else "App Store で入手")
                }
            } else {
                Text("App Store で「ToriNos」を検索して入れてください。", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }

            Step("3. アプリで貼り付ける")
            Text(
                "アプリを開き、最初の画面の「招待リンクを持っている」に貼り付けてください。" +
                    "すでにログインしている方は、設定の「チャット」から貼り付けられます。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            Text(
                "アプリを入れられないときは、PC のブラウザでこの招待リンクを開いてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onContinueInBrowser) { Text("このブラウザで続ける（PC の方）") }
            TextButton(onClick = onClose) { Text("閉じる") }
        }
    }
}

@Composable
private fun Step(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
