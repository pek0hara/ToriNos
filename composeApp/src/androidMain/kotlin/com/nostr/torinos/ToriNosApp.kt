package com.nostr.torinos

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.nostr.torinos.ui.components.XPostSnapshotCache
import com.nostr.torinos.ui.components.registerAppImageLoader

class ToriNosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        registerAppImageLoader()
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // TRIM_MEMORY_UI_HIDDEN(20)は単にUIが不可視になっただけの通知でメモリ逼迫を
                // 意味しないため除外する。バックグラウンド遷移だけではクリアしない設計方針(7.2)。
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                    level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                ) {
                    XPostSnapshotCache.clear()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java", ReplaceWith("onTrimMemory"))
            override fun onLowMemory() {
                XPostSnapshotCache.clear()
            }
        })
    }

    companion object {
        lateinit var appContext: ToriNosApp
            private set
    }
}
