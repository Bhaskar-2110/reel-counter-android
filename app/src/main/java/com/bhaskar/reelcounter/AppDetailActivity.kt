package com.bhaskar.reelcounter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.bhaskar.reelcounter.databinding.ActivityAppDetailBinding
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One app's page (idea from Regain's per-app page): today's reels and minutes, the last 7 days,
 * its own limits with a suggestion from real use, and its blocking rules ("Block reels always",
 * "Allow a reel someone sends you", "Count this app"). Opened by tapping an app on Today.
 */
class AppDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDetailBinding
    private lateinit var platform: Platform
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = intent.getStringExtra(EXTRA_PLATFORM)?.let { name -> Platform.entries.firstOrNull { it.name == name } }
            ?: run { finish(); return }
        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.appName.text = platform.shortName
        binding.appIcon.setImageDrawable(icon())

        binding.blockAlways.text = getString(R.string.detail_block_always, platform.shortName)
        binding.blockAlwaysHint.text = getString(R.string.detail_block_always_hint)
        binding.blockAlways.isChecked = Blocking.blockAlways(this, platform)
        binding.blockAlways.setOnCheckedChangeListener { _, on -> Blocking.setBlockAlways(this, platform, on) }
        binding.allowFirst.isChecked = Blocking.allowFirst(this, platform)
        binding.allowFirst.setOnCheckedChangeListener { _, on -> Blocking.setAllowFirst(this, platform, on) }
        // Apps that are nothing but short videos (TikTok…) have no "first reel" to allow.
        binding.allowFirst.isVisible = !platform.isWholeApp
        binding.tracked.isChecked = CountStore.isTracked(this, platform)
        binding.tracked.setOnCheckedChangeListener { _, on -> CountStore.setTracked(this, platform, on) }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) render()
    }

    private fun render() {
        val today = LocalDate.now()
        val reels = CountStore.appCount(this, today, platform)
        val timeMs = CountStore.appTime(this, today, platform)
        binding.todayReels.text = resources.getQuantityString(R.plurals.reels_today_short, reels, reels)
        binding.todaySub.text = getString(R.string.detail_today_sub, duration(timeMs), platform.shortName)

        // Last 7 days, today last.
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val counts = days.map { CountStore.appCount(this, it, platform) }
        binding.weekBars.setData(
            counts.map { it.toFloat() }.toFloatArray(),
            days.mapIndexed { i, d ->
                if (i == days.lastIndex) getString(R.string.today) else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            },
            counts.map { it.toString() },
            highlight = days.lastIndex,
        )
        val firstDay = CountStore.firstDay(this)
        val counted = days.filter { !it.isBefore(firstDay) }
        val n = counted.size.coerceAtLeast(1)
        binding.weekSummary.text = getString(R.string.detail_week_summary, counts.sum() / n, counts.sum())

        renderLimits()
        renderSuggestion(days.dropLast(1).filter { !it.isBefore(firstDay) }, today)
    }

    private fun renderLimits() {
        stepperRow(
            binding.reelLimitRow, getString(R.string.detail_reels_a_day),
            CountStore.appLimit(this, platform), step = 5, max = 500,
        ) { CountStore.setAppLimit(this, platform, it); render() }
        stepperRow(
            binding.timeLimitRow, getString(R.string.detail_minutes_a_day),
            CountStore.appTimeLimitMinutes(this, platform), step = 5, max = 600,
        ) { CountStore.setAppTimeLimitMinutes(this, platform, it); render() }
    }

    /**
     * Suggested limits from real use (idea from Regain): about three quarters of the average on
     * past days (today isn't over yet), rounded to 5.
     */
    private fun renderSuggestion(pastDays: List<LocalDate>, today: LocalDate) {
        val reelsAvg = pastDays.map { CountStore.appCount(this, it, platform) }.average().takeIf { pastDays.isNotEmpty() } ?: 0.0
        val minutesAvg = pastDays.map { CountStore.appTime(this, it, platform) / 60_000.0 }.average().takeIf { pastDays.isNotEmpty() } ?: 0.0
        if (reelsAvg < 1 && minutesAvg < 1) {
            binding.suggestion.text = getString(R.string.detail_suggestion_none, platform.shortName)
            binding.useSuggestion.isVisible = false
            return
        }
        val reels = round5(reelsAvg * 0.75)
        val minutes = round5(minutesAvg * 0.75)
        binding.suggestion.text = getString(R.string.detail_suggestion, reelsAvg.roundToInt(), minutesAvg.roundToInt(), reels, minutes)
        binding.useSuggestion.isVisible = true
        binding.useSuggestion.setOnClickListener {
            CountStore.setAppLimit(this, platform, reels)
            CountStore.setAppTimeLimitMinutes(this, platform, minutes)
            render()
        }
    }

    private fun round5(v: Double) = ((v / 5).roundToInt() * 5).coerceAtLeast(5)

    /** "Label   [−]  20  [+]"; 0 shows "No limit". */
    private fun stepperRow(row: LinearLayout, label: String, value: Int, step: Int, max: Int, onChange: (Int) -> Unit) {
        row.removeAllViews()
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.fg))
            textSize = 15f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun button(sign: String, delta: Int) = TextView(this).apply {
            text = sign
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(getColor(R.color.fg))
            background = getDrawable(R.drawable.stepper_button)
            contentDescription = "$label $sign"
            setOnClickListener { onChange((value + delta).coerceIn(0, max)) }
        }
        row.addView(button("−", -step), LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
        row.addView(TextView(this).apply {
            text = if (value <= 0) getString(R.string.detail_no_limit) else value.toString()
            gravity = Gravity.CENTER
            setTextColor(getColor(if (value <= 0) R.color.muted else R.color.fg))
            textSize = if (value <= 0) 13f else 18f
            typeface = resources.getFont(R.font.bricolage_grotesque)
        }, LinearLayout.LayoutParams((84 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(button("+", step), LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
    }

    private fun icon() = platform.packages.firstNotNullOfOrNull { pkg ->
        try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> getString(R.string.duration_under_minute)
            minutes < 60 -> getString(R.string.duration_minutes, minutes.toInt())
            else -> getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    companion object {
        private const val EXTRA_PLATFORM = "platform"

        fun open(ctx: Context, platform: Platform) =
            ctx.startActivity(Intent(ctx, AppDetailActivity::class.java).putExtra(EXTRA_PLATFORM, platform.name))
    }
}
