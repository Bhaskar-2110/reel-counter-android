package com.bhaskar.reelcounter

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the user's light/dark theme choice before any screen opens. */
class ReelCounterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(CountStore.themeMode(this))
    }
}
