package com.nostr.torinos.network

import com.nostr.torinos.ToriNosApp
import java.io.File

internal actual suspend fun deleteLegacyChannelCacheDatabase() {
    val context = ToriNosApp.appContext.applicationContext
    val dbFile = context.getDatabasePath(LEGACY_CHANNEL_CACHE_DB)
    // deleteDatabase は -journal / -wal / -shm も合わせて削除する。bundled SQLite の .lck は別途消す。
    if (dbFile.exists()) context.deleteDatabase(LEGACY_CHANNEL_CACHE_DB)
    File(dbFile.path + ".lck").takeIf { it.exists() }?.delete()
}

private const val LEGACY_CHANNEL_CACHE_DB = "torinos_channel_cache.db"
