package com.bhaskar.reelcounter

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bhaskar.reelcounter.databinding.ActivityUsageBinding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Screen time in detail, from Android's usage data (what Digital Wellbeing is built on): the
 * day's total, unlocks, notifications and first pickup, use by hour, social apps and all apps
 * with opens and longest sessions, and the last 7 days.
 */
class UsageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUsageBinding
    private val io = Executors.newSingleThreadExecutor()
    private var daysBack = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.prevDay.setOnClickListener { daysBack = minOf(daysBack + 1, 6); load() }
        binding.nextDay.setOnClickListener { daysBack = maxOf(daysBack - 1, 0); load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val today = LocalDate.now()
        val day = today.minusDays(daysBack.toLong())
        binding.dayLabel.text = when (daysBack) {
            0 -> getString(R.string.today)
            1 -> getString(R.string.usage_yesterday)
            else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        }
        binding.prevDay.isEnabled = daysBack < 6
        binding.nextDay.isEnabled = daysBack > 0

        val app = applicationContext
        io.execute {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun endOf(d: LocalDate) = if (d == today) now else startOf(d.plusDays(1))

            val report = UsageRepository.dayReport(app, startOf(day), endOf(day))
            val week = (6 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                d to UsageRepository.dayReport(app, startOf(d), endOf(d)).totalMs
            }
            runOnUiThread { if (!isDestroyed) show(report, week) }
        }
    }

    private fun show(report: DayReport, week: List<Pair<LocalDate, Long>>) {
        binding.totalTime.text = formatDuration(report.totalMs)
        binding.unlocks.text = report.unlocks.toString()
        binding.notifications.text = if (report.notifications >= 0) report.notifications.toString() else "–"
        binding.firstPickup.text = report.firstPickup?.let { DateFormat.getTimeFormat(this).format(Date(it)) } ?: "–"

        // By hour: bars for each hour, labels every 6 hours.
        val hours = FloatArray(24) { report.hourlyMs[it] / 60_000f }
        val hourLabels = List(24) { h -> if (h % 6 == 0) hourLabel(h) else "" }
        binding.hourChart.setData(hours, hourLabels, highlight = hours.indices.maxByOrNull { hours[it] } ?: -1)
        val peak = hours.indices.maxByOrNull { hours[it] }
        binding.peakHour.text = if (peak != null && hours[peak] > 0f) {
            getString(R.string.usage_peak, hourLabel(peak), hourLabel((peak + 1) % 24), formatDuration(report.hourlyMs[peak]))
        } else {
            getString(R.string.usage_none_day)
        }

        val social = report.social
        binding.socialTotal.text = getString(
            R.string.usage_social_total,
            formatDuration(social.sumOf { it.foregroundMs }),
            social.sumOf { it.opens },
        )
        fillList(binding.socialList, social, getString(R.string.usage_social_none))
        fillList(binding.allList, report.apps.take(20), getString(R.string.usage_none_day))

        // Last 7 days of total screen time, in hours.
        val values = FloatArray(week.size) { week[it].second / 3_600_000f }
        val labels = week.mapIndexed { i, (d, _) ->
            if (i == week.lastIndex) getString(R.string.today) else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        val valueLabels = week.map { (_, ms) -> if (ms > 0) formatShort(ms) else "" }
        binding.weekChart.setData(values, labels, valueLabels, highlight = week.lastIndex - daysBack)
        val daysWithData = week.count { it.second > 0 }.coerceAtLeast(1)
        binding.weekAverage.text = getString(R.string.usage_week_average, formatDuration(week.sumOf { it.second } / daysWithData))
    }

    private fun fillList(container: LinearLayout, apps: List<AppUsage>, empty: String) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            val text = layoutInflater.inflate(R.layout.row_usage, container, false)
            text.findViewById<TextView>(R.id.usageName).text = empty
            text.findViewById<TextView>(R.id.usageOpens).text = ""
            text.findViewById<TextView>(R.id.usageTime).text = ""
            container.addView(text)
            return
        }
        val timeFormat = DateFormat.getTimeFormat(this)
        apps.forEach { usage ->
            val row = layoutInflater.inflate(R.layout.row_usage, container, false)
            row.findViewById<TextView>(R.id.usageName).text = usage.label
            row.findViewById<TextView>(R.id.usageTime).text = formatDuration(usage.foregroundMs)
            val details = buildList {
                add(resources.getQuantityString(R.plurals.usage_opens, usage.opens, usage.opens))
                if (usage.notifications > 0) {
                    add(resources.getQuantityString(R.plurals.usage_notifications, usage.notifications, usage.notifications))
                }
                if (usage.longestMs >= 60_000) add(getString(R.string.usage_longest, formatDuration(usage.longestMs)))
                if (usage.lastUsed > 0) add(getString(R.string.usage_last, timeFormat.format(Date(usage.lastUsed))))
            }
            row.findViewById<TextView>(R.id.usageOpens).text = details.joinToString(" · ")
            val icon = try {
                packageManager.getApplicationIcon(usage.packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            row.findViewById<ImageView>(R.id.usageIcon).setImageDrawable(icon)
            container.addView(row)
        }
    }

    /** "12 AM", "6 PM" or "18:00", following the phone's 12/24-hour setting. */
    private fun hourLabel(hour: Int): String =
        if (DateFormat.is24HourFormat(this)) "%02d:00".format(hour)
        else "${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "AM" else "PM"}"

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> getString(R.string.duration_under_minute)
            minutes < 60 -> getString(R.string.duration_minutes, minutes.toInt())
            else -> getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    /** Compact form for chart labels: "45m", "2h", "2.5h". */
    private fun formatShort(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "${minutes}m"
            minutes % 60 < 15 -> "${minutes / 60}h"
            else -> "%.1fh".format(minutes / 60f)
        }
    }
}
