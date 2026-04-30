package com.nostr.torinos.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nostr.torinos.ToriNosApp
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore(name = "torinos_settings")

actual object LocalSettingsStorage {
    private val context get() = ToriNosApp.appContext

    actual suspend fun getString(key: String): String? {
        val prefs = runCatching { context.settingsDataStore.data.first() }.getOrElse { emptyPreferences() }
        return prefs[stringPreferencesKey(key)]
    }

    actual suspend fun putString(key: String, value: String?) {
        context.settingsDataStore.edit { prefs ->
            val prefKey = stringPreferencesKey(key)
            if (value == null) {
                prefs.remove(prefKey)
            } else {
                prefs[prefKey] = value
            }
        }
    }
}
