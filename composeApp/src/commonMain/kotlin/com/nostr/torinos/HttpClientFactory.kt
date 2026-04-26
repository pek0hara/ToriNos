package com.nostr.torinos

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
