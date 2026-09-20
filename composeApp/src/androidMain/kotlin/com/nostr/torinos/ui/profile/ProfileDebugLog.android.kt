package com.nostr.torinos.ui.profile

import android.util.Log

internal actual fun profileDebugLog(message: String) {
    // JVMユニットテストではandroid.util.Logがモックされておらず未モック例外を投げるため、
    // コンパイル先が本物のAndroidランタイムでない場合はテストを壊さないよう握りつぶす。
    runCatching { Log.d("ProfileDebug", message) }
}
