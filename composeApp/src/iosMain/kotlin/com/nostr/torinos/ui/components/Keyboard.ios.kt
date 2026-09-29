package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIKeyboardWillHideNotification
import platform.UIKit.UIKeyboardWillShowNotification
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

@Composable
internal actual fun rememberSoftwareKeyboardVisible(): Boolean {
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val showObserver = center.addObserverForName(
            name = UIKeyboardWillShowNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> visible = true }
        val hideObserver = center.addObserverForName(
            name = UIKeyboardWillHideNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> visible = false }
        onDispose {
            center.removeObserver(showObserver)
            center.removeObserver(hideObserver)
        }
    }
    return visible
}
