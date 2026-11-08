package com.reelcounter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.reelcounter.databinding.ActivityOnboardingBinding
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * First open (design/Rokoreels App Flow.html): one promise and one button, then six steps with
 * one job each, easy asks first.
 *
 * Welcome → Pick your apps → Your week in reels (usage access, the low-risk ask with an
 * instant payoff) → Set your limit → Turn on counting (the Accessibility disclosure, with an
 * explicit "I understand") → Keep it running (notifications) → Meet Iris → Home.
 *
 * Progress is saved, so it resumes where it was when the phone closes the app while the user is
 * in Settings granting a permission (OnePlus and others do this often).
 */
class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val io = Executors.newSingleThreadExecutor()

    private var step = Step.WELCOME
    private var limit = LimitLock.DEFAULT
    /** Daily social media time limit, minutes (step 3's second lock). */
    private var timeLimit = TimeLock.DEFAULT
    /** Step 3 has two locks: 0 = reels, 1 = social media time. */
    private var limitPart = 0
    private var agreed = false
    private var revealed = false
    private var weekMs = -1L
    private var weekApps: List<String> = emptyList()
    private val faces = ArrayList<EyesView>()
    private var loggedStep: Step? = null
    /** Settings was opened from step 4 in this session: coming back with counting off shows a banner. */
    private var askedAccess = false
    private var accessLogged = false
    /** The lock's click sounds, made once and kept until onboarding closes. */
    private var lockSounds: LockSounds? = null

    private enum class Step { WELCOME, APPS, WEEK, LIMIT, ACCESS, RUNNING, IRIS, SIGNIN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Replaying the tour (or restored from a backup): start from the saved limit.
        if (CountStore.prefs(this).contains("daily_limit")) limit = CountStore.limit(this)
        CountStore.timeLimitMinutes(this).takeIf { it > 0 }?.let { timeLimit = it }
        binding.obBack.setOnClickListener { back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        restoreProgress()
        show(firstMissing(step), direction = 0)
    }

    override fun onResume() {
        super.onResume()
        // Back from a system screen: move on once the permission is granted.
        when {
            step == Step.WEEK && !revealed && UsageRepository.hasAccess(this) -> {
                revealed = true
                Analytics.permissionGranted(this, "usage")
                show(Step.WEEK, direction = 0)
            }
            // Back from Settings (or brought back by the guide): the "counting is on" summary,
            // or the same screen with "Counting is still off".
            step == Step.ACCESS && MainActivity.isServiceEnabled(this) -> {
                if (askedAccess && !accessLogged) {
                    accessLogged = true
                    Analytics.permissionGranted(this, "accessibility")
                }
                show(Step.ACCESS, direction = 0)
            }
            step == Step.ACCESS && askedAccess -> show(Step.ACCESS, direction = 0)
            step == Step.RUNNING -> show(Step.RUNNING, direction = 0)
        }
    }

    override fun onDestroy() {
        lockSounds?.release()
        lockSounds = null
        io.shutdownNow()
        super.onDestroy()
    }

    private fun back() {
        when (step) {
            Step.WELCOME -> finish()
            Step.IRIS -> {}
            // Step 3's time lock goes back to its reel lock; step 4 back to the time lock.
            Step.LIMIT -> if (limitPart == 1) {
                limitPart = 0
                show(Step.LIMIT, direction = -1)
            } else {
                show(Step.WEEK, direction = -1)
            }
            Step.ACCESS -> {
                limitPart = 1
                show(Step.LIMIT, direction = -1)
            }
            else -> show(Step.entries[step.ordinal - 1], direction = -1)
        }
    }

    // Progress

    private fun restoreProgress() {
        val parts = CountStore.onboardingProgress(this)?.split('|') ?: return
        step = Step.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return
        limit = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(CountStore.LIMIT_MIN, CountStore.LIMIT_MAX) ?: limit
        agreed = parts.getOrNull(2) == "1"
        revealed = parts.getOrNull(3) == "1"
        // Added 2026-11-07; older saves have 4 fields.
        parts.getOrNull(4)?.toIntOrNull()?.takeIf(TimeLock::isValid)?.let { timeLimit = it }
    }

    private fun saveProgress() = CountStore.setOnboardingProgress(
        this, listOf(step.name, limit, if (agreed) 1 else 0, if (revealed) 1 else 0, timeLimit).joinToString("|"),
    )

    // Rendering

    /** Shows [s]; [direction] 1 slides it in from the right, -1 from the left, 0 not at all. */
    private fun show(s: Step, direction: Int) {
        step = s
        if (s != loggedStep) {
            loggedStep = s
            Analytics.onboardingStep(this, s.name)
        }
        saveProgress()
        faces.clear()
        val body = binding.body
        body.removeAllViews()
        binding.foot.removeAllViews()
        binding.foot.isVisible = false
        binding.primary.isVisible = true
        binding.ghost.isVisible = false
        binding.primary.isEnabled = true
        binding.primary.alpha = 1f
        binding.obTop.visibility = if (s == Step.WELCOME) View.INVISIBLE else View.VISIBLE
        binding.obBack.visibility = if (s == Step.IRIS) View.INVISIBLE else View.VISIBLE
        renderProgress(s.ordinal)
        when (s) {
            Step.WELCOME -> welcome()
            Step.APPS -> apps()
            Step.WEEK -> week()
            Step.LIMIT -> limitStep()
            Step.ACCESS -> access()
            Step.RUNNING -> running()
            Step.IRIS -> meetIris()
            Step.SIGNIN -> signInStep()
        }
        if (direction != 0 && !CountStore.reduceMotion(this)) {
            body.translationX = body.width * (if (direction > 0) 0.28f else -0.18f)
            body.alpha = 0f
            body.animate().translationX(0f).alpha(1f).setDuration(if (direction > 0) 340 else 300).setInterpolator(Ui.EASE).start()
        }
    }

    /** The seven-step bar (design ".prog"): segments up to this step in the accent colour. */
    private fun renderProgress(n: Int) {
        val bar = binding.progress
        bar.removeAllViews()
        for (i in 1..STEPS) {
            bar.addView(View(this).apply {
                background = getDrawable(if (i <= n) R.drawable.progress_seg_on else R.drawable.progress_seg_off)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 1) marginStart = Ui.dp(this@OnboardingActivity, 5f) })
        }
        binding.stepText.text = getString(R.string.step_of, n.coerceIn(1, STEPS))
    }

    private fun title(text: CharSequence, center: Boolean = false) = binding.body.addView(
        Ui.text(this, R.style.Rr_Text_OnbTitle, text).apply { if (center) gravity = Gravity.CENTER },
        Ui.gap(this, 0f),
    )

    private fun para(text: CharSequence, center: Boolean = false) = binding.body.addView(
        Ui.text(this, R.style.Rr_Text_Body, text).apply {
            textSize = 15f
            if (center) gravity = Gravity.CENTER
        },
        Ui.gap(this, 12f),
    )

    private fun primary(text: CharSequence, enabled: Boolean = true, onClick: () -> Unit) {
        binding.primary.text = text
        binding.primary.isEnabled = enabled
        binding.primary.alpha = if (enabled) 1f else 0.4f
        binding.primary.setOnClickListener { onClick() }
    }

    private fun face(mood: Mood, sizeDp: Float): EyesView = EyesView(this).apply {
        setMood(mood)
        contentDescription = getString(R.string.iris_description)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(context, sizeDp), Ui.dp(context, sizeDp))
        faces += this
    }

    // Steps

    /** One promise, one button. Iris says hello first, so her later faces mean something. */
    private fun welcome() {
        binding.body.gravity = Gravity.CENTER_HORIZONTAL
        binding.body.addView(face(Mood.HAPPY, 230f).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = Ui.dp(context, 40f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        binding.body.addView(wordmarkView(), Ui.gap(this, 10f))
        title(getString(R.string.onb_welcome_title), center = true)
        para(getString(R.string.onb_welcome_text), center = true)
        primary(getString(R.string.get_started)) { show(Step.APPS, 1) }
        // Returning users. Sign-in only for now: nothing to restore, so the tour carries on.
        if (Account.isSetUp(this) && Account.current(this) == null) {
            binding.ghost.isVisible = true
            binding.ghost.text = getString(R.string.have_account)
            binding.ghost.setOnClickListener {
                Account.signIn(this) { user, error ->
                    if (user != null) {
                        Toast.makeText(this, getString(R.string.signed_in_as, Account.label(user)), Toast.LENGTH_SHORT).show()
                        if (step == Step.WELCOME) show(Step.APPS, 1)
                    } else if (error != null) {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun wordmarkView() = TextView(this).apply {
        text = MainActivity.wordmark(context)
        gravity = Gravity.CENTER
        textSize = 18f
        typeface = resources.getFont(R.font.bricolage_grotesque)
        fontVariationSettings = "'wght' 800"
        setTextColor(attrColor(R.attr.rrText))
    }

    /** Recognition over recall: logos people know, installed apps ticked already. */
    private fun apps() {
        binding.body.gravity = Gravity.NO_GRAVITY
        title(getString(R.string.onb_apps_title))
        para(getString(R.string.onb_apps_text))
        val chips = Ui.chipFlow(this)
        if (CountStore.prefs(this).all.keys.none { it.startsWith("tracked|") }) {
            // New installs start with the apps on this phone ticked.
            Platform.entries.forEach { CountStore.setTracked(this, it, Ui.isInstalled(this, it)) }
        }
        fun refresh() {
            val any = Platform.entries.any { CountStore.isTracked(this, it) }
            primary(getString(R.string.onb_continue), enabled = any) { show(Step.WEEK, 1) }
        }
        for (p in Platform.entries) {
            val icon = Ui.appIconOrBadge(this, p).let { it.constantState?.newDrawable()?.mutate() ?: it }
            chips.addView(Ui.chip(this, p.shortName, CountStore.isTracked(this, p), icon) { chip ->
                val on = !chip.isSelected
                chip.isSelected = on
                CountStore.setTracked(this, p, on)
                refresh()
            })
        }
        binding.body.addView(chips, Ui.gap(this, 18f))
        refresh()
    }

    /** Value before the big ask: usage access shows their real number, last week. */
    private fun week() {
        binding.body.gravity = Gravity.NO_GRAVITY
        val hasAccess = UsageRepository.hasAccess(this)
        // Already allowed (a replay, or granted earlier): show the number straight away.
        if (hasAccess) revealed = true
        if (!revealed || !hasAccess) {
            title(getString(R.string.onb_week_title))
            para(getString(R.string.onb_week_text))
            binding.body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.card_bg)
                setPadding(0, Ui.dp(context, 26f), 0, Ui.dp(context, 26f))
                addView(Ui.text(context, R.style.Rr_Text_Muted, getString(R.string.onb_number_here)).apply { gravity = Gravity.CENTER })
                addView(Ui.text(context, R.style.Rr_Text_Big, "9h 20m").apply {
                    textSize = 44f
                    setTextColor(attrColor(R.attr.rrText3))
                    // A blurred number: something is waiting for you.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRenderEffect(android.graphics.RenderEffect.createBlurEffect(22f, 22f, android.graphics.Shader.TileMode.DECAL))
                    } else {
                        alpha = 0.15f
                    }
                    contentDescription = getString(R.string.onb_hidden_number)
                }, Ui.gap(context, 6f).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
            }, Ui.gap(this, 18f))
            primary(getString(R.string.usage_access_button)) {
                if (hasAccess) {
                    revealed = true
                    show(Step.WEEK, 0)
                } else {
                    MainActivity.openUsageAccessSettings(this)
                }
            }
            // No Skip: screen time and per-app minutes need usage access.
            return
        }
        title(getString(R.string.onb_week_revealed))
        if (weekMs < 0) {
            loadWeek()
            para(getString(R.string.onb_reveal_loading))
            primary(getString(R.string.onb_continue)) { show(Step.LIMIT, 1) }
            return
        }
        val hours = weekMs / 3_600_000.0
        binding.body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.card_bg)
            setPadding(Ui.dp(context, 18f), Ui.dp(context, 18f), Ui.dp(context, 18f), Ui.dp(context, 18f))
            addView(Ui.text(context, R.style.Rr_Text_Big, Ui.minutes(weekMs)).apply {
                textSize = 48f
                setTextColor(moodColor(3))
            })
            addView(Ui.text(context, R.style.Rr_Text, if (weekApps.isEmpty()) getString(R.string.onb_week_none)
            else getString(R.string.onb_week_on, Ui.joinNames(this@OnboardingActivity, weekApps))).apply { gravity = Gravity.CENTER }, Ui.gap(context, 4f))
            addView(Ui.text(context, R.style.Rr_Text_Muted, when {
                // 23 hours used to be called "more than a full working day".
                hours >= 16 -> getString(R.string.onb_week_days, (hours / 8).toInt())
                hours >= 8 -> getString(R.string.onb_week_workday)
                hours >= 3 -> getString(R.string.onb_week_films)
                else -> getString(R.string.onb_week_light)
            }).apply { gravity = Gravity.CENTER }, Ui.gap(context, 2f))
        }, Ui.gap(this, 16f))
        para(getString(R.string.onb_week_help))
        primary(getString(R.string.onb_continue)) { show(Step.LIMIT, 1) }
    }

    /** Last week's time in the apps picked on the previous step. */
    private fun loadWeek() {
        val app = applicationContext
        io.execute {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val start = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val picked = Platform.entries.filter { CountStore.isTracked(app, it) }
            val usage = UsageRepository.socialUsage(app, start, end)
            val byApp = picked.map { p -> p to usage.filter { it.packageName in p.packages }.sumOf { it.foregroundMs } }
                .filter { it.second >= 60_000 }
            runOnUiThread {
                weekMs = byApp.sumOf { it.second }
                weekApps = byApp.sortedByDescending { it.second }.map { it.first.shortName }
                if (!isDestroyed && step == Step.WEEK) show(Step.WEEK, 0)
            }
        }
    }

    /**
     * Step 3/6 has two locks with the same look (design/reel-limit-lock.html): first the daily
     * reel limit (three dials, 1–999, 100 = "most pick"), then the daily social media time limit
     * (hours and minutes, 0:15–9:45, 2 h = "most pick"). Holding the button for 2.5 s locks each
     * in as a promise; only Continue saves it. [limitPart] says which one is showing.
     */
    private fun limitStep() {
        if (limitPart == 0) {
            if (!LimitLock.isValid(limit)) limit = LimitLock.DEFAULT
            lockStep(LockScreen(
                title = getString(R.string.onb_limit_question),
                lede = getString(R.string.lock_lede),
                lockedLede = { resources.getQuantityString(R.plurals.lock_lede_locked, it, it) },
                start = limit,
                isValid = LimitLock::isValid,
                makeLock = { v, onChange -> reelLock(this, v, onChange) },
                picks = LimitLock.QUICK_PICKS.map { it to if (it == LimitLock.DEFAULT) getString(R.string.most_pick, it) else it.toString() },
                big = { it.toString() },
                unit = { resources.getQuantityString(R.plurals.lock_reels_a_day, it) },
                level = LimitLock::level,
                sub = { v ->
                    val level = LimitLock.level(v)
                    val time = minutesText(LimitLock.minutes(v))
                    when {
                        v == 0 -> getString(R.string.lock_pick_one)
                        level >= 3 -> getString(R.string.lock_thats, time)
                        level == 2 -> getString(R.string.lock_bit_more, time)
                        else -> getString(R.string.lock_about, time)
                    }
                },
                oath = { resources.getQuantityString(R.plurals.lock_oath, it, it) },
                onLocked = { limit = it },
                onContinue = {
                    CountStore.setLimit(this, limit)
                    Analytics.limitSet(this, limit)
                    limitPart = 1
                    show(Step.LIMIT, 1)
                },
            ))
        } else {
            if (!TimeLock.isValid(timeLimit)) timeLimit = TimeLock.DEFAULT
            lockStep(LockScreen(
                title = getString(R.string.lock_time_title),
                lede = getString(R.string.lock_time_lede),
                lockedLede = { getString(R.string.lock_time_lede_locked, minutesText(it)) },
                start = timeLimit,
                isValid = TimeLock::isValid,
                makeLock = { v, onChange -> timeLock(this, v, onChange) },
                picks = TimeLock.QUICK_PICKS.map {
                    val h = minutesText(it)
                    it to if (it == TimeLock.DEFAULT) getString(R.string.lock_most_pick_text, h) else h
                },
                big = { minutesText(it) },
                unit = { getString(R.string.lock_time_unit) },
                level = TimeLock::level,
                sub = { v ->
                    val level = TimeLock.level(v)
                    val week = TimeLock.weekHours(v)
                    when {
                        !TimeLock.isValid(v) -> getString(R.string.lock_time_pick_one)
                        level >= 3 -> getString(R.string.lock_time_thats, week, minutesText(TimeLock.DEFAULT))
                        level == 2 -> getString(R.string.lock_time_bit_more, week)
                        else -> getString(R.string.lock_time_about, week)
                    }
                },
                oath = { getString(R.string.lock_time_oath, minutesText(it)) },
                onLocked = { timeLimit = it },
                onContinue = {
                    CountStore.setTimeLimitMinutes(this, timeLimit)
                    Analytics.timeLimitSet(this, timeLimit)
                    show(Step.ACCESS, 1)
                },
            ))
        }
    }

    /** "45 min", "2 h", "2 h 30 min". */
    private fun minutesText(m: Int) = when {
        m < 60 -> getString(R.string.lock_min, m)
        m % 60 == 0 -> getString(R.string.lock_h, m / 60)
        else -> getString(R.string.lock_h_min, m / 60, m % 60)
    }

    /** What differs between step 3's two locks. */
    private class LockScreen(
        val title: String,
        val lede: String,
        val lockedLede: (Int) -> String,
        val start: Int,
        val isValid: (Int) -> Boolean,
        val makeLock: (Int, (Int) -> Unit) -> ReelLockView,
        val picks: List<Pair<Int, String>>,
        val big: (Int) -> String,
        val unit: (Int) -> String,
        val level: (Int) -> Int,
        val sub: (Int) -> String,
        val oath: (Int) -> String,
        val onLocked: (Int) -> Unit,
        val onContinue: () -> Unit,
    )

    /** One combination-lock screen: dials, readout, quick picks, oath, hold button, hint. */
    private fun lockStep(cfg: LockScreen) {
        binding.body.gravity = Gravity.NO_GRAVITY
        binding.primary.isVisible = false
        val text3 = attrColor(R.attr.rrText3)
        val heading = Ui.text(this, R.style.Rr_Text_OnbTitle, cfg.title)
        binding.body.addView(heading, Ui.gap(this, 0f))
        val lede = Ui.text(this, R.style.Rr_Text_Body, cfg.lede).apply {
            textSize = 15f
            setTextColor(attrColor(R.attr.rrText2))
        }
        binding.body.addView(lede, Ui.gap(this, 8f))

        // Readout: "100 reels a day" over "About 33 min of reels a day".
        val number = TextView(this).apply {
            typeface = resources.getFont(R.font.bricolage_grotesque)
            fontVariationSettings = "'wght' 800"
            fontFeatureSettings = "tnum"
            textSize = 22f
        }
        val unit = Ui.text(this, R.style.Rr_Text).apply {
            textSize = 17f
            fontVariationSettings = "'wght' 700"
        }
        val sub = Ui.text(this, R.style.Rr_Text_Muted).apply {
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val readout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = Ui.dp(context, 52f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(number)
                addView(unit)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            })
            addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(context, 4f)
            })
        }

        // Pinned at the bottom: the oath, the hold button, a hint, and "Change the number".
        val oath = TextView(this).apply {
            gravity = Gravity.CENTER
            typeface = resources.getFont(R.font.bricolage_grotesque)
            fontVariationSettings = "'wght' 600"
            textSize = 19f
            setTextColor(text3)
            setPadding(Ui.dp(context, 8f), 0, Ui.dp(context, 8f), 0)
            // design: aria-live on the oath; it speaks when the number changes, not per word.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val hint = Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.lock_hint)).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            minHeight = Ui.dp(context, 18f)
        }
        var words: List<String> = emptyList()
        var accentWords: Set<Int> = emptySet()
        var lit = -1
        fun paintOath(n: Int) {
            if (n == lit) return
            lit = n
            val out = android.text.SpannableStringBuilder()
            words.forEachIndexed { i, w ->
                if (i > 0) out.append(' ')
                val color = when {
                    i >= n -> text3
                    i in accentWords -> attrColor(R.attr.rrAccent)
                    else -> attrColor(R.attr.rrText)
                }
                out.append(w, android.text.style.ForegroundColorSpan(color), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            oath.text = out
        }

        var lockedIn = false
        var current = cfg.start
        val chips = ArrayList<TextView>()
        lateinit var hold: HoldButton
        var shown = -1
        fun refresh(v: Int) {
            if (v == shown) return
            shown = v
            val level = cfg.level(v)
            number.text = cfg.big(v)
            number.setTextColor(moodColor(level))
            Ui.bump(number)
            unit.text = " " + cfg.unit(v)
            sub.text = cfg.sub(v)
            sub.setTextColor(if (cfg.isValid(v) && level >= 2) moodColor(level) else text3)
            readout.contentDescription = "${number.text} ${unit.text.trim()}. ${sub.text}"
            chips.forEach { it.isSelected = it.tag == v }
            hold.isEnabled = cfg.isValid(v)
            if (cfg.isValid(v)) current = v
            val oathText = cfg.oath(v)
            words = oathText.split(' ')
            // The number's words ("100", or "2 h 30 min") light in the accent colour.
            val numberWords = cfg.big(v).split(' ')
            val at = (0..words.size - numberWords.size).firstOrNull { words.subList(it, it + numberWords.size) == numberWords }
            accentWords = if (at == null) emptySet() else (at until at + numberWords.size).toSet()
            oath.contentDescription = oathText
            lit = -1
            paintOath(LimitLock.litWords(hold.progress, words.size))
        }

        val lock = cfg.makeLock(cfg.start) { refresh(it) }
        val sounds = lockSounds ?: LockSounds(applicationContext).also { lockSounds = it }
        lock.sounds = sounds
        binding.body.addView(lock, Ui.gap(this, 4f))
        binding.body.addView(readout, Ui.gap(this, 4f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((v, label) in cfg.picks) {
            row.addView(Ui.chip(this, label, false) { if (!lockedIn) lock.rollTo(v) }.also {
                it.tag = v
                chips += it
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(this@OnboardingActivity, 4f)
                marginEnd = Ui.dp(this@OnboardingActivity, 4f)
            })
        }
        binding.body.addView(row, Ui.gap(this, 14f))
        binding.body.addView(View(this), Ui.gap(this, 12f))

        val relink = Ui.link(this, getString(R.string.lock_change)) {}.apply {
            textSize = 13.5f
            setTextColor(attrColor(R.attr.rrText2))
            isVisible = false
        }
        fun setLockedIn(on: Boolean) {
            lockedIn = on
            lock.setLocked(on)
            heading.text = if (on) getString(R.string.lock_title_locked) else cfg.title
            lede.text = if (on) cfg.lockedLede(current) else cfg.lede
            row.alpha = if (on) .35f else 1f
            chips.forEach { it.isEnabled = !on }
            hint.setTextColor(text3)
            hint.text = getString(if (on) R.string.lock_later else R.string.lock_hint)
            relink.isVisible = on
            paintOath(if (on) words.size else 0)
        }
        relink.setOnClickListener {
            setLockedIn(false)
            hold.reset()
            Haptics.light(this)
            sounds.unlock()
        }

        hold = HoldButton(this, object : HoldButton.Listener {
            override fun onHoldStart() {
                lock.settle()
                hint.setTextColor(text3)
                hint.text = getString(R.string.lock_keep)
            }

            override fun onHoldProgress(progress: Float) {
                lock.pressShackle(progress)
                if (!lockedIn) paintOath(LimitLock.litWords(progress, words.size))
            }

            override fun onPulse(progress: Float) = sounds.pulse(progress)

            override fun onLetGoEarly() {
                hint.setTextColor(moodColor(2))
                hint.text = getString(R.string.lock_too_soon)
            }

            override fun onLocked() {
                current = lock.value.takeIf(cfg.isValid) ?: return
                cfg.onLocked(current)
                Haptics.lockThunk(this@OnboardingActivity)
                sounds.lock()
                setLockedIn(true)
                saveProgress()
                heading.announceForAccessibility("${heading.text} ${lede.text}")
            }

            override fun onContinue() = cfg.onContinue()
        })

        val foot = binding.foot
        foot.isVisible = true
        foot.addView(oath, Ui.gap(this, 6f))
        foot.addView(hold, Ui.gap(this, 12f))
        foot.addView(hint, Ui.gap(this, 10f))
        foot.addView(relink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = Ui.dp(this@OnboardingActivity, 2f)
        })
        refresh(lock.value)
    }

    /**
     * Step 4/6, the prominent disclosure Google Play asks for before sending people to
     * Accessibility settings (design/reel-counting-permission.html): what it does and never does,
     * the 3 taps ahead, two explainers, and an explicit "I understand" before the button works.
     * The button opens Settings with a picture-in-picture guide on top ([GuideActivity]). Back here
     * without counting on, a banner says nothing changed; with it on, a short summary and Continue.
     */
    private fun access() {
        binding.body.gravity = Gravity.NO_GRAVITY
        binding.primary.isVisible = false
        if (MainActivity.isServiceEnabled(this)) {
            accessOn()
            return
        }
        title(getString(R.string.onb_access_title))
        para(getString(R.string.onb_access_text))
        if (askedAccess) binding.body.addView(stillOffBanner(), Ui.gap(this, 4f))

        // What it does and never does, with "never leaves your phone" as the card's footer.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_bg)
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 6f), Ui.dp(context, 16f), Ui.dp(context, 2f))
            contentDescription = null
        }
        fun divider() = card.addView(View(this).apply { setBackgroundColor(attrColor(R.attr.rrLine)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1f)))
        fun line(ok: Boolean, text: Int, sub: Int? = null) {
            if (card.childCount > 0) divider()
            card.addView(iconRow(if (ok) R.drawable.ic_check else R.drawable.ic_cross,
                if (ok) attrColor(R.attr.rrAccent) else moodColor(3), getString(text), 14.5f, attrColor(R.attr.rrText), 18f,
                sub?.let { getString(it) }))
        }
        // Google Play prominent disclosure: every use of the Accessibility service is listed here
        // (counting, ad labels, Iris, and Back/Home to close Reels). Keep in step with
        // service_description, the "full control" sheet and privacy_text.
        line(true, R.string.onb_access_yes1)
        line(true, R.string.onb_access_yes2)
        line(true, R.string.onb_access_yes3, R.string.onb_access_yes3_sub)
        line(false, R.string.onb_access_no1)
        divider()
        card.addView(iconRow(R.drawable.ic_lock, attrColor(R.attr.rrAccent), getString(R.string.onb_access_private), 13f, attrColor(R.attr.rrText2), 16f))
        binding.body.addView(card, Ui.gap(this, 16f))

        // "3 taps in Settings": the path as chips, and the floating guide that comes along.
        binding.body.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_access_taps).uppercase()).apply {
            textSize = 11f
            letterSpacing = 0.14f
            fontVariationSettings = "'wght' 700"
        }, Ui.gap(this, 18f))
        val crumbs = Ui.chipFlow(this).apply { contentDescription = getString(R.string.onb_access_crumbs) }
        val names = listOf(getString(R.string.onb_access_crumb1), getString(R.string.app_name), getString(R.string.onb_access_crumb3))
        names.forEachIndexed { i, name ->
            val last = i == names.lastIndex
            crumbs.addView(TextView(this).apply {
                text = if (last) name else "$name  ›"
                textSize = 12.5f
                fontVariationSettings = "'wght' 600"
                setTextColor(if (last) attrColor(R.attr.rrAccent) else attrColor(R.attr.rrText))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(context, 9f).toFloat()
                    setColor(if (last) androidx.core.graphics.ColorUtils.setAlphaComponent(attrColor(R.attr.rrAccent), 41) else attrColor(R.attr.rrRaised))
                }
                setPadding(Ui.dp(context, 9f), Ui.dp(context, 6f), Ui.dp(context, 9f), Ui.dp(context, 6f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        binding.body.addView(crumbs, Ui.gap(this, 10f))
        binding.body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pipThumb(), LinearLayout.LayoutParams(Ui.dp(context, 52f), Ui.dp(context, 68f)))
            addView(Ui.text(context, R.style.Rr_Text_Body).apply {
                textSize = 13.5f
                setTextColor(attrColor(R.attr.rrText2))
                text = android.text.SpannableStringBuilder().apply {
                    append(getString(R.string.onb_access_guide_title), android.text.style.ForegroundColorSpan(attrColor(R.attr.rrText)), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(" ")
                    append(getString(R.string.onb_access_guide_text))
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(context, 12f) })
        }, Ui.gap(this, 10f))

        binding.body.addView(Ui.link(this, getString(R.string.onb_access_full_link)) { fullControlSheet() }.apply { textSize = 13.5f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@OnboardingActivity, 14f) })
        binding.body.addView(Ui.link(this, getString(R.string.onb_access_grey_link)) { greyedOutSheet() }.apply { textSize = 13.5f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Pinned: the consent box, the button (looks off until ticked, but still answers), a hint.
        val box = View(this).apply { background = getDrawable(R.drawable.checkbox_bg) }
        val tick = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = android.content.res.ColorStateList.valueOf(attrColor(R.attr.rrOnAccent))
        }
        val check = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.FrameLayout(context).apply {
                addView(box, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 24f), Ui.dp(context, 24f)))
                addView(tick, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 15f), Ui.dp(context, 15f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(Ui.dp(context, 24f), Ui.dp(context, 24f)))
            addView(Ui.text(context, R.style.Rr_Text_Body, getString(R.string.onb_access_agree)).apply {
                textSize = 14f
                setPadding(Ui.dp(context, 12f), 0, 0, 0)
            })
            setPadding(Ui.dp(context, 4f), Ui.dp(context, 6f), Ui.dp(context, 4f), Ui.dp(context, 6f))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val cta = com.google.android.material.button.MaterialButton(this).apply { text = getString(R.string.enable_button) }
        val hint = Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_access_hint)).apply { gravity = Gravity.CENTER }
        fun sync() {
            if (agreed) box.background = getDrawable(R.drawable.checkbox_bg)
            box.isSelected = agreed
            tick.isVisible = agreed
            cta.alpha = if (agreed) 1f else 0.4f
            check.contentDescription = getString(R.string.onb_access_agree)
            check.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.CheckBox::class.java.name
                    info.isCheckable = true
                    info.isChecked = agreed
                }
            }
            if (agreed) {
                hint.setTextColor(attrColor(R.attr.rrText3))
                hint.text = getString(R.string.onb_access_hint)
            }
        }
        check.setOnClickListener {
            agreed = !agreed
            saveProgress()
            sync()
        }
        cta.setOnClickListener {
            if (!agreed) {
                // Not yet: shake the box and say why, instead of a button that does nothing.
                if (!CountStore.reduceMotion(this)) {
                    val d = Ui.dp(this, 1f).toFloat()
                    android.animation.ObjectAnimator.ofFloat(check, View.TRANSLATION_X, 0f, -5 * d, 5 * d, -3 * d, 2 * d, 0f).setDuration(400).start()
                }
                box.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(this@OnboardingActivity, 6f).toFloat()
                    setStroke(Ui.dp(this@OnboardingActivity, 2f), moodColor(2))
                }
                hint.setTextColor(moodColor(2))
                hint.text = getString(R.string.onb_access_tick_first)
                hint.announceForAccessibility(hint.text)
                Haptics.light(this)
                return@setOnClickListener
            }
            askedAccess = true
            GuideActivity.open(this)
        }
        sync()
        val foot = binding.foot
        foot.isVisible = true
        foot.addView(check, Ui.gap(this, 10f))
        foot.addView(cta, Ui.gap(this, 10f))
        foot.addView(hint, Ui.gap(this, 4f))
    }

    /** Counting is on: what was allowed, how to undo it, and Continue to step 5. */
    private fun accessOn() {
        binding.body.addView(android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(context, 20f).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(attrColor(R.attr.rrAccent), 41))
            }
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(attrColor(R.attr.rrAccent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 32f), Ui.dp(context, 32f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(Ui.dp(this, 64f), Ui.dp(this, 64f)).apply {
            topMargin = Ui.dp(this@OnboardingActivity, 8f)
            marginStart = Ui.dp(this@OnboardingActivity, 4f)
        })
        binding.body.addView(Ui.text(this, R.style.Rr_Text_OnbTitle, getString(R.string.onb_access_on_title)), Ui.gap(this, 18f))
        para(getString(R.string.onb_access_on_text))
        val receipt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_bg)
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 4f), Ui.dp(context, 16f), Ui.dp(context, 4f))
        }
        listOf(
            R.string.onb_access_r1 to R.string.onb_access_on,
            R.string.onb_access_r2 to R.string.onb_access_on,
            R.string.onb_access_r_close to R.string.onb_access_on,
            R.string.onb_access_r3 to R.string.onb_access_never,
            R.string.onb_access_r4 to R.string.onb_access_where,
        ).forEachIndexed { i, (label, value) ->
            if (i > 0) receipt.addView(View(this).apply { setBackgroundColor(attrColor(R.attr.rrLine)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1f)))
            receipt.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(context, 11f), 0, Ui.dp(context, 11f))
                addView(Ui.text(context, R.style.Rr_Text_Body, getString(label)).apply {
                    textSize = 14f
                    setTextColor(attrColor(R.attr.rrText2))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(context, R.style.Rr_Text_Body, getString(value)).apply {
                    textSize = 14f
                    gravity = Gravity.END
                    fontVariationSettings = "'wght' 600"
                    setTextColor(if (value == R.string.onb_access_on) attrColor(R.attr.rrAccent) else attrColor(R.attr.rrText))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 12f) })
            })
        }
        binding.body.addView(receipt, Ui.gap(this, 16f))
        val foot = binding.foot
        foot.isVisible = true
        foot.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.onb_continue)
            setOnClickListener { show(Step.RUNNING, 1) }
        }, Ui.gap(this, 10f))
        foot.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_access_on_hint)).apply { gravity = Gravity.CENTER }, Ui.gap(this, 4f))
    }

    /** A card row: a tinted icon, a line of text and an optional smaller second line (design .card li). */
    private fun iconRow(icon: Int, tint: Int, text: CharSequence, size: Float, color: Int, iconDp: Float, sub: CharSequence? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, Ui.dp(context, 11f), 0, Ui.dp(context, 11f))
        addView(android.widget.ImageView(context).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(context, iconDp), Ui.dp(context, iconDp)).apply { topMargin = Ui.dp(context, 1f) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, R.style.Rr_Text_Body, text).apply {
                textSize = size
                setTextColor(color)
            })
            if (sub != null) addView(Ui.text(context, R.style.Rr_Text_Muted, sub).apply { textSize = 12.5f }, Ui.gap(context, 2f))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(context, 12f) })
    }

    /** "Counting is still off. Nothing changed on your phone…" (design .banner). */
    private fun stillOffBanner() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val warn = moodColor(2)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Ui.dp(context, 16f).toFloat()
            setColor(androidx.core.graphics.ColorUtils.compositeColors(androidx.core.graphics.ColorUtils.setAlphaComponent(warn, 26), attrColor(R.attr.rrSurface)))
            setStroke(Ui.dp(context, 1f), androidx.core.graphics.ColorUtils.setAlphaComponent(warn, 89))
        }
        setPadding(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 12f))
        addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_info)
            imageTintList = android.content.res.ColorStateList.valueOf(warn)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(context, 20f), Ui.dp(context, 20f)))
        addView(Ui.text(context, R.style.Rr_Text_Body).apply {
            textSize = 13.5f
            text = android.text.SpannableStringBuilder().apply {
                append(getString(R.string.onb_access_still_off), android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" ")
                append(getString(R.string.onb_access_still_off_text))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(context, 10f) })
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** The little picture-in-picture icon next to "A small video guide comes with you" (design .pipthumb). */
    private fun pipThumb() = android.widget.FrameLayout(this).apply {
        background = getDrawable(R.drawable.card_bg)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(context, 5f).toFloat()
                setColor(attrColor(R.attr.rrAccent))
            }
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_play)
                imageTintList = android.content.res.ColorStateList.valueOf(attrColor(R.attr.rrOnAccent))
            }, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 14f), Ui.dp(context, 14f), Gravity.CENTER))
        }, android.widget.FrameLayout.LayoutParams(Ui.dp(context, 24f), Ui.dp(context, 31f), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = Ui.dp(context, 5f)
            bottomMargin = Ui.dp(context, 5f)
        })
    }

    /** Android's "full control" wording, line by line, in Rokoreels' terms. */
    private fun fullControlSheet() = Ui.sheet(this, getString(R.string.full_title), button = getString(R.string.got_it)) { box ->
        box.addView(Ui.text(this, R.style.Rr_Text_Body, getString(R.string.full_text)).apply {
            textSize = 14f
            setTextColor(attrColor(R.attr.rrText2))
        }, Ui.gap(this, 8f))
        for ((q, a) in listOf(R.string.full_q1 to R.string.full_a1, R.string.full_q2 to R.string.full_a2)) {
            box.addView(Ui.text(this, R.style.Rr_Text_Body, getString(q)).apply {
                textSize = 14.5f
                fontVariationSettings = "'wght' 600"
            }, Ui.gap(this, 14f))
            box.addView(Ui.text(this, R.style.Rr_Text_Body, getString(a)).apply {
                textSize = 13.5f
                setTextColor(attrColor(R.attr.rrText2))
            }, Ui.gap(this, 4f))
        }
    }

    /** Android 13+ "restricted setting" for apps installed from a file: how to allow it. */
    private fun greyedOutSheet() = Ui.sheet(this, getString(R.string.grey_title), button = getString(R.string.grey_button), onButton = {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }) { box ->
        box.addView(Ui.text(this, R.style.Rr_Text_Body, getString(R.string.grey_text)).apply {
            textSize = 14f
            setTextColor(attrColor(R.attr.rrText2))
        }, Ui.gap(this, 8f))
        val steps = listOf(R.string.grey_s1, R.string.grey_s2, R.string.grey_s3, R.string.grey_s4)
        steps.forEachIndexed { i, s ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Ui.dp(context, 14f).toFloat()
                    setColor(attrColor(R.attr.rrRaised))
                }
                setPadding(Ui.dp(context, 12f), Ui.dp(context, 11f), Ui.dp(context, 12f), Ui.dp(context, 11f))
                addView(TextView(context).apply {
                    text = (i + 1).toString()
                    gravity = Gravity.CENTER
                    typeface = resources.getFont(R.font.bricolage_grotesque)
                    fontVariationSettings = "'wght' 800"
                    textSize = 14f
                    setTextColor(attrColor(R.attr.rrAccent))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(attrColor(R.attr.rrBg))
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(Ui.dp(context, 28f), Ui.dp(context, 28f)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(context, R.style.Rr_Text_Body, getText(s)).apply { textSize = 14f })
                    if (s == R.string.grey_s3) addView(Ui.text(context, R.style.Rr_Text_Muted, getString(R.string.grey_s3_sub)).apply { textSize = 12f })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(context, 12f) })
            }, Ui.gap(this, if (i == 0) 12f else 8f))
        }
    }

    /** Notifications keep the counter alive; status shows in place. */
    private fun running() {
        binding.body.gravity = Gravity.NO_GRAVITY
        title(getString(R.string.onb_running_title))
        para(getString(R.string.onb_running_text))
        val list = Ui.listCard(this)
        val notif = MainActivity.notificationsAllowed(this)
        Ui.setRow(list, R.drawable.ic_bell, getString(R.string.notifications), getString(R.string.onb_notif_sub),
            listOf(if (notif) Ui.status(this, getString(R.string.allowed), true) else Ui.link(this, getString(R.string.allow)) { askNotifications() }))
        binding.body.addView(list, Ui.gap(this, 18f))
        if (closesBackgroundApps()) {
            binding.body.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_brand_tip)).apply {
                setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
            }, Ui.gap(this, 12f))
        }
        // The ongoing notification keeps counting alive, so Continue waits for it.
        if (!notif) {
            binding.body.addView(Ui.text(this, R.style.Rr_Text_Muted, getString(R.string.onb_running_required)).apply {
                setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
            }, Ui.gap(this, 12f))
        }
        primary(getString(R.string.onb_continue), enabled = notif) { show(Step.IRIS, 1) }
    }

    private fun deviceState() = getSharedPreferences(CountStore.DEVICE_PREFS, MODE_PRIVATE)

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (deviceState().getBoolean(KEY_NOTIF_ASKED, false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Refused for good: Android won't show its prompt again, so open the settings.
            MainActivity.openNotificationSettings(this)
            return
        }
        deviceState().edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIF && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Analytics.permissionGranted(this, "notifications")
        }
        if (requestCode == REQUEST_NOTIF && step == Step.RUNNING) show(Step.RUNNING, 0)
    }

    /**
     * The first permission step whose permission is missing, at or before [s]: progress saved by
     * an older version (where these steps could be skipped) lands back on the missing one.
     */
    private fun firstMissing(s: Step): Step = when {
        s > Step.WEEK && !UsageRepository.hasAccess(this) -> Step.WEEK
        s > Step.ACCESS && !MainActivity.isServiceEnabled(this) -> Step.ACCESS
        s > Step.RUNNING && !MainActivity.notificationsAllowed(this) -> Step.RUNNING
        else -> s
    }

    /** Teach the one idea: Iris gets worried as you scroll. Three faces, no tutorial. */
    private fun meetIris() {
        binding.body.gravity = Gravity.CENTER_HORIZONTAL
        title(getString(R.string.onb_iris_title), center = true)
        para(getString(R.string.onb_iris_text), center = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((mood, label) in listOf(Mood.HAPPY to R.string.face_calm, Mood.WORRIED to R.string.face_worried, Mood.ANGRY to R.string.face_limit)) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(face(mood, 104f))
                addView(Ui.text(context, R.style.Rr_Text_Muted, getString(label)), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 4f) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        binding.body.addView(row, Ui.gap(this, 22f))
        // Signed in already ("I already have an account" on Welcome): nothing left to do.
        if (Account.current(this) != null) primary(getString(R.string.go_home)) { finishOnboarding() }
        else primary(getString(R.string.onb_continue)) { show(Step.SIGNIN, 1) }
    }

    /**
     * Step 7/7: sign in with Google (required, owner's decision 2026-11-08). Onboarding finishes
     * only once Firebase has a user; closing the picker or an error keeps you here. A build with
     * no Firebase config can't sign anyone in, so it says so and lets you through instead of
     * locking you out of the app. Sign-in only: counts and settings stay on the phone.
     */
    private fun signInStep() {
        binding.body.gravity = Gravity.CENTER_HORIZONTAL
        binding.body.addView(face(Mood.HAPPY, 150f).apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = Ui.dp(context, 24f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        title(getString(R.string.onb_signin_title), center = true)
        para(getString(R.string.onb_signin_text), center = true)
        val user = Account.current(this)
        when {
            user != null -> {
                para(getString(R.string.signed_in_as, Account.label(user)), center = true)
                primary(getString(R.string.go_home)) { finishOnboarding() }
            }
            !Account.isSetUp(this) -> {
                para(getString(R.string.sign_in_not_set_up), center = true)
                primary(getString(R.string.go_home)) { finishOnboarding() }
            }
            else -> primary(getString(R.string.onb_signin_button)) { startSignIn() }
        }
    }

    private fun startSignIn() {
        binding.primary.isEnabled = false
        binding.primary.text = getString(R.string.onb_signin_wait)
        Account.signIn(this) { user, error ->
            if (isFinishing || isDestroyed || step != Step.SIGNIN) return@signIn
            if (user != null) {
                Toast.makeText(this, getString(R.string.signed_in_as, Account.label(user)), Toast.LENGTH_SHORT).show()
                finishOnboarding()
            } else {
                // Closed the picker (no error) or failed: stay, ready to try again.
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                binding.primary.isEnabled = true
                binding.primary.text = getString(R.string.onb_signin_button)
            }
        }
    }

    private fun finishOnboarding() {
        CountStore.setLimit(this, limit)
        CountStore.setTimeLimitMinutes(this, timeLimit)
        CountStore.setOnboarded(this)
        Analytics.onboardingComplete(this)
        if (MainActivity.isServiceEnabled(this)) KeepAliveService.start(this)
        startActivity(Intent(this, MainActivity::class.java))
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    /** Every Iris on the screen watches the finger. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            faces.forEach { it.watch(ev.rawX, ev.rawY) }
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private const val REQUEST_NOTIF = 7
        /** Steps in the progress bar (Welcome isn't one). */
        private const val STEPS = 7
        /** Kept on this phone only (not backed up): a new phone should get the real prompt. */
        private const val KEY_NOTIF_ASKED = "notif_asked"

        /** Phones from brands known for closing background apps. */
        fun closesBackgroundApps(): Boolean {
            val brand = Build.MANUFACTURER.lowercase()
            return listOf("oneplus", "oppo", "realme", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "samsung").any { brand.contains(it) }
        }
    }
}
