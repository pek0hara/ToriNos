package com.nostr.torinos.network

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun deleteLegacyChannelCacheDatabase() {
    val fileManager = NSFileManager.defaultManager
    val documentDirectory = fileManager
        .URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        ?.path
        ?: return
    val base = "$documentDirectory/torinos_channel_cache.db"
    listOf(base, "$base-wal", "$base-shm", "$base-journal", "$base.lck").forEach { path ->
        if (fileManager.fileExistsAtPath(path)) fileManager.removeItemAtPath(path, error = null)
    }
}
