package com.bhaskar.reelcounter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate

/**
 * A foreground service with an ongoing "N reels today" notification. Its only job is to keep
 * the app's process in the foreground, so battery savers (OnePlus/OxygenOS in particular)
 * don't kill the accessibility service after the first use.
 */
class KeepAliveService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        CountStore.prefs(this).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        return START_STICKY
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notif_title, CountStore.total(this, LocalDate.now())))
        .setContentText(getString(R.string.notif_text, CountStore.limit(this)))
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onDestroy() {
        CountStore.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "counting"
        private const val NOTIFICATION_ID = 1

        /** Starts the service; Android may refuse when the app is in the background, which is fine. */
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start keep-alive service", e)
            }
        }
    }
}
