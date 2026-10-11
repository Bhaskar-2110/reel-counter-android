package com.bhaskar.reelcounter

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Every Rokoreels screen: applies the chosen theme (Royal has its own), slides new screens in
 * from the right and old ones back (the design's push and pop), and redraws itself when the
 * theme was changed on another screen.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var appliedTheme: AppTheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = CountStore.theme(this)
        if (appliedTheme == AppTheme.ROYAL) setTheme(R.style.Theme_ReelCounter_Royal)
        super.onCreate(savedInstanceState)
        // Home opens from the splash theme, whose window background would stay otherwise
        // (Royal's gradient went missing behind Home).
        val bg = android.util.TypedValue()
        if (theme.resolveAttribute(R.attr.rrScreenBg, bg, true) && bg.resourceId != 0) {
            // A solid strip behind the status bar, so the camera hole doesn't sit on Royal's
            // bright gradient (Android 15 draws every app behind the status bar).
            val screen = getDrawable(bg.resourceId)
            val strip = android.graphics.drawable.ColorDrawable(attrColor(R.attr.rrBg))
            val layers = android.graphics.drawable.LayerDrawable(arrayOf(strip, screen))
            layers.setLayerInset(1, 0, statusBarHeight(), 0, 0)
            window.setBackgroundDrawable(layers)
        }
    }

    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else Ui.dp(this, 32f)
    }

    override fun onResume() {
        super.onResume()
        // Dark ↔ Royal keeps the same night mode, so Android won't recreate the screen itself.
        if (appliedTheme != null && appliedTheme != CountStore.theme(this)) recreate()
    }

    /** Opens [intent] with the push animation. */
    fun push(intent: Intent) {
        startActivity(intent)
        transition(R.anim.push_in, R.anim.push_out)
    }

    override fun finish() {
        super.finish()
        transition(R.anim.pop_in, R.anim.pop_out)
    }

    // Still honoured on Android 14 and 15, and the same call on every version.
    @Suppress("DEPRECATION")
    private fun transition(enter: Int, exit: Int) = overridePendingTransition(enter, exit)
}
