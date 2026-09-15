package com.nostr.torinos.ui.components

import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.endEditing

internal actual fun dismissPlatformKeyboard() {
    val activeScene = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
    val window = activeScene?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
        ?: activeScene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
    window?.endEditing(true)
}
