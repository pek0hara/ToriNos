package com.nostr.torinos

import com.nostr.torinos.ui.components.XPostSnapshotCache
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

private var registered = false

/** iOSのmemory warning通知を受けて、OSメモリ圧迫時にクリアすべきキャッシュを解放する。 */
internal fun registerMemoryWarningObserver() {
    if (registered) return
    registered = true
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = null,
    ) { _ ->
        XPostSnapshotCache.clear()
    }
}
