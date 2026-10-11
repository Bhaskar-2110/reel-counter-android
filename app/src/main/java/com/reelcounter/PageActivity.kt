package com.reelcounter

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A screen one level down: back button, title and scrolling content that [build] fills. The
 * content is rebuilt when the screen comes back and whenever a setting changes, so every
 * screen always shows current values; cards rise in only the first time.
 */
abstract class PageActivity : BaseActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    protected lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var built = false
    private var pending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)
        content = findViewById(R.id.content)
        scroll = findViewById(R.id.pageScroll)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        if (!isFinishing) setPageTitle(title())
    }

    /** The page's title; may change on rebuild via [setPageTitle]. */
    abstract fun title(): CharSequence

    /** Fills [content]. */
    abstract fun build()

    fun setPageTitle(text: CharSequence) {
        findViewById<TextView>(R.id.pageTitle).text = text
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        CountStore.prefs(this).registerOnSharedPreferenceChangeListener(this)
        rebuild()
    }

    override fun onPause() {
        CountStore.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /**
     * Settings can change several keys at once; rebuild once, after a switch that was just
     * flipped has finished sliding (the design waits 260 ms too).
     */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (pending) return
        pending = true
        content.postDelayed({
            pending = false
            if (!isFinishing && !isDestroyed) rebuild()
        }, REBUILD_DELAY_MS)
    }

    private companion object {
        const val REBUILD_DELAY_MS = 260L
    }

    protected fun rebuild() {
        val y = scroll.scrollY
        content.removeAllViews()
        build()
        if (!built) {
            built = true
            Ui.riseChildren(content)
        } else {
            scroll.post { scroll.scrollTo(0, y) }
        }
    }

    // Shorthands for building pages

    /** A section label (design ".ghd"). */
    fun section(text: Int) = section(getString(text))

    fun section(text: CharSequence) {
        content.addView(Ui.text(this, R.style.Rr_Text_Label, text).apply {
            textSize = 11f
            letterSpacing = 0.1f
        }, Ui.gap(this, 16f).apply { marginStart = Ui.dp(this@PageActivity, 4f) })
    }

    /** A list card added to the page. */
    fun list(topDp: Float = 8f): LinearLayout = Ui.listCard(this).also { content.addView(it, Ui.gap(this, topDp)) }

    /** A padded card added to the page. */
    fun card(topDp: Float = 10f): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.card_bg)
        val p = Ui.dp(context, 14f)
        setPadding(p, p, p, p)
    }.also { content.addView(it, Ui.gap(this, topDp)) }

    fun intro(text: CharSequence) {
        content.addView(Ui.text(this, R.style.Rr_Text_Muted, text).apply {
            textSize = 13.5f
            setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
        }, Ui.gap(this, 2f))
    }
}
