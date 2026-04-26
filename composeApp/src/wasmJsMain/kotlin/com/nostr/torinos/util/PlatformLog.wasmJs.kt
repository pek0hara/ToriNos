package com.nostr.torinos.util

actual fun platformLog(message: String) {
    kotlin.js.console.log(message)
}
