package com.bhaskar.reelcounter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.bhaskar.reelcounter.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.batteryButton.setOnClickListener { requestBackgroundRunning() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        binding.usageAccessButton.setOnClickListener { openUsageAccessSettings() }
        binding.moodToggle.addOnButtonCheckedListener { _, _, _ -> updateIslandPreview() }
        binding.islandPreview.frosted = true

        binding.blockSwitch.isChecked = CountStore.blockAtLimit(this)
        binding.badgeSwitch.isChecked = CountStore.showBadge(this)
        binding.blockSwitch.setOnCheckedChangeListener { _, on -> CountStore.setBlockAtLimit(this, on) }
        binding.badgeSwitch.setOnCheckedChangeListener { _, on -> CountStore.setShowBadge(this, on) }
        binding.pauseSwitch.isChecked = CountStore.pauseOnOpen(this)
        binding.pauseSwitch.setOnCheckedChangeListener { _, on -> CountStore.setPauseOnOpen(this, on) }

        binding.limitInput.setText(CountStore.limit(this).toString())
        binding.limitInput.doAfterTextChanged { text ->
            val value = text?.toString()?.toIntOrNull()?.takeIf { it in 1..999 }
            binding.limitLayout.error = if (value == null) getString(R.string.limit_error) else null
            if (value != null) CountStore.setLimit(this, value)
        }

        binding.resetButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset) { _, _ -> CountStore.resetDay(this, LocalDate.now()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        CountStore.prefs(this).registerOnSharedPreferenceChangeListener(this)
        render()
        loadUsage()
        if (isServiceEnabled()) KeepAliveService.start(this)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // Social media screen time

    private fun loadUsage() {
        val granted = UsageRepository.hasAccess(this)
        binding.usageAccessHint.isVisible = !granted
        binding.usageAccessButton.isVisible = !granted
        binding.usageContent.isVisible = granted
        if (!granted) return

        val app = applicationContext
        io.execute {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val now = System.currentTimeMillis()
            fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()

            val todayUsage = UsageRepository.socialUsage(app, startOf(today), now)
            val weekMs = (0..6).sumOf { back ->
                val day = today.minusDays(back.toLong())
                val end = if (back == 0) now else startOf(day.plusDays(1))
                UsageRepository.socialUsage(app, startOf(day), end).sumOf { it.foregroundMs }
            }
            runOnUiThread { if (!isDestroyed) showUsage(todayUsage, weekMs) }
        }
    }

    private fun showUsage(apps: List<AppUsage>, weekMs: Long) {
        val totalMs = apps.sumOf { it.foregroundMs }
        binding.usageTotal.text = if (apps.isEmpty()) getString(R.string.usage_none)
        else getString(R.string.usage_total_today, formatDuration(totalMs))
        binding.usageTotal.textSize = if (apps.isEmpty()) 15f else 28f
        binding.usageOpensTotal.isVisible = apps.isNotEmpty()
        binding.usageOpensTotal.text = getString(R.string.usage_opens_total, apps.sumOf { it.opens })

        binding.usageList.removeAllViews()
        apps.forEach { usage ->
            val row = layoutInflater.inflate(R.layout.row_usage, binding.usageList, false)
            row.findViewById<TextView>(R.id.usageName).text = usage.label
            row.findViewById<TextView>(R.id.usageOpens).text = getString(R.string.usage_app_opens, usage.opens)
            row.findViewById<TextView>(R.id.usageTime).text = formatDuration(usage.foregroundMs)
            val icon = try {
                packageManager.getApplicationIcon(usage.packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            row.findViewById<ImageView>(R.id.usageIcon).setImageDrawable(icon)
            binding.usageList.addView(row)
        }

        binding.usageWeek.text = getString(R.string.usage_week, formatDuration(weekMs), formatDuration(weekMs / 7))
    }

    private fun openUsageAccessSettings() {
        val forThisApp = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
        try {
            startActivity(forThisApp)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> getString(R.string.duration_under_minute)
            minutes < 60 -> getString(R.string.duration_minutes, minutes.toInt())
            else -> getString(R.string.duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    override fun onPause() {
        CountStore.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = render()

    private fun render() {
        val today = LocalDate.now()
        val total = CountStore.total(this, today)
        val limit = CountStore.limit(this)

        binding.dateText.text = today.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

        val enabled = isServiceEnabled()
        binding.statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        binding.statusDetail.setText(if (enabled) R.string.status_on_detail else R.string.status_off_detail)
        binding.enableButton.isVisible = !enabled
        binding.batteryCard.isVisible = !isIgnoringBatteryOptimizations()

        binding.todayCount.text = total.toString()
        binding.mainEyes.setMood(if (total >= limit) Mood.ANGRY else Mood.CURIOUS, total.toFloat() / limit)
        updateIslandPreview()

        binding.limitProgress.max = limit
        binding.limitProgress.setProgressCompat(minOf(total, limit), true)
        val level = when {
            total >= limit -> R.color.over
            total >= limit * 0.8 -> R.color.warn
            else -> R.color.ok
        }
        binding.limitProgress.setIndicatorColor(getColor(level))
        binding.limitText.text = when {
            total < limit -> getString(R.string.left_today, limit - total, limit)
            CountStore.blockAtLimit(this) -> getString(R.string.at_limit)
            else -> getString(R.string.over_limit, total - limit, limit)
        }

        val opens = CountStore.opens(this, today)
        binding.opensText.isVisible = CountStore.pauseOnOpen(this)
        binding.opensText.text = if (opens < CountStore.FREE_OPENS) {
            getString(R.string.opens_free, opens, CountStore.FREE_OPENS - opens)
        } else {
            getString(R.string.opens_paused, opens, CountStore.pauseSecondsFor(opens + 1))
        }

        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val values = days.map { CountStore.total(this, it) }
        val labels = days.mapIndexed { i, d ->
            if (i == days.lastIndex) getString(R.string.today)
            else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        binding.weekChart.setData(labels, values, limit)
        val weekTotal = values.sum()
        binding.weekStats.text = getString(R.string.week_stats, weekTotal / 7, weekTotal)
    }

    private fun updateIslandPreview() {
        val total = CountStore.total(this, LocalDate.now())
        val limit = CountStore.limit(this)
        val mood = when (binding.moodToggle.checkedButtonId) {
            R.id.moodElectric -> Mood.ELECTRIC
            R.id.moodAngry -> Mood.ANGRY
            else -> Mood.CURIOUS
        }
        binding.islandPreview.setState(total, mood, if (mood == Mood.CURIOUS) 0.1f else 1f)
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, ReelCounterService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBackgroundRunning() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
