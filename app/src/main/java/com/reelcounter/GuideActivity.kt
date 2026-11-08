package com.reelcounter

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Rational
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

/**
 * Onboarding step 4's floating setup guide: opens Accessibility settings and shows [GuideView]
 * in a picture-in-picture window over it, with previous / pause / next buttons. It watches for
 * Rokoreels' service being switched on; then it says so for 1.5 s and brings onboarding back to
 * the front (on its "counting is on" state) and closes. Expanding the window without turning it
 * on also returns to onboarding, which then shows "Counting is still off".
 *
 * Its own task (manifest taskAffinity), so the PiP window and Settings don't stack on onboarding.
 * Not exported; the PiP buttons reach it through a receiver that isn't exported either.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var guide: GuideView
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var succeeded = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = checkEnabled()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PREV -> guide.prev()
                ACTION_NEXT -> guide.next()
                ACTION_PLAY -> guide.togglePause()
                else -> return
            }
            updateParams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guide = GuideView(this)
        setContentView(guide)
        // Recreated (rare: we handle size changes ourselves): don't open Settings a second time.
        started = savedInstanceState != null
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(ACTION_PREV)
            addAction(ACTION_PLAY)
            addAction(ACTION_NEXT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            guide.post { start() }
        }
        checkEnabled()
    }

    /** Shrink into a PiP window, then open Settings under it. No PiP: just Settings. */
    private fun start() {
        if (isFinishing || succeeded) return
        val pip = try {
            enterPictureInPictureMode(params())
        } catch (_: Exception) {
            false // PiP turned off for the app, or not supported here
        }
        try {
            MainActivity.openAccessibilitySettings(this)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (!pip) finish()
    }

    private fun checkEnabled() {
        if (succeeded || isFinishing || !MainActivity.isServiceEnabled(this)) return
        succeeded = true
        guide.showDone()
        updateParams()
        handler.postDelayed({ backToOnboarding() }, SUCCESS_MS)
    }

    private fun backToOnboarding() {
        if (isFinishing) return
        // Allowed from the background: our window is visible (PiP) or, once counting is on,
        // the system has bound our Accessibility service.
        startActivity(
            Intent(this, OnboardingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode || succeeded) return
        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // The window was swiped away or closed (stopped, not shown): leave the user in Settings.
            finish()
        } else {
            // Expanded to full screen: back to onboarding, which says counting is still off.
            backToOnboarding()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    private fun updateParams() {
        if (!isInPictureInPictureMode) return
        try {
            setPictureInPictureParams(params())
        } catch (_: Exception) {
        }
    }

    private fun params(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(7, 10))
        .setActions(if (succeeded) emptyList() else actions())
        .build()

    private fun actions(): List<RemoteAction> {
        fun action(icon: Int, title: Int, act: String, code: Int) = RemoteAction(
            Icon.createWithResource(this, icon), getString(title), getString(title),
            PendingIntent.getBroadcast(this, code, Intent(act).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val play = if (guide.paused) action(R.drawable.ic_play, R.string.guide_play, ACTION_PLAY, 2)
        else action(R.drawable.ic_pause_fill, R.string.guide_pause, ACTION_PLAY, 2)
        return listOf(action(R.drawable.ic_prev, R.string.guide_prev, ACTION_PREV, 1), play, action(R.drawable.ic_next, R.string.guide_next, ACTION_NEXT, 3))
            .take(maxNumPictureInPictureActions.coerceAtLeast(0))
    }

    companion object {
        private const val ACTION_PREV = "com.reelcounter.guide.PREV"
        private const val ACTION_PLAY = "com.reelcounter.guide.PLAY"
        private const val ACTION_NEXT = "com.reelcounter.guide.NEXT"
        private const val SUCCESS_MS = 1500L

        /** Opens Accessibility settings, with the floating guide when picture-in-picture is allowed. */
        fun open(activity: Activity) {
            if (pipAllowed(activity)) {
                try {
                    activity.startActivity(Intent(activity, GuideActivity::class.java))
                    return
                } catch (_: Exception) {
                }
            }
            MainActivity.openAccessibilitySettings(activity)
        }

        /** PiP exists on this phone and the user hasn't switched it off for Rokoreels. */
        fun pipAllowed(ctx: Context): Boolean {
            if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
            val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return true
            val mode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), ctx.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), ctx.packageName)
                }
            } catch (_: Exception) {
                AppOpsManager.MODE_ALLOWED
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
