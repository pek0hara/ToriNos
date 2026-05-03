package com.nostr.torinos.network.cache

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun createChannelCacheDatabaseBuilder(): RoomDatabase.Builder<ChannelCacheDatabase> {
    val documentDirectory = NSFileManager.defaultManager
        .URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        ?.path
        ?: "."
    return Room.databaseBuilder<ChannelCacheDatabase>(
        name = "$documentDirectory/torinos_channel_cache.db",
    )
}
