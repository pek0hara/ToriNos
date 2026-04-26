package com.nostr.torinos

import android.app.Application

class ToriNosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
    }

    companion object {
        lateinit var appContext: ToriNosApp
            private set
    }
}
