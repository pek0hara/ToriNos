package com.nostr.torinos

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
        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                StartupTrace.mark("ui.firstFrame")
            }
            App()
        }
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
