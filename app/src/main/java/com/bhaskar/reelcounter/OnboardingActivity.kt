package com.bhaskar.reelcounter

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.bhaskar.reelcounter.databinding.ActivityOnboardingBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * First-run onboarding, following design_docs/reel-counter-onboarding.html: show people their
 * own number before asking for anything hard.
 *
 * Steps: Welcome → Your guess (and which apps) → Usage access → The reveal → Set a limit →
 * Accessibility → Notifications → Background (only on brands that need it) → First reel →
 * Today. The design's Create account and Sign in steps need a server and aren't built yet.
 */
class OnboardingActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityOnboardingBinding
    private val io = Executors.newSingleThreadExecutor()
    private val dp by lazy { resources.displayMetrics.density }

    private var step = Step.WELCOME
    private var guess = 120
    private var limit = 85
    private var usageSkipped = false
    private var agreed = false
    private var yesterdayMinutes = -1
    private val backgroundDone = BooleanArray(3)
    private var reelsAtStart = -1

    private enum class Step { WELCOME, GUESS, USAGE, REVEAL, LIMIT, ACCESSIBILITY, NOTIFICATIONS, BACKGROUND, FIRST_REEL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Replaying the tour (or restored from a backup): start from the saved guess and limit.
        CountStore.guess(this).takeIf { it > 0 }?.let {
            guess = it
            limit = CountStore.limit(this)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step == Step.WELCOME) finish() else show(previous(step))
            }
        })
        show(Step.WELCOME)
    }

    override fun onResume() {
        super.onResume()
        CountStore.prefs(this).registerOnSharedPreferenceChangeListener(this)
        // Coming back from a settings screen: move on once the permission is granted.
        when (step) {
            Step.USAGE -> if (UsageRepository.hasAccess(this)) show(Step.REVEAL)
            Step.ACCESSIBILITY -> if (MainActivity.isServiceEnabled(this)) show(Step.NOTIFICATIONS)
            else -> show(step)
        }
    }

    override fun onPause() {
        CountStore.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** On the first-reel step, a new count means it worked. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (step == Step.FIRST_REEL && key?.startsWith("total|") == true) show(Step.FIRST_REEL)
    }

    private fun previous(s: Step): Step = when (s) {
        Step.LIMIT -> if (usageSkipped) Step.USAGE else Step.REVEAL
        Step.FIRST_REEL -> if (needsBackgroundStep()) Step.BACKGROUND else Step.NOTIFICATIONS
        else -> Step.entries[(s.ordinal - 1).coerceAtLeast(0)]
    }

    // Rendering

    private fun show(s: Step) {
        step = s
        binding.extra.removeAllViews()
        binding.ghost.isVisible = false
        binding.primary.isEnabled = true
        binding.topSpace.layoutParams.height = 0
        binding.title.gravity = Gravity.START
        binding.text.gravity = Gravity.START
        binding.eyes.layoutParams.apply { width = (150 * dp).toInt(); height = (150 * dp).toInt() }
        renderDots()
        when (s) {
            Step.WELCOME -> welcome()
            Step.GUESS -> guessStep()
            Step.USAGE -> usageStep()
            Step.REVEAL -> revealStep()
            Step.LIMIT -> limitStep()
            Step.ACCESSIBILITY -> accessibilityStep()
            Step.NOTIFICATIONS -> notificationsStep()
            Step.BACKGROUND -> backgroundStep()
            Step.FIRST_REEL -> firstReelStep()
        }
        binding.eyes.requestLayout()
        binding.topSpace.requestLayout()
    }

    /** Dots for steps 1–8; the current one is a wider mint pill, finished ones are dimmer mint. */
    private fun renderDots() {
        binding.dots.removeAllViews()
        binding.dots.isVisible = step != Step.WELCOME
        val count = Step.entries.size - 1
        for (i in 1..count) {
            val on = i == step.ordinal
            val done = i < step.ordinal
            binding.dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3 * dp
                    setColor(getColor(if (on || done) R.color.accent else R.color.line))
                }
                alpha = if (done) 0.5f else 1f
                layoutParams = LinearLayout.LayoutParams(((if (on) 18 else 6) * dp).toInt(), (6 * dp).toInt()).apply {
                    marginEnd = (5 * dp).toInt()
                }
            })
        }
    }

    private fun primary(text: Int, onClick: () -> Unit) {
        binding.primary.setText(text)
        binding.primary.setOnClickListener { onClick() }
    }

    private fun ghost(text: Int, onClick: () -> Unit) {
        binding.ghost.isVisible = true
        binding.ghost.setText(text)
        binding.ghost.setOnClickListener { onClick() }
    }

    // Steps

    private fun welcome() {
        binding.topSpace.layoutParams.height = (90 * dp).toInt()
        binding.eyes.layoutParams.apply { width = (220 * dp).toInt(); height = (220 * dp).toInt() }
        binding.eyes.setMood(Mood.HAPPY)
        binding.title.setText(R.string.onb_welcome_title)
        binding.text.setText(R.string.onb_welcome_text)
        binding.title.gravity = Gravity.CENTER
        binding.text.gravity = Gravity.CENTER
        primary(R.string.onb_lets_go) { show(Step.GUESS) }
    }

    private fun guessStep() {
        binding.eyes.setMood(guessMood(guess))
        binding.title.setText(R.string.onb_guess_title)
        binding.text.text = ""

        val box = surface()
        val value = bigNumber(guess.toString(), getString(R.string.onb_reels_a_day))
        val line = small(getString(guessLine(guess)))
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 300f
            stepSize = 5f
            this.value = guess.toFloat()
            addOnChangeListener { _, v, _ ->
                guess = v.toInt()
                limit = round5(guess * 0.7f)
                (value.getChildAt(0) as TextView).text = guess.toString()
                line.setText(guessLine(guess))
                binding.eyes.setMood(guessMood(guess))
            }
        }
        box.addView(value)
        box.addView(line)
        box.addView(slider)
        binding.extra.addView(box, margins(top = 16))

        binding.extra.addView(small(getString(R.string.onb_which_apps)), margins(top = 16))
        val chips = ChipGroup(this)
        Platform.entries.forEach { platform ->
            val installed = platform.packages.any { isInstalled(it) }
            chips.addView(Chip(this).apply {
                text = platform.shortName
                isCheckable = true
                isChecked = CountStore.isTracked(this@OnboardingActivity, platform) && installed
                setOnCheckedChangeListener { _, on -> CountStore.setTracked(this@OnboardingActivity, platform, on) }
            })
            if (!installed) CountStore.setTracked(this, platform, false)
        }
        binding.extra.addView(chips, margins(top = 8))
        primary(R.string.onb_next) {
            CountStore.setGuess(this, guess)
            show(Step.USAGE)
        }
    }

    private fun usageStep() {
        binding.eyes.setMood(Mood.CURIOUS)
        binding.title.setText(R.string.onb_usage_title)
        binding.text.setText(R.string.onb_usage_text)
        binding.extra.addView(
            checkCard(
                true to R.string.onb_usage_yes1,
                false to R.string.onb_usage_no,
                true to R.string.onb_usage_yes2,
            ),
            margins(top = 16),
        )
        if (UsageRepository.hasAccess(this)) {
            primary(R.string.onb_continue) { show(Step.REVEAL) }
        } else {
            primary(R.string.usage_access_button) { MainActivity.openUsageAccessSettings(this) }
            ghost(R.string.onb_skip_for_now) {
                usageSkipped = true
                show(Step.LIMIT)
            }
        }
    }

    /** The "aha" moment: yesterday's real social media time next to the guess. */
    private fun revealStep() {
        usageSkipped = false
        binding.eyes.setMood(Mood.SURPRISED)
        primary(R.string.onb_set_limit) { show(Step.LIMIT) }
        if (yesterdayMinutes < 0) {
            binding.title.setText(R.string.onb_reveal_loading)
            binding.text.text = ""
            val app = applicationContext
            io.execute {
                val zone = ZoneId.systemDefault()
                val yesterday = LocalDate.now().minusDays(1)
                val start = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = yesterday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val ms = UsageRepository.socialUsage(app, start, end).sumOf { it.foregroundMs }
                runOnUiThread {
                    yesterdayMinutes = (ms / 60_000).toInt()
                    if (!isDestroyed && step == Step.REVEAL) show(Step.REVEAL)
                }
            }
            return
        }
        val estimate = yesterdayMinutes * 60 / SECONDS_PER_REEL
        binding.title.text = getString(R.string.onb_reveal_title, formatMinutes(yesterdayMinutes))
        binding.text.text = getString(R.string.onb_reveal_text, estimate, yesterdayMinutes * 7 / 60)
        val max = maxOf(estimate, guess, 1)
        val compare = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        compare.addView(compareRow(getString(R.string.onb_reveal_guess, guess), guess / max.toFloat(), R.color.raised, animate = false))
        compare.addView(compareRow(getString(R.string.onb_reveal_real, estimate), estimate / max.toFloat(), R.color.mood_worried, animate = true), margins(top = 12))
        binding.extra.addView(compare, margins(top = 18))
        binding.extra.addView(small(getString(R.string.onb_reveal_note)), margins(top = 10))
    }

    private fun limitStep() {
        binding.eyes.setMood(Mood.HAPPY)
        binding.title.text = getString(R.string.onb_limit_title, limit)
        binding.text.setText(if (usageSkipped) R.string.onb_limit_text_skipped else R.string.onb_limit_text)

        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.surface_rounded)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        val value = TextView(this).apply {
            text = limit.toString()
            gravity = Gravity.CENTER
            textSize = 52f
            typeface = resources.getFont(R.font.bricolage_grotesque)
            fontVariationSettings = "'wght' 750"
            setTextColor(getColor(R.color.accent))
        }
        fun step(delta: Int) {
            limit = (limit + delta).coerceIn(5, 500)
            show(Step.LIMIT)
        }
        stepper.addView(stepperButton("−") { step(-5) })
        stepper.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepper.addView(stepperButton("+") { step(5) })
        binding.extra.addView(stepper, margins(top = 18))

        val quick = ChipGroup(this).apply { isSingleLine = true }
        listOf(20, 40, 60, 100).forEach { q ->
            quick.addView(Chip(this).apply {
                text = q.toString()
                isCheckable = true
                isChecked = limit == q
                setOnClickListener {
                    limit = q
                    show(Step.LIMIT)
                }
            })
        }
        binding.extra.addView(quick, margins(top = 10).apply { gravity = Gravity.CENTER_HORIZONTAL })
        primary(R.string.onb_sounds_good) {
            CountStore.setLimit(this, limit)
            show(Step.ACCESSIBILITY)
        }
    }

    /** The hard step: a clear disclosure with an explicit "I agree", and "later" allowed. */
    private fun accessibilityStep() {
        binding.eyes.setMood(Mood.PLEADING)
        binding.title.setText(R.string.onb_a11y_title)
        binding.text.setText(R.string.onb_a11y_text)
        binding.extra.addView(
            checkCard(
                true to R.string.onb_a11y_yes1,
                false to R.string.onb_a11y_no,
                true to R.string.onb_a11y_yes2,
            ),
            margins(top = 16),
        )
        if (MainActivity.isServiceEnabled(this)) {
            primary(R.string.onb_continue) { show(Step.NOTIFICATIONS) }
            return
        }
        val agree = CheckBox(this).apply {
            setText(R.string.onb_a11y_agree)
            isChecked = agreed
            setTextColor(getColor(R.color.fg))
            buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setOnCheckedChangeListener { _, on ->
                agreed = on
                binding.primary.isEnabled = on
            }
        }
        binding.extra.addView(agree, margins(top = 12))
        primary(R.string.onb_open_settings) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.primary.isEnabled = agreed
        ghost(R.string.onb_later) { show(Step.NOTIFICATIONS) }
    }

    private fun notificationsStep() {
        binding.eyes.setMood(Mood.CURIOUS)
        binding.title.setText(R.string.onb_notif_title)
        binding.text.setText(R.string.onb_notif_text)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.surface_rounded)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        val icon = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 9 * dp
                setColor(getColor(R.color.accent))
            }
            addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(R.drawable.ic_stat_reel_counter)
                imageTintList = ColorStateList.valueOf(getColor(R.color.on_accent))
            }, FrameLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt(), Gravity.CENTER))
        }
        card.addView(icon, LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = getString(R.string.notif_title, 9)
            setTextColor(getColor(R.color.fg))
            textSize = 14f
            fontVariationSettings = "'wght' 600"
        })
        texts.addView(small(getString(R.string.onb_notif_sample)))
        card.addView(texts)
        binding.extra.addView(card, margins(top = 20))

        val next = { show(if (needsBackgroundStep()) Step.BACKGROUND else Step.FIRST_REEL) }
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            primary(R.string.onb_allow_notif) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF) }
            ghost(R.string.onb_not_now) { next() }
        } else {
            primary(R.string.onb_continue) { next() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF) show(if (needsBackgroundStep()) Step.BACKGROUND else Step.FIRST_REEL)
    }

    /** Brand-specific settings that keep the app alive; only shown on brands that need them. */
    private fun backgroundStep() {
        val all = backgroundDone.all { it }
        binding.eyes.setMood(if (all) Mood.HAPPY else Mood.WORRIED)
        binding.title.text = getString(R.string.onb_bg_title, brandName())
        binding.text.text = getString(R.string.onb_bg_text, brandName())
        val items = listOf(R.string.onb_bg_battery, R.string.onb_bg_autolaunch, R.string.onb_bg_lock)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, label ->
            list.addView(checkRow(i + 1, getString(label), backgroundDone[i]) {
                backgroundDone[i] = true
                when (i) {
                    0 -> requestBackgroundRunning()
                    1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    2 -> Toast.makeText(this, R.string.onb_bg_lock_how, Toast.LENGTH_LONG).show()
                }
            }, margins(top = 8))
        }
        binding.extra.addView(list, margins(top = 12))
        binding.extra.addView(small(getString(R.string.onb_bg_note)), margins(top = 10))
        primary(if (all) R.string.onb_continue else R.string.onb_bg_done) { show(Step.FIRST_REEL) }
        ghost(R.string.onb_skip) { show(Step.FIRST_REEL) }
    }

    /** Proves it works before they leave: one swipe, +1, Iris happy, and the island lined up. */
    private fun firstReelStep() {
        if (!MainActivity.isServiceEnabled(this)) {
            binding.eyes.setMood(Mood.SAD)
            binding.title.setText(R.string.onb_waiting_title)
            binding.text.setText(R.string.onb_waiting_text)
            primary(R.string.onb_turn_on_now) { show(Step.ACCESSIBILITY) }
            ghost(R.string.onb_go_today) { finishOnboarding() }
            return
        }
        val total = CountStore.total(this, LocalDate.now())
        if (reelsAtStart < 0) reelsAtStart = total
        val counted = total > reelsAtStart
        binding.eyes.setMood(if (counted) Mood.HAPPY else Mood.CURIOUS)
        binding.title.setText(if (counted) R.string.onb_reel_done_title else R.string.onb_reel_title)
        binding.text.setText(if (counted) R.string.onb_reel_done_text else R.string.onb_reel_text)
        if (counted) {
            val chips = ChipGroup(this)
            chips.addView(Chip(this).apply {
                setText(R.string.onb_island_up)
                setOnClickListener { CountStore.setIslandOffsetDp(this@OnboardingActivity, CountStore.islandOffsetDp(this@OnboardingActivity) - 2) }
            })
            chips.addView(Chip(this).apply {
                setText(R.string.onb_island_down)
                setOnClickListener { CountStore.setIslandOffsetDp(this@OnboardingActivity, CountStore.islandOffsetDp(this@OnboardingActivity) + 2) }
            })
            binding.extra.addView(chips, margins(top = 16))
        }
        primary(R.string.onb_done) { finishOnboarding() }
        binding.primary.isEnabled = counted
        ghost(R.string.onb_skip) { finishOnboarding() }
    }

    private fun finishOnboarding() {
        CountStore.setOnboarded(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // Helpers

    private fun needsBackgroundStep(): Boolean = brandName() != null

    /** Brands known for closing background apps; null on others. */
    private fun brandName(): String? {
        val brand = Build.MANUFACTURER.lowercase()
        return listOf("oneplus", "oppo", "realme", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung")
            .firstOrNull { brand.contains(it) }
            ?.replaceFirstChar { it.uppercase() }
            ?.replace("Oneplus", "OnePlus")
    }

    @SuppressLint("BatteryLife")
    private fun requestBackgroundRunning() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun guessMood(g: Int) = when {
        g < 60 -> Mood.HAPPY
        g < 150 -> Mood.CURIOUS
        g < 230 -> Mood.WORRIED
        else -> Mood.SURPRISED
    }

    private fun guessLine(g: Int) = when {
        g < 60 -> R.string.onb_guess_light
        g < 150 -> R.string.onb_guess_normal
        g < 230 -> R.string.onb_guess_lot
        else -> R.string.onb_guess_whoa
    }

    private fun round5(n: Float) = maxOf(10, (n / 5f).toInt() * 5)

    private fun formatMinutes(minutes: Int): String =
        if (minutes < 60) getString(R.string.duration_minutes, minutes)
        else getString(R.string.onb_hours_minutes, minutes / 60, minutes % 60)

    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = (top * dp).toInt() }

    private fun surface() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.surface_rounded)
        setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
    }

    private fun small(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.faint))
        textSize = 12f
    }

    /** "120 reels a day": a big Bricolage number with a small label beside it. */
    private fun bigNumber(number: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(TextView(this@OnboardingActivity).apply {
            text = number
            textSize = 44f
            typeface = resources.getFont(R.font.bricolage_grotesque)
            fontVariationSettings = "'wght' 750"
            fontFeatureSettings = "tnum"
            setTextColor(getColor(R.color.fg))
        })
        addView(TextView(this@OnboardingActivity).apply {
            text = label
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            setPadding((6 * dp).toInt(), 0, 0, (8 * dp).toInt())
        })
    }

    /** A card of ✓ / ✕ lines, for what the app does and doesn't see. */
    private fun checkCard(vararg rows: Pair<Boolean, Int>) = surface().apply {
        rows.forEach { (ok, text) ->
            addView(LinearLayout(this@OnboardingActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@OnboardingActivity).apply {
                    this.text = if (ok) "✓" else "✕"
                    setTextColor(getColor(if (ok) R.color.accent else R.color.faint))
                    textSize = 13f
                    fontVariationSettings = "'wght' 700"
                }, LinearLayout.LayoutParams((22 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@OnboardingActivity).apply {
                    setText(text)
                    setTextColor(getColor(R.color.muted))
                    textSize = 13f
                })
            }, margins(top = 4))
        }
    }

    /** One background checklist item: number (or ✓), label, and an Open/Done button. */
    private fun checkRow(n: Int, label: String, done: Boolean, onOpen: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = getDrawable(R.drawable.surface_rounded)
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
        addView(TextView(this@OnboardingActivity).apply {
            text = if (done) "✓" else n.toString()
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(getColor(if (done) R.color.on_accent else R.color.muted))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(getColor(R.color.accent)) else setStroke((1 * dp).toInt(), getColor(R.color.line))
            }
        }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
        addView(TextView(this@OnboardingActivity).apply {
            text = label
            setTextColor(getColor(R.color.fg))
            textSize = 13f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@OnboardingActivity).apply {
            setText(if (done) R.string.onb_bg_item_done else R.string.onb_bg_item_open)
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            fontVariationSettings = "'wght' 600"
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener {
                onOpen()
                show(Step.BACKGROUND)
            }
        })
    }

    private fun stepperButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 24f
        setTextColor(getColor(R.color.fg))
        background = getDrawable(R.drawable.stepper_button)
        layoutParams = LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt())
        setOnClickListener { onClick() }
    }

    /** "Your guess 120 reels" over a bar; the real bar grows in for the reveal. */
    private fun compareRow(label: String, share: Float, color: Int, animate: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@OnboardingActivity).apply {
            text = label
            setTextColor(getColor(R.color.muted))
            textSize = 13f
        })
        val track = FrameLayout(this@OnboardingActivity)
        val bar = View(this@OnboardingActivity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 7 * dp
                setColor(getColor(color))
            }
        }
        track.addView(bar, FrameLayout.LayoutParams(0, (14 * dp).toInt()))
        addView(track, margins(top = 5))
        track.post {
            val target = (track.width * share.coerceIn(0.04f, 1f)).toInt()
            if (!animate) {
                bar.layoutParams.width = target
                bar.requestLayout()
            } else {
                ValueAnimator.ofInt((track.width * 0.04f).toInt(), target).apply {
                    duration = 900
                    startDelay = 150
                    addUpdateListener {
                        bar.layoutParams.width = it.animatedValue as Int
                        bar.requestLayout()
                    }
                    start()
                }
            }
        }
    }

    companion object {
        private const val REQUEST_NOTIF = 7
        private const val SECONDS_PER_REEL = 20
    }

    /** Iris watches the finger wherever it moves. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            binding.eyes.watch(ev.rawX, ev.rawY)
        }
        return super.dispatchTouchEvent(ev)
    }
}
