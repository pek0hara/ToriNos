package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object StatusStore{
    private const val STATUS_VISIBLE_KEY = "status_visible"
    private val _statusVisible = MutableStateFlow(false)
    val statusVisible: StateFlow<Boolean> = _statusVisible.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun saveStatusVisible(visible: Boolean) {
        scope.launch{
            LocalSettingsStorage.putString(STATUS_VISIBLE_KEY, visible.toString())
        }
    }
    fun loadStatusVisible(){
        scope.launch{
            LocalSettingsStorage.getString(STATUS_VISIBLE_KEY)?.toBoolean()?.let{
                _statusVisible.value = it
            }
        }
    }
}