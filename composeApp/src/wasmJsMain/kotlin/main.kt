import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.nostr.torinos.App
import com.nostr.torinos.ui.components.installRedundantFocusGuard
import com.nostr.torinos.ui.components.installSoftwareKeyboardBridge
import com.nostr.torinos.ui.components.installVisualViewportBridge
import com.nostr.torinos.util.timeZoneDatabase

private const val ComposeViewportContainerId = "compose-root"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    timeZoneDatabase
    installRedundantFocusGuard()
    installSoftwareKeyboardBridge()
    installVisualViewportBridge(ComposeViewportContainerId)
    // 画像はCoilの既定シングルトンを使う(ディスクキャッシュを置けないため registerAppImageLoader は呼ばない)。
    ComposeViewport(ComposeViewportContainerId) {
        App()
    }
}
