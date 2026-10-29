package com.miaocun.app

import android.app.Application
import com.miaocun.app.core.Notifications
import com.miaocun.app.core.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.get(this)
        Notifications.createChannels(this)
    }
}
