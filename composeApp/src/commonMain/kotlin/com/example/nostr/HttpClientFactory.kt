package com.example.nostr

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
