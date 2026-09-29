package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.semantics
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsSwitchControlRunning
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilitySwitchControlStatusDidChangeNotification
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification

@Composable
internal actual fun Modifier.avoidStaleAccessibilityFocus(): Modifier {
    val assistiveTechnologyRunning = rememberAssistiveTechnologyRunning()
    return if (assistiveTechnologyRunning) this else semantics { focused = false }
}

private fun isAssistiveTechnologyRunning(): Boolean =
    UIAccessibilityIsVoiceOverRunning() || UIAccessibilityIsSwitchControlRunning()

@Composable
private fun rememberAssistiveTechnologyRunning(): Boolean {
    var running by remember { mutableStateOf(isAssistiveTechnologyRunning()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            UIAccessibilityVoiceOverStatusDidChangeNotification,
            UIAccessibilitySwitchControlStatusDidChangeNotification,
        ).map { name ->
            center.addObserverForName(
                name = name,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> running = isAssistiveTechnologyRunning() }
        }
        onDispose { observers.forEach(center::removeObserver) }
    }
    return running
}
