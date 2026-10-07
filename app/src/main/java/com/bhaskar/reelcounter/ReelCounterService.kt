package com.bhaskar.reelcounter

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isInvisible
import com.google.android.material.button.MaterialButton
import java.time.LocalDate

/**
 * Counts one reel each time a full-screen short-video feed settles on a new video, shows a
 * live counter badge over Reels/Shorts, pauses social app opens after the free ones, and
 * keeps the user out of social apps once the daily limit is reached.
 *
 * A single swipe fires many scroll events, so counting waits until scrolling has been quiet
 * for [SETTLE_MS], then compares the pager's item index with the last one counted. Scrolls in
 * smaller views (comment sheets, share menus) are ignored by the full-screen size check.
 *
 * Every entry point catches its own exceptions: an uncaught one would crash the service, and
 * some phones then leave it switched off until the user turns it back on.
 */
class ReelCounterService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSwipe: Runnable? = null
    private var screenCheckQueued = false
    private val lastIndex = HashMap<Platform, Int>()
    private var lastCountAt = 0L
    private var lastBlockAt = 0L

    private val recentSwipes = java.util.ArrayDeque<Long>()
    private var electricUntil = 0L
    private var angryHoldUntil = 0L

    private lateinit var windowManager: WindowManager
    private var island: IslandView? = null
    private var blurPane: Dialog? = null
    private var blockView: View? = null
    private var pauseView: View? = null
    private var pauseTick: Runnable? = null
    private var foregroundPkg: String? = null

    /** Re-checks the screen while the island is up, so it leaves with the user and its mood stays current. */
    private val pollWhileVisible = object : Runnable {
        override fun run() {
            checkScreen()
            if (island != null) handler.postDelayed(this, POLL_MS)
        }
    }
    private val recheck = Runnable { checkScreen() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        safely("event") { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val platform = Platform.forPackage(pkg)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onForegroundChanged(pkg, platform)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (platform != null) queueScreenCheck()
                else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != SYSTEM_UI) hideBadge()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (platform == null || !isFullScreen(event.source)) return
                val index = event.fromIndex
                pendingSwipe?.let { handler.removeCallbacks(it) }
                val settle = Runnable { safely("swipe") { onSwipeSettled(platform, index) } }
                pendingSwipe = settle
                handler.postDelayed(settle, SETTLE_MS)
            }
        }
    }

    private fun onSwipeSettled(platform: Platform, index: Int) {
        if (shortsPlatformOnScreen() != platform) return
        if (index >= 0 && lastIndex[platform] == index) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCountAt < MIN_GAP_MS) return

        lastIndex[platform] = index
        lastCountAt = now
        val total = CountStore.increment(this)
        val limit = CountStore.limit(this)
        if (!CountStore.blockAtLimit(this) && (total == limit || (total > limit && (total - limit) % 10 == 0))) {
            Toast.makeText(this, getString(R.string.limit_toast, total, limit), Toast.LENGTH_LONG).show()
        }

        // Fast scrolling (several reels within a few seconds) makes the eyes electric for a while.
        recentSwipes.addLast(now)
        while (recentSwipes.isNotEmpty() && now - recentSwipes.first() > FAST_WINDOW_MS) recentSwipes.removeFirst()
        if (recentSwipes.size >= FAST_SWIPES) electricUntil = now + ELECTRIC_HOLD_MS

        // Reaching the limit: let the island be angry for a moment before blocking.
        if (total == limit && CountStore.blockAtLimit(this)) {
            angryHoldUntil = now + ANGRY_HOLD_MS
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, ANGRY_HOLD_MS + 50)
        }
        checkScreen()
    }

    private fun queueScreenCheck() {
        if (screenCheckQueued) return
        screenCheckQueued = true
        handler.postDelayed({
            screenCheckQueued = false
            checkScreen()
        }, SCREEN_CHECK_MS)
    }

    private fun isOverLimit(): Boolean =
        CountStore.blockAtLimit(this) && CountStore.total(this, LocalDate.now()) >= CountStore.limit(this)

    // Opening a social app

    /**
     * Tracks which app is in front. Arriving in a social app from a different app counts as
     * one open. Over the limit, the app is closed right away; otherwise, after the free opens,
     * each open shows the pause screen for longer. Keyboards, the notification shade, system
     * dialogs and share sheets don't count as leaving the app.
     */
    private fun onForegroundChanged(pkg: String, platform: Platform?) {
        if (pkg in PASS_THROUGH_PACKAGES || isKeyboard(pkg)) return
        val previous = foregroundPkg
        foregroundPkg = pkg
        if (platform == null) {
            hidePauseScreen()
            return
        }
        if (previous == pkg) return

        // Over the limit, apps that are nothing but short videos stay closed. Other apps open
        // normally; only their Reels/Shorts/Spotlight screen is blocked (see checkScreen).
        if (isOverLimit() && platform.isWholeApp) {
            block(platform)
            return
        }
        val opens = CountStore.incrementOpens(this)
        val seconds = CountStore.pauseSecondsFor(opens)
        if (seconds > 0 && CountStore.pauseOnOpen(this)) showPauseScreen(opens, seconds)
    }

    private fun isKeyboard(pkg: String): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun showPauseScreen(opens: Int, seconds: Int) {
        hidePauseScreen()
        hideBadge()
        val themed = ContextThemeWrapper(this, R.style.Theme_ReelCounter)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_pause, null)
        val countdown = view.findViewById<TextView>(R.id.pauseCountdown)
        val continueButton = view.findViewById<MaterialButton>(R.id.pauseContinue)
        view.findViewById<TextView>(R.id.pauseMessage).text = getString(R.string.pause_message, opens)
        view.findViewById<View>(R.id.pauseLeave).setOnClickListener {
            hidePauseScreen()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        continueButton.isEnabled = false
        continueButton.setOnClickListener {
            hidePauseScreen()
            queueScreenCheck()
        }

        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    countdown.text = left.toString()
                    continueButton.text = getString(R.string.pause_continue_in, left)
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    countdown.isInvisible = true
                    continueButton.isEnabled = true
                    continueButton.setText(R.string.pause_continue)
                }
            }
        }
        windowManager.addView(view, fullScreenParams())
        pauseView = view
        pauseTick = tick
        tick.run()
    }

    private fun hidePauseScreen() {
        pauseTick?.let { handler.removeCallbacks(it) }
        pauseTick = null
        pauseView?.let { removeOverlay(it) }
        pauseView = null
    }

    // What's on screen

    /** Blocks over the limit, otherwise shows the badge while a short-video feed is on screen. */
    private fun checkScreen() = safely("check") {
        if (pauseView != null) {
            hideBadge()
            return@safely
        }
        val root = rootInActiveWindow
        val front = root?.packageName?.toString()?.let(Platform::forPackage)
        if (root == null || front == null) {
            hideBadge()
            return@safely
        }
        if (!isShortsScreen(front, root)) {
            hideBadge()
            return@safely
        }
        val total = CountStore.total(this, LocalDate.now())
        val limit = CountStore.limit(this)
        when {
            // Over the limit on Reels/Shorts: stay angry for a moment, then send the user out.
            isOverLimit() && island != null && SystemClock.elapsedRealtime() < angryHoldUntil -> showIsland(total, limit)
            isOverLimit() -> block(front)
            CountStore.showBadge(this) -> showIsland(total, limit)
            else -> hideBadge()
        }
    }

    private fun shortsPlatformOnScreen(): Platform? {
        val root = rootInActiveWindow ?: return null
        val platform = root.packageName?.toString()?.let(Platform::forPackage) ?: return null
        return if (isShortsScreen(platform, root)) platform else null
    }

    private fun isShortsScreen(platform: Platform, root: AccessibilityNodeInfo): Boolean {
        if (platform.isWholeApp) return true
        return platform.shortsViewIds.any { id ->
            root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
        } || hasLargeViewWithIdHint(root, platform.shortsIdHints)
    }

    /**
     * Walks the visible view tree (up to [MAX_NODES_SCANNED] nodes) looking for a view whose
     * resource ID contains one of [hints] and that fills most of the screen, so a small tab
     * button with the same name doesn't count.
     */
    private fun hasLargeViewWithIdHint(root: AccessibilityNodeInfo, hints: List<String>): Boolean {
        if (hints.isEmpty()) return false
        val minHeight = resources.displayMetrics.heightPixels * 0.5f
        val bounds = Rect()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_NODES_SCANNED) {
            val node = queue.poll() ?: break
            scanned++
            if (!node.isVisibleToUser) continue
            val id = node.viewIdResourceName?.lowercase()
            if (id != null && hints.any { it in id }) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() >= minHeight) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun isFullScreen(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.height() >= resources.displayMetrics.heightPixels * 0.6f
    }

    // Blocking

    /**
     * Takes the user out of the short-video screen and shows the limit message. Back leaves
     * Reels/Shorts/Spotlight but keeps the rest of the app; apps that are only short videos
     * go to the home screen.
     */
    private fun block(platform: Platform) {
        hideBadge()
        hidePauseScreen()
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < BLOCK_GAP_MS) {
            // Still in the app right after the last attempt: try again once the gap passes.
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, BLOCK_GAP_MS)
            return
        }
        lastBlockAt = now
        performGlobalAction(if (platform.isWholeApp) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        showBlockScreen(CountStore.total(this, LocalDate.now()), CountStore.limit(this))
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, BLOCK_GAP_MS)
    }

    private fun showBlockScreen(total: Int, limit: Int) {
        val view = blockView ?: run {
            val themed = ContextThemeWrapper(this, R.style.Theme_ReelCounter)
            val v = LayoutInflater.from(themed).inflate(R.layout.overlay_block, null)
            v.findViewById<View>(R.id.blockClose).setOnClickListener { hideBlockScreen() }
            v.findViewById<EyesView>(R.id.blockEyes).setMood(Mood.ANGRY)
            windowManager.addView(v, fullScreenParams())
            blockView = v
            v
        }
        view.findViewById<TextView>(R.id.blockMessage).text = getString(R.string.block_message, total, limit)
    }

    private fun hideBlockScreen() {
        blockView?.let { removeOverlay(it) }
        blockView = null
    }

    // Dynamic island

    private fun showIsland(total: Int, limit: Int) {
        val view = island ?: createIsland().also {
            island = it
            handler.postDelayed(pollWhileVisible, POLL_MS)
        }
        val mood = when {
            total >= limit -> Mood.ANGRY
            SystemClock.elapsedRealtime() < electricUntil -> Mood.ELECTRIC
            else -> Mood.CURIOUS
        }
        view.setState(total, mood, total.toFloat() / limit)
    }

    /**
     * Adds the island's overlay window at the very top edge of the screen. With a centered
     * camera hole, the circle drops just enough for the eyes to sit below the camera, which
     * then reads as the circle's forehead. Where Android supports it, a blur window goes in
     * first, right behind the circle, so the video under the glass is frosted.
     */
    private fun createIsland(): IslandView {
        val dp = resources.displayMetrics.density
        val size = IslandView.BASE_D_DP * dp
        var centerY = 4 * dp + size / 2f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val screenW = metrics.bounds.width()
            metrics.windowInsets.displayCutout?.boundingRects
                ?.firstOrNull { it.top < 80 * dp && kotlin.math.abs(it.exactCenterX() - screenW / 2f) < screenW * 0.15f }
                ?.let { camera ->
                    // Eyes are drawn 6% of the size above center and are 25% of it tall.
                    val eyesTop = centerY - size * 0.06f - size * 0.125f
                    val needed = camera.bottom + 3 * dp - eyesTop
                    if (needed > 0) centerY += needed
                }
        }
        val top = centerY - size / 2f

        val blurred = showBlurPane(top.toInt())
        val view = IslandView(this, centerY).apply { frosted = blurred }
        val params = WindowManager.LayoutParams(
            (320 * dp).toInt(),
            (centerY + size / 2f + 50 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            OVERLAY_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        return view
    }

    /**
     * Shows a round window that blurs whatever is behind it (Android 12+, when the phone has
     * window blur turned on). Its blur fades in as the island grows. Returns false when blur
     * isn't available.
     */
    private fun showBlurPane(top: Int): Boolean {
        hideBlurPane()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !windowManager.isCrossWindowBlurEnabled) return false
        val dp = resources.displayMetrics.density
        val w = (IslandView.BASE_D_DP * dp).toInt()
        val h = w
        val dialog = Dialog(this, R.style.Theme_ReelCounter_BlurPane)
        dialog.setContentView(View(this))
        val window = dialog.window ?: return false
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        window.addFlags(OVERLAY_FLAGS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // The blur takes its rounded shape from this background's corners.
        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = h / 2f
            setColor(Color.TRANSPARENT)
        })
        window.setLayout(w, h)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply {
            y = top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setBackgroundBlurRadius(0)
        return try {
            dialog.show()
            blurPane = dialog
            ValueAnimator.ofInt(0, (25 * dp).toInt()).apply {
                startDelay = 120
                duration = 350
                addUpdateListener { if (blurPane === dialog) window.setBackgroundBlurRadius(it.animatedValue as Int) }
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show blur", e)
            false
        }
    }

    private fun hideBlurPane() {
        val pane = blurPane ?: return
        blurPane = null
        try {
            pane.dismiss()
        } catch (e: Exception) {
            // Already gone.
        }
    }

    /** Animates the island back into a dot, then removes its window. */
    private fun hideBadge() {
        handler.removeCallbacks(pollWhileVisible)
        val leaving = island ?: return
        island = null
        hideBlurPane()
        leaving.dismiss { removeOverlay(leaving) }
    }

    // Helpers

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun removeOverlay(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached; nothing to remove.
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Failed during $what", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBadge()
        hideBlurPane()
        hideBlockScreen()
        hidePauseScreen()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ReelCounterService"
        const val SYSTEM_UI = "com.android.systemui"

        /** Island windows: never take touches or focus, and may sit in the status bar area. */
        const val OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** Windows that appear on top of an app without the user leaving it. */
        val PASS_THROUGH_PACKAGES = setOf(
            SYSTEM_UI,
            "android",
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        const val SETTLE_MS = 450L
        const val MIN_GAP_MS = 700L
        const val SCREEN_CHECK_MS = 300L
        const val POLL_MS = 1500L
        const val BLOCK_GAP_MS = 1500L
        const val MAX_NODES_SCANNED = 600
        const val FAST_SWIPES = 3
        const val FAST_WINDOW_MS = 6000L
        const val ELECTRIC_HOLD_MS = 3000L
        const val ANGRY_HOLD_MS = 1800L
    }
}
