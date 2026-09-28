package com.nostr.torinos.network

// Web版は旧チャンネルキャッシュDBを作ったことがないため、削除するものがない。
internal actual suspend fun deleteLegacyChannelCacheDatabase() = Unit
