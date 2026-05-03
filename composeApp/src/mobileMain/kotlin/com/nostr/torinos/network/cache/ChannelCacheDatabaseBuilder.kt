package com.nostr.torinos.network.cache

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal expect fun createChannelCacheDatabaseBuilder(): RoomDatabase.Builder<ChannelCacheDatabase>

internal fun createChannelCacheDatabase(): ChannelCacheDatabase =
    createChannelCacheDatabaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
