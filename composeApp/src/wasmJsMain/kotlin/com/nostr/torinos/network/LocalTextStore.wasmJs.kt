package com.nostr.torinos.network

/** Web版はキャッシュを永続化しない。 */
internal actual fun platformCacheTextStore(fileName: String): LocalTextStore? = null
