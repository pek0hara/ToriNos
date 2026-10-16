package com.nostr.torinos.util

import android.util.Log

private const val TAG = "ToriNosRefactor"
internal actual fun isRefactorTraceEnabled(): Boolean = Log.isLoggable(TAG, Log.DEBUG)
internal actual fun writeRefactorTrace(message: String) { Log.d(TAG, message) }
