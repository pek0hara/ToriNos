package com.nostr.torinos.network.cache

import androidx.room.Room
import androidx.room.RoomDatabase
import com.nostr.torinos.ToriNosApp

internal actual fun createChannelCacheDatabaseBuilder(): RoomDatabase.Builder<ChannelCacheDatabase> {
    val context = ToriNosApp.appContext.applicationContext
    val dbFile = context.getDatabasePath("torinos_channel_cache.db")
    return Room.databaseBuilder<ChannelCacheDatabase>(
        context = context,
        name = dbFile.absolutePath,
    )
}
