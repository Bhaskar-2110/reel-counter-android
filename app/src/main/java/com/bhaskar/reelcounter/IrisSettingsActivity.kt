package com.bhaskar.reelcounter

import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import java.time.LocalDate

/**
 * Iris settings (design): a live preview of Iris over a colourful "reel", whether she shows over
 * reel apps, her size (the only dial: bigger is harder to ignore), and how to move her. Position
 * is set by dragging Iris over any reel, so there's no position setting beyond the camera line.
 */
class IrisSettingsActivity : PageActivity() {

    private var preview: IslandView? = null
    private var playing: Runnable? = null
    private val previewStart = SystemClock.elapsedRealtime() - 192_000L

    override fun title(): CharSequence = getString(R.string.iris)

    // The preview and the slider update themselves; rebuilding would cut them off mid-drag.
    override fun onSharedPreferenceChanged(prefs: android.content.SharedPreferences?, key: String?) {
        if (key == null || key.startsWith("total|")) preview?.let { showToday(it) }
    }

    override fun build() {
        val frame = FrameLayout(this).apply {
            background = getDrawable(R.drawable.island_wallpaper)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val island = IslandView(this).apply {
            cycle = true
            contentDescription = getString(R.string.iris_description)
        }
        preview = island
        showToday(island)
        frame.addView(island, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(TextView(this).apply {
            setText(R.string.preview)
            textSize = 11.5f
            fontVariationSettings = "'wght' 600"
            setTextColor(0xBFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = Ui.dp(this@IrisSettingsActivity, 10f)
        })
        content.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 170f)).apply {
            topMargin = Ui.dp(this@IrisSettingsActivity, 4f)
        })

        val main = list(12f)
        Ui.switchRow(main, R.drawable.ic_eye, getString(R.string.show_iris), getString(R.string.show_iris_sub), CountStore.showBadge(this)) {
            CountStore.setShowBadge(this, it)
        }

        section(R.string.size)
        val size = card(8f)
        val sizeText = Ui.text(this, R.style.Rr_Text_CardTitle, "")
        fun showValue(pct: Int) {
            sizeText.text = getString(R.string.percent, pct)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(Ui.text(this, R.style.Rr_Text_Muted, "A").apply { textSize = 12f })
        val slider = Slider(this).apply {
            valueFrom = 80f
            valueTo = 180f
            stepSize = 10f
            value = SettingsActivity.sizePercent(CountStore.counterSizeDp(context)).toFloat().coerceIn(80f, 180f)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            isTickVisible = false
            contentDescription = getString(R.string.size)
            addOnChangeListener { _, v, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val dp = Math.round(CountStore.COUNTER_SIZE_DEFAULT * v / 100f)
                CountStore.setCounterSizeDp(context, dp)
                island.sizeDp = CountStore.counterSizeDp(context)
                showValue(v.toInt())
            }
        }
        row.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(this, R.style.Rr_Text_Muted, "A").apply {
            textSize = 17f
            fontVariationSettings = "'wght' 700"
        })
        size.addView(row)
        showValue(slider.value.toInt())
        size.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.text(context, R.style.Rr_Text_Muted, getString(R.string.bigger_harder)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sizeText)
        })

        val extra = list(12f)
        Ui.switchRow(extra, R.drawable.ic_pause, getString(R.string.setting_reduce_motion), getString(R.string.reduce_motion_sub),
            CountStore.reduceMotion(this)) {
            CountStore.setReduceMotion(this, it)
            recreate()
        }
        Ui.setRow(extra, R.drawable.ic_progress, getString(R.string.play_day), getString(R.string.play_day_sub), listOf(Ui.chevron(this))) {
            playDay(island)
        }
    }

    private fun showToday(island: IslandView) {
        if (playing != null) return
        island.lockedMood = null
        val total = CountStore.total(this, LocalDate.now())
        island.setState(total, previewStart, total / CountStore.limit(this).toFloat())
    }

    /**
     * "Play a full day" on the preview: 30 reels from happy to angry, with a binge
     * (electrocuted), a doze (sleepy) and a pause screen (pleading) on the way.
     */
    private fun playDay(island: IslandView) {
        stopPlayDay()
        val steps = ArrayList<Pair<Long, () -> Unit>>()
        var n = 0
        fun reels(count: Int, gap: Long, firstGap: Long = gap) = repeat(count) { k ->
            steps += (if (k == 0) firstGap else gap) to {
                n++
                island.setState(n, previewStart, n / PLAY_LIMIT)
                island.onReel(n / PLAY_LIMIT)
            }
        }
        steps += 0L to {
            island.lockedMood = null
            island.sleepy = false
            island.setState(0, previewStart, 0f)
        }
        reels(8, 700, firstGap = 900)
        reels(4, 330)
        reels(6, 800, firstGap = 1800)
        steps += 800L to { island.sleepy = true }
        reels(7, 800, firstGap = 4200)
        steps += 800L to { island.lockedMood = Mood.PLEADING; island.kick() }
        steps += 3000L to { island.lockedMood = null }
        reels(5, 900)
        var i = 0
        val runner = object : Runnable {
            override fun run() {
                if (i >= steps.size) {
                    playing = null
                    return
                }
                steps[i].second()
                i++
                if (i < steps.size) island.postDelayed(this, steps[i].first) else playing = null
            }
        }
        playing = runner
        runner.run()
    }

    private fun stopPlayDay() {
        playing?.let { preview?.removeCallbacks(it) }
        playing = null
    }

    override fun onDestroy() {
        stopPlayDay()
        super.onDestroy()
    }

    /** Iris in the preview watches the finger. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) preview?.watch(ev.rawX, ev.rawY)
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val PLAY_LIMIT = 30f
    }
}
