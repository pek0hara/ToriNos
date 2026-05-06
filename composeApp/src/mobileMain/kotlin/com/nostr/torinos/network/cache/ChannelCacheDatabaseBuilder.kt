package com.nostr.torinos.network.cache

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal expect fun createChannelCacheDatabaseBuilder(): RoomDatabase.Builder<ChannelCacheDatabase>

internal fun createChannelCacheDatabase(): ChannelCacheDatabase =
    createChannelCacheDatabaseBuilder()
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
