import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.nostr.torinos.App
import com.nostr.torinos.util.timeZoneDatabase
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    timeZoneDatabase
    // 画像はCoilの既定シングルトンを使う(ディスクキャッシュを置けないため registerAppImageLoader は呼ばない)。
    ComposeViewport(document.body!!) {
        App()
    }
}
