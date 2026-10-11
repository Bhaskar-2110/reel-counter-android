package com.reelcounter

import android.app.Application

/** Applies the user's light/dark theme choice before any screen opens. */
class ReelCounterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Analytics.init(this)
        AppTheme.applyNightMode(this)
    }
}
