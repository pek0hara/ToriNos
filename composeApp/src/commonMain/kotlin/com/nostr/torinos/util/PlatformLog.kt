package com.nostr.torinos.util

expect fun platformLog(message: String)

fun appLog(message: String) {
    platformLog(message)
}
