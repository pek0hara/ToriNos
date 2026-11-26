package com.nostr.torinos

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.nostr.torinos.util.StartupTrace

/**
 * 回転やダークモードの切り替えでは Activity を作り直さない(マニフェストの configChanges)。
 * アカウント単位の ViewModel は Compose 側で保持しているため、作り直すと表示中の一覧や入力が失われる。
 * Compose は Configuration の変化をそのまま反映するので、ここではシステムバーの配色だけを合わせ直す。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.mark("activity.onCreate")
        // 作り直し（プロセスの復元）では同じ Intent がもう一度来るので、最初の作成でだけ受け取る。
        if (savedInstanceState == null) receiveAppLink(intent)
        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                StartupTrace.mark("ui.firstFrame")
            }
            App()
        }
    }

    /** singleTask なので、起動中に招待リンクを開くとここに届く。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveAppLink(intent)
    }

    /**
     * `torinos://join?…` の招待を預け、ログインの後に参加シートで確かめる（docs/buzz-invite-link-design.md 5.2）。
     * 履歴（最近使ったアプリ）から開き直したときは、前に受け取った Intent がそのまま来るので使わない。
     * URL には招待コードが入るのでログに出さず、受け取ったら Intent から消す。
     */
    private fun receiveAppLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val url = intent.dataString ?: return
        com.nostr.torinos.chat.pendingInvites.offerAppLink(url, System.currentTimeMillis())
        intent.data = null
    }

    override fun onResume() {
        super.onResume()
        com.nostr.torinos.util.refactorTraceLog("App") { "resumed diagnostics=v1" }
    }

    override fun onPause() {
        com.nostr.torinos.util.refactorTraceLog("App") { "paused" }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }
}
