package com.bhaskar.reelcounter

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import java.time.LocalDate
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Counts one reel each time a full-screen short-video feed settles on a new video, shows the
 * reel island (animated eyes with the count and a session timer) while a supported app is open,
 * pauses social app opens after the free ones, and blocks Reels/Shorts once the overall or the
 * app's own daily limit is reached.
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
    /** Apps whose Reels the user swiped on while blocked, this visit (ends the first-reel grace). */
    private val scrolledWhileBlocked = HashSet<Platform>()

    /** Furthest video reached in the current visit to each app's Reels/Shorts pager. */
    private val furthestIndex = HashMap<Platform, Int>()
    private var lastCountAt = 0L
    private var lastBlockAt = 0L

    private var angryHoldUntil = 0L

    /** Until when the counter should plead the next time it shows (elapsedRealtime). */
    private var pleadUntil = 0L
    private var sessionStart = 0L
    private var timedPlatform: Platform? = null
    private var timedSince = 0L
    private var lastLength = -1
    private var lastSignature: String? = null
    private var pendingSignature: String? = null
    private val seenSignatures = LinkedHashSet<String>()
    private var lastPosition = -1

    private lateinit var windowManager: WindowManager
    private var island: IslandView? = null
    private var islandTouch: View? = null
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

    /** With the screen off, the user isn't using the app: stop the timer and hide the island. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = safely("screen off") {
            stopTiming()
            sessionStart = 0L
            hideBadge()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        CountStore.markServiceStarted(this)
        // After a restart (an update, or the phone killing the app), the app already on screen
        // isn't a new open.
        foregroundPkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        KeepAliveService.start(this)
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // Time in each app today

    private fun startTiming(platform: Platform) {
        if (timedPlatform == platform) return
        stopTiming()
        timedPlatform = platform
        timedSince = System.currentTimeMillis()
    }

    /** Saves the time since the last save; called on leaving and every [TIME_SAVE_MS] while in an app. */
    private fun flushTime() {
        val platform = timedPlatform ?: return
        val now = System.currentTimeMillis()
        CountStore.addAppTime(this, platform, timedSince, now)
        timedSince = now
    }

    private fun stopTiming() {
        flushTime()
        timedPlatform = null
    }

    /** Today's total time in [platform], including the part not saved yet. */
    private fun todayTimeMs(platform: Platform): Long =
        CountStore.appTime(this, LocalDate.now(), platform) +
            if (timedPlatform == platform) System.currentTimeMillis() - timedSince else 0L

    /** How much of [platform]'s daily time limit is used (1 = used up), or 0 without a limit. */
    private fun timeProgress(platform: Platform): Float {
        val minutes = CountStore.appTimeLimitMinutes(this, platform)
        return if (minutes <= 0) 0f else todayTimeMs(platform) / (minutes * 60_000f)
    }

    private val timeWarned = HashMap<Platform, LocalDate>()

    /** Once a day per app, a buzz and a heads-up when 90% of its time limit is used. */
    private fun warnNearTimeLimit(platform: Platform) {
        val progress = timeProgress(platform)
        val today = LocalDate.now()
        if (progress < NEAR_LIMIT || progress >= 1f || timeWarned[platform] == today) return
        timeWarned[platform] = today
        val left = ((CountStore.appTimeLimitMinutes(this, platform) * 60_000L - todayTimeMs(platform)) / 60_000L)
            .coerceAtLeast(1L).toInt()
        vibrate(longArrayOf(0, 45, 90, 45))
        Toast.makeText(this, getString(R.string.time_warning, left, platform.shortName), Toast.LENGTH_LONG).show()
        pleadUntil = SystemClock.elapsedRealtime() + PLEAD_NEAR_TIME_MS
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        safely("event") { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (DEBUG_LOG && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(DBG, "WIN pkg=$pkg cls=${event.className} fg=$foregroundPkg")
        }
        if (DEBUG_LOG && (Platform.forPackage(pkg) != null || pkg in DEBUG_PACKAGES) &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val src = event.source
            val r = Rect().also { src?.getBoundsInScreen(it) }
            Log.d(
                DBG, "EV ${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=$pkg cls=${event.className} " +
                    "id=${src?.viewIdResourceName} desc=${src?.contentDescription?.toString()?.take(40)} " +
                    "from=${event.fromIndex} to=${event.toIndex} items=${event.itemCount} dy=${event.scrollDeltaY} h=${r.height()}",
            )
        }
        val platform = Platform.forPackage(pkg)?.takeIf { CountStore.isTracked(this, it) }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onForegroundChanged(pkg, platform)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (platform != null) queueScreenCheck()
                else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != SYSTEM_UI) hideBadge()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Apps tracked by their progress bar or on-screen text are counted in checkScreen.
                if (platform == null || platform.progressBarId != null || platform.signatureRootId != null ||
                    !isFullScreen(event.source)
                ) return
                val index = event.fromIndex
                pendingSwipe?.let { handler.removeCallbacks(it) }
                val settle = Runnable { safely("swipe") { onSwipeSettled(platform, index) } }
                pendingSwipe = settle
                handler.postDelayed(settle, SETTLE_MS)
            }
        }
    }

    /**
     * A swipe settled on video [index] of the pager. Only a video further along than any seen
     * in this visit counts: swiping back to re-watch one doesn't (found on a OnePlus, 8 Oct).
     */
    private fun onSwipeSettled(platform: Platform, index: Int) {
        if (shortsPlatformOnScreen() != platform) return
        if (index >= 0) {
            if (index <= (furthestIndex[platform] ?: -1)) return
            furthestIndex[platform] = index
        }
        // Instagram reports its first video (index 0) as a scroll when Reels opens; that isn't
        // the user swiping on, so it doesn't end the "allow first reel" grace.
        countReel(platform, swipedOn = index != 0)
    }

    /**
     * For apps tracked by their progress bar (see [Platform.progressBarId]): reads "29 seconds
     * of 59 seconds" from the bar and counts a new video when the length changes, or when
     * playback jumps back to the start without the previous video having been near its end (that
     * would just be the video looping). Only the numbers are read, so any language works.
     */
    private fun checkProgress(platform: Platform, root: AccessibilityNodeInfo) {
        val id = platform.progressBarId ?: return
        val bar = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser } ?: return
        val read = readProgress(bar)
        if (DEBUG_LOG) Log.d(DBG, "PROGRESS $read last=$lastLength/$lastPosition")
        val (position, length) = read ?: return
        val previousLength = lastLength
        val previousPosition = lastPosition
        lastLength = length
        lastPosition = position
        if (previousLength < 0) return
        // The bar is only seen every second or two, so "near the end" (where a jump back to the
        // start is just the video looping) is the last 15% or last 6 seconds, whichever is more.
        val nearEnd = minOf(previousLength - 6f, previousLength * 0.85f)
        val newVideo = length != previousLength ||
            (position < previousPosition && position <= 2 && previousPosition < nearEnd)
        if (newVideo) countReel(platform)
    }

    /**
     * For apps that send no scroll events and have no readable progress bar (Snapchat): reads
     * the first few lines of text in the video viewer (creator, sound, like count) and counts a
     * new video when they change. A video looping or pausing keeps the same text, so it isn't
     * counted again.
     */
    private fun checkSignature(platform: Platform, root: AccessibilityNodeInfo) {
        val id = platform.signatureRootId ?: return
        val viewer = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.refresh(); it.isVisibleToUser } ?: return
        val texts = ArrayList<String>()
        collectTexts(viewer, texts, 0)
        if (texts.isEmpty()) return
        val signature = texts.joinToString("|")
        if (DEBUG_LOG) Log.d(DBG, "SIGNATURE $signature (was $lastSignature)")
        val previous = lastSignature
        if (previous == null) {
            lastSignature = signature
            seenSignatures += signature
            return
        }
        if (signature == previous) {
            pendingSignature = null
            return
        }
        // Mid-swipe, Snapchat can briefly show half the old text and half the new; only a text
        // seen on two checks in a row is a new video.
        if (signature != pendingSignature) {
            pendingSignature = signature
            return
        }
        lastSignature = signature
        pendingSignature = null
        // Swiping back to a video already watched in this visit doesn't count again.
        if (!seenSignatures.add(signature)) return
        if (seenSignatures.size > MAX_SEEN_SIGNATURES) seenSignatures.remove(seenSignatures.first())
        countReel(platform)
    }

    /** The first [SIGNATURE_TEXTS] texts in [node]'s subtree, freshly read (see [readProgress]). */
    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (out.size >= SIGNATURE_TEXTS || depth > 12) return
        node.refresh()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (i in 0 until node.childCount) {
            if (out.size >= SIGNATURE_TEXTS) return
            node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
        }
    }

    /** Position and length in seconds from the first description in [node]'s subtree that has them. */
    private fun readProgress(node: AccessibilityNodeInfo, depth: Int = 0): Pair<Int, Int>? {
        // Android caches screen content, and YouTube doesn't announce that the bar's text
        // changed, so without a refresh the same "6 seconds of 15 seconds" comes back forever
        // and no new Short is ever spotted.
        node.refresh()
        node.contentDescription?.toString()?.let { desc ->
            val n = Regex("\\d+").findAll(desc).map { it.value.toInt() }.toList()
            when (n.size) {
                2 -> return n[0] to n[1]
                4 -> return (n[0] * 60 + n[1]) to (n[2] * 60 + n[3])
                6 -> return (n[0] * 3600 + n[1] * 60 + n[2]) to (n[3] * 3600 + n[4] * 60 + n[5])
            }
        }
        if (depth > 4) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> readProgress(child, depth + 1)?.let { return it } }
        }
        return null
    }

    /** Adds one reel for [platform] and reacts: eye moods, a buzz near the limit, blocking at it. */
    private fun countReel(platform: Platform, swipedOn: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (DEBUG_LOG) Log.d(DBG, "COUNT? $platform gap=${now - lastCountAt}")
        if (now - lastCountAt < MIN_GAP_MS) return
        lastCountAt = now
        // Swiping on while blocked ends the "first reel allowed" grace (see checkScreen).
        if (swipedOn && Blocking.reason(this, platform) != null) scrolledWhileBlocked += platform
        val before = CountStore.limitProgress(this, platform)
        CountStore.increment(this, platform)
        val after = CountStore.limitProgress(this, platform)
        if (!CountStore.blockAtLimit(this) && before < 1f && after >= 1f) {
            val total = CountStore.total(this, LocalDate.now())
            Toast.makeText(this, getString(R.string.limit_toast, total, CountStore.limit(this)), Toast.LENGTH_LONG).show()
        }

        // Iris hops; she decides herself whether that was a surprise (a new band, the limit)
        // or a binge (3 reels in 6 s: electrocuted).
        island?.onReel(maxOf(after, timeProgress(platform)))

        if (after >= 1f) CountStore.markLimitHit(this, LocalDate.now())

        // Near the limit, every reel gives a short buzz; reaching it gives a long one.
        when {
            before < 1f && after >= 1f -> vibrate(longArrayOf(0, 350, 120, 350))
            after >= NEAR_LIMIT -> vibrate(longArrayOf(0, 45, 90, 45))
        }

        // Reaching a limit: let the island be angry for a moment before blocking.
        if (before < 1f && after >= 1f && CountStore.blockAtLimit(this)) {
            angryHoldUntil = now + ANGRY_HOLD_MS
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, ANGRY_HOLD_MS + 50)
        }
        checkScreen()
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun queueScreenCheck() {
        if (screenCheckQueued) return
        screenCheckQueued = true
        handler.postDelayed({
            screenCheckQueued = false
            checkScreen()
        }, SCREEN_CHECK_MS)
    }

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
            sessionStart = 0L
            stopTiming()
            return
        }
        if (previous == pkg) return
        sessionStart = SystemClock.elapsedRealtime()
        startTiming(platform)

        // Time limit used up: the whole app stays closed until midnight.
        if (timeProgress(platform) >= 1f) {
            block(platform, timeUp = true)
            return
        }

        // Over the limit, apps that are nothing but short videos stay closed. Other apps open
        // normally; only their Reels/Shorts/Spotlight screen is blocked (see checkScreen).
        Blocking.reason(this, platform)?.takeIf { platform.isWholeApp }?.let {
            block(platform, reason = it)
            return
        }
        val opens = CountStore.incrementOpens(this)
        if (DEBUG_LOG) Log.d(DBG, "OPEN #$opens $platform from previous=$previous")
        val seconds = CountStore.pauseSecondsFor(opens)
        if (seconds > 0 && CountStore.pauseOnOpen(this)) showPauseScreen(opens, seconds, platform)
    }

    private fun isKeyboard(pkg: String): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    /**
     * The pause before opening, as in the design: Iris pleading inside a slowly turning ring,
     * "Open #7 today.", a big Close app button and a quiet Continue anyway that unlocks after
     * the countdown.
     */
    private fun showPauseScreen(opens: Int, seconds: Int, platform: Platform) {
        hidePauseScreen()
        hideBadge()
        val themed = ContextThemeWrapper(this, R.style.Theme_ReelCounter)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_pause, null)
        val countdown = view.findViewById<TextView>(R.id.pauseCountdown)
        val continueButton = view.findViewById<MaterialButton>(R.id.pauseContinue)
        view.findViewById<TextView>(R.id.pauseTitle).text = getString(R.string.pause_title_opens, opens)
        view.findViewById<TextView>(R.id.pauseMessage).text = getString(R.string.pause_take_breath, platform.shortName)
        view.findViewById<EyesView>(R.id.pauseEyes).setMood(Mood.PLEADING)
        view.findViewById<View>(R.id.pauseLeave).setOnClickListener {
            hidePauseScreen()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        continueButton.isEnabled = false
        continueButton.setOnClickListener {
            hidePauseScreen()
            // The counter comes back pleading: "are you sure?"
            pleadUntil = SystemClock.elapsedRealtime() + PLEAD_AFTER_PAUSE_MS
            queueScreenCheck()
        }

        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    countdown.text = getString(R.string.pause_countdown_seconds, left)
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

    /**
     * Shows the island while a supported app is in front, and blocks its Reels/Shorts screen
     * once a limit is reached (after a moment of angry eyes when the limit was just hit).
     */
    private fun checkScreen() = safely("check") {
        if (pauseView != null) {
            hideBadge()
            return@safely
        }
        val root = rootInActiveWindow
        val front = root?.packageName?.toString()?.let(Platform::forPackage)?.takeIf { CountStore.isTracked(this, it) }
        if (root == null || front == null) {
            hideBadge()
            return@safely
        }
        if (sessionStart == 0L) sessionStart = SystemClock.elapsedRealtime()
        CountStore.markSeen(this, front)
        startTiming(front)
        if (System.currentTimeMillis() - timedSince > TIME_SAVE_MS) flushTime()
        if (timeProgress(front) >= 1f) {
            block(front, timeUp = true)
            return@safely
        }
        warnNearTimeLimit(front)
        val onShorts = isShortsScreen(front, root)
        // Leaving the pager resets its video numbering, so start afresh next time.
        if (!onShorts) furthestIndex.remove(front)
        if (DEBUG_LOG) Log.d(DBG, "CHECK $front onShorts=$onShorts block=${Blocking.reason(this, front)} time=${todayTimeMs(front) / 1000}s badge=${island != null}")
        if (onShorts) checkProgress(front, root) else lastLength = -1
        if (onShorts) checkSignature(front, root) else {
            lastSignature = null
            pendingSignature = null
            seenSignatures.clear()
        }
        val reason = Blocking.reason(this, front)
        if (!onShorts) scrolledWhileBlocked.remove(front)
        // "Allow a reel someone sends you": while blocked, the first video of a visit plays;
        // swiping on (see countReel) blocks.
        val allowThisOne = reason != null && !front.isWholeApp && Blocking.allowFirst(this, front) &&
            front !in scrolledWhileBlocked
        val angryHold = island != null && SystemClock.elapsedRealtime() < angryHoldUntil
        when {
            reason != null && !angryHold && onShorts && !allowThisOne -> block(front, reason = reason)
            CountStore.showBadge(this) -> showIsland(front)
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
            // Fresh read: Android's cached copy can say a screen is still visible after leaving it.
            root.findAccessibilityNodeInfosByViewId(id).any { it.refresh(); it.isVisibleToUser }
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
     * Takes the user out and shows the limit message. For a reel limit, Back leaves
     * Reels/Shorts/Spotlight but keeps the rest of the app (apps that are only short videos go to
     * the home screen). When [timeUp], the app's daily time limit is used up, so the whole app is
     * left for the home screen.
     */
    private fun block(platform: Platform, timeUp: Boolean = false, reason: BlockReason? = null) {
        if (DEBUG_LOG) Log.d(DBG, "BLOCK $platform timeUp=$timeUp")
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
        if (timeUp) CountStore.markLimitHit(this, LocalDate.now())
        performGlobalAction(if (timeUp || platform.isWholeApp) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        showBlockScreen(platform, timeUp, reason)
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, BLOCK_GAP_MS)
    }

    /**
     * The block screen, as in the design: a badge, Iris (angry for 2 s, then sad), a headline
     * naming the limit, and a big button back to the app's home (or just "Got it" when the whole
     * app is closed).
     */
    private fun showBlockScreen(platform: Platform, timeUp: Boolean, reason: BlockReason? = null) {
        val view = blockView ?: run {
            val themed = ContextThemeWrapper(this, R.style.Theme_ReelCounter)
            val v = LayoutInflater.from(themed).inflate(R.layout.overlay_block, null)
            v.findViewById<View>(R.id.blockClose).setOnClickListener { hideBlockScreen() }
            v.findViewById<View>(R.id.blockOpenApp).setOnClickListener {
                hideBlockScreen()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            windowManager.addView(v, fullScreenParams())
            blockView = v
            v
        }
        val today = LocalDate.now()
        val own = CountStore.appLimit(this, platform)
        val appCount = CountStore.appCount(this, today, platform)
        val appClosed = timeUp || platform.isWholeApp

        val eyes = view.findViewById<EyesView>(R.id.blockEyes)
        handler.removeCallbacks(settleToSad)
        // A limit hit makes Iris angry, then sad. Blocks the user chose (always off, no-reel
        // hours) are calm: Iris is fine with it, or sleepy during no-reel hours.
        val chosen = !timeUp && (reason is BlockReason.Always || reason is BlockReason.Schedule)
        when (reason) {
            is BlockReason.Always -> eyes.setMood(Mood.HAPPY)
            is BlockReason.Schedule -> eyes.setMood(Mood.SLEEPY)
            else -> {
                eyes.setMood(Mood.ANGRY)
                settleEyes = eyes
                handler.postDelayed(settleToSad, ANGRY_HOLD_MS + 200)
            }
        }

        view.findViewById<TextView>(R.id.blockBadge).apply {
            text = when {
                timeUp -> getString(R.string.badge_time_up)
                reason is BlockReason.Always -> getString(R.string.badge_always)
                reason is BlockReason.Schedule -> reason.name
                else -> getString(R.string.badge_limit)
            }
            setBackgroundResource(if (chosen) R.drawable.badge_calm else R.drawable.badge_angry)
            setTextColor(getColor(if (chosen) R.color.night_calm else R.color.night_angry))
        }
        val timeFormat = android.text.format.DateFormat.getTimeFormat(this)
        view.findViewById<TextView>(R.id.blockTitle).text = when {
            timeUp -> getString(R.string.block_title_time, platform.shortName)
            reason is BlockReason.Always -> getString(R.string.block_title_always, platform.shortName)
            reason is BlockReason.Schedule -> getString(
                R.string.block_title_schedule,
                timeFormat.format(java.util.Date.from(reason.until.atDate(LocalDate.now()).atZone(java.time.ZoneId.systemDefault()).toInstant())),
            )
            else -> getString(R.string.block_title)
        }
        val stillWorks = if (appClosed) getString(R.string.block_opens_tomorrow, platform.shortName) else getString(R.string.block_still_works)
        view.findViewById<TextView>(R.id.blockMessage).text = when {
            reason is BlockReason.Always -> getString(R.string.block_message_always) + " " + stillWorks
            reason is BlockReason.Schedule -> getString(R.string.block_message_schedule, reason.name) + " " + stillWorks
            timeUp -> getString(R.string.block_message_time, CountStore.appTimeLimitMinutes(this, platform), platform.shortName) +
                " " + stillWorks
            own > 0 && appCount >= own -> getString(R.string.block_message_app, own, platform.shortName) + " " + stillWorks
            else -> getString(R.string.block_message, CountStore.limit(this)) + " " + stillWorks
        }
        view.findViewById<TextView>(R.id.blockHint).apply {
            text = if (timeUp) getString(R.string.block_used_today, formatMinutes(todayTimeMs(platform))) else ""
            isVisible = text.isNotEmpty()
        }
        view.findViewById<MaterialButton>(R.id.blockClose).text =
            if (appClosed) getString(R.string.block_got_it) else getString(R.string.block_back_to, platform.shortName)
    }

    private var settleEyes: EyesView? = null

    /** After the angry moment, Iris settles to sad. */
    private val settleToSad = Runnable { settleEyes?.setMood(Mood.SAD) }

    private fun formatMinutes(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes < 60) getString(R.string.duration_minutes, minutes.toInt())
        else getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
    }

    private fun hideBlockScreen() {
        handler.removeCallbacks(settleToSad)
        settleEyes = null
        blockView?.let { removeOverlay(it) }
        blockView = null
    }

    // Dynamic island

    private fun showIsland(platform: Platform) {
        val view = island ?: createIsland().also {
            island = it
            handler.postDelayed(pollWhileVisible, POLL_MS)
        }
        val now = SystemClock.elapsedRealtime()
        val progress = maxOf(CountStore.limitProgress(this, platform), timeProgress(platform))
        // The timer shows today's total time in this app; it carries on across visits and
        // starts again from zero at midnight.
        view.setState(CountStore.total(this, LocalDate.now()), now - todayTimeMs(platform), progress)
        if (pleadUntil > now) {
            view.plead((pleadUntil - now) / 1000f)
            pleadUntil = 0L
        }
        // In a reel app but no reel for a while: Iris dozes off until the next one.
        // A touch on the counter counts as being awake too (the Iris spec).
        val active = maxOf(lastCountAt, sessionStart, view.lastTouchAt)
        view.sleepy = active > 0L && now - active > SLEEPY_AFTER_MS
    }

    /**
     * Adds the island's overlay window: Iris's face from the design, docked just below
     * the camera. The user can drag it anywhere; it stays where it's dropped, and snaps back
     * under the camera when dropped near there.
     *
     * The window is only as big as the face plus room for its glow and movement, because it
     * takes touches: anything it covers can't be tapped in the app below.
     */
    private fun createIsland(): IslandView {
        val dp = resources.displayMetrics.density
        // Room for the glow and movement grows with the face.
        val sizeDp = CountStore.counterSizeDp(this)
        val pad = ISLAND_PAD_DP * dp * sizeDp / CountStore.COUNTER_SIZE_DEFAULT
        val size = sizeDp * dp
        val win = (size + 2 * pad).roundToInt()

        val screen = screenSize()
        val camera = topCamera()
        val dockCenterX = camera?.centerX() ?: (screen.x / 2f)
        val dockTop = (camera?.bottom ?: (FALLBACK_TOP_DP * dp)) + CAMERA_GAP_DP * dp + CountStore.islandOffsetDp(this) * dp
        val dock = Point((dockCenterX - win / 2f).roundToInt(), (dockTop - pad).roundToInt())
        // Keep the face itself on screen; only its spare room may hang off the edges.
        val padPx = pad.toInt()
        fun clampX(x: Int) = x.coerceIn(-padPx, screen.x - win + padPx)
        fun clampY(y: Int) = y.coerceIn(minOf(dock.y, -padPx), screen.y - win + padPx)

        val view = IslandView(this, pad)
        // Two windows: the drawing (face plus room for its glow and movement) lets every touch
        // through, and an invisible pad exactly over the face takes the touches. Only the face
        // itself blocks the app underneath (found on a OnePlus: a bigger touch area covered
        // Instagram's "Friends" tab).
        val params = WindowManager.LayoutParams(
            win,
            win,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            ISLAND_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = CountStore.islandPosition(this@ReelCounterService)
            x = saved?.let { clampX(it.x) } ?: dock.x
            y = saved?.let { clampY(it.y) } ?: dock.y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val touchPad = View(this)
        val faceSize = size.roundToInt()
        val touchParams = WindowManager.LayoutParams(
            faceSize,
            faceSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            ISLAND_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + padPx
            y = params.y + padPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val snap = ISLAND_SNAP_DP * dp
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        fun moveTo(x: Int, y: Int) {
            params.x = x
            params.y = y
            touchParams.x = x + padPx
            touchParams.y = y + padPx
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
            if (touchPad.isAttachedToWindow) windowManager.updateViewLayout(touchPad, touchParams)
        }
        touchPad.setOnTouchListener { v, e ->
            // Iris watches the finger on her, as in the demo.
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) view.watch(e.rawX, e.rawY)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val oldX = params.x
                        val oldY = params.y
                        moveTo(clampX(startX + dx.roundToInt()), clampY(startY + dy.roundToInt()))
                        view.dragBy((params.x - oldX).toFloat(), (params.y - oldY).toFloat())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        view.release()
                        if (hypot((params.x - dock.x).toFloat(), (params.y - dock.y).toFloat()) < snap) {
                            // Dropped near its spot: glide back under the camera.
                            val fromX = params.x
                            val fromY = params.y
                            ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 220
                                interpolator = DecelerateInterpolator()
                                addUpdateListener {
                                    val t = it.animatedFraction
                                    moveTo(fromX + ((dock.x - fromX) * t).roundToInt(), fromY + ((dock.y - fromY) * t).roundToInt())
                                }
                                start()
                            }
                            CountStore.setIslandPosition(this, null)
                        } else {
                            CountStore.setIslandPosition(this, Point(params.x, params.y))
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        view.poke(e.x + padPx, e.y + padPx)
                        v.performClick()
                    }
                }
            }
            true
        }
        windowManager.addView(view, params)
        windowManager.addView(touchPad, touchParams)
        islandTouch = touchPad
        return view
    }

    /** The full screen size in pixels, including the status and navigation bars. */
    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }

    /**
     * The camera hole at the top of the screen, in screen pixels, or null without one. Uses the
     * cutout's exact shape where Android provides it (12+); older versions only give a
     * bounding box, which can be wider than the hole itself.
     */
    private fun topCamera(): RectF? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val metrics = windowManager.currentWindowMetrics
        val cutout = metrics.windowInsets.displayCutout ?: return null
        val box = cutout.boundingRects.firstOrNull {
            it.top < 80 * resources.displayMetrics.density
        } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cutout.cutoutPath?.let { path ->
                val exact = RectF()
                path.computeBounds(exact, true)
                if (!exact.isEmpty && RectF.intersects(exact, RectF(box))) return exact
            }
        }
        return RectF(box)
    }

    /** Animates the island back into a dot, then removes its window. */
    private fun hideBadge() {
        handler.removeCallbacks(pollWhileVisible)
        val leaving = island ?: return
        island = null
        islandTouch?.let { removeOverlay(it) }
        islandTouch = null
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
        stopTiming()
        try {
            unregisterReceiver(screenOff)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        handler.removeCallbacksAndMessages(null)
        hideBadge()
        hideBlockScreen()
        hidePauseScreen()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ReelCounterService"

        /** Test builds only: logs what the service sees (tag RCDBG). Must be false in releases. */
        const val DEBUG_LOG = false
        const val DBG = "RCDBG"
        val DEBUG_PACKAGES = setOf("com.snapchat.android")
        const val SYSTEM_UI = "com.android.systemui"

        /**
         * The island's window: takes touches only on itself so it can be dragged (everything
         * outside it still reaches the app), never takes focus, and may sit in the status bar area.
         */
        const val ISLAND_FLAGS = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /**
         * Room around the face in its window for its glow and movement. The spec says 16dp;
         * a little more keeps the glow from being cut off at the top of a hop or a stretch.
         */
        const val ISLAND_PAD_DP = 20

        /** Space between the bottom of the camera hole and the top of the face. */
        const val CAMERA_GAP_DP = 6

        /** Top of the face on phones without a camera hole (below the status bar icons). */
        const val FALLBACK_TOP_DP = 30

        /** Dropped within this distance of its spot under the camera, the face docks back there. */
        const val ISLAND_SNAP_DP = 48

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

        /** Lines of text that identify a video in [checkSignature] (title, creator · sound). */
        const val SIGNATURE_TEXTS = 2
        const val MAX_SEEN_SIGNATURES = 200

        /** At the limit, time for Iris's surprise, huff and anger before the block screen. */
        const val ANGRY_HOLD_MS = 2200L
        const val TIME_SAVE_MS = 30_000L

        /** From this share of a limit on, every reel gives a short buzz. */
        const val NEAR_LIMIT = 0.9f

        /** After this long in a reel app without a reel, Iris dozes off (the spec's 15 min hardly ever showed). */
        const val SLEEPY_AFTER_MS = 3 * 60 * 1000L

        /** How long the counter pleads after "Continue anyway", and near an app's time limit. */
        const val PLEAD_AFTER_PAUSE_MS = 5000L
        const val PLEAD_NEAR_TIME_MS = 4000L
    }
}
