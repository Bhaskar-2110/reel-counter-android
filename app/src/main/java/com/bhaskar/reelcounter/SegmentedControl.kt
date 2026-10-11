package com.bhaskar.reelcounter

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The design's segmented control (".seg"): equal segments on a surface, the chosen one on a
 * raised thumb that slides across when the choice changes.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val thumb = View(context).apply { background = context.getDrawable(R.drawable.seg_thumb) }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var selected = 0
    private var listener: ((Int) -> Unit)? = null

    init {
        background = context.getDrawable(R.drawable.seg_bg)
        val pad = Ui.dp(context, 3f)
        setPadding(pad, pad, pad, pad)
        addView(thumb, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setItems(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        listener = onSelect
        this.selected = selected
        row.removeAllViews()
        labels.forEachIndexed { i, label ->
            row.addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12.5f
                fontVariationSettings = "'wght' 600"
                setTextColor(context.getColorStateList(R.color.seg_text))
                isSelected = i == selected
                setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 8f))
                Ui.pressable(this)
                setOnClickListener { select(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        requestLayout()
    }

    fun select(i: Int) {
        if (i == selected) return
        selected = i
        for (k in 0 until row.childCount) row.getChildAt(k).isSelected = k == i
        moveThumb(animate = true)
        listener?.invoke(i)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val n = row.childCount
        if (n == 0) return
        val w = row.width / n
        if (thumb.layoutParams.width != w) {
            thumb.layoutParams.width = w
            thumb.post { thumb.requestLayout() }
        }
        if (thumb.animation == null && !thumbMoving) moveThumb(animate = false)
    }

    private var thumbMoving = false

    private fun moveThumb(animate: Boolean) {
        val n = row.childCount
        if (n == 0 || row.width == 0) return
        val x = row.width / n * selected.toFloat()
        if (!animate || CountStore.reduceMotion(context)) {
            thumb.translationX = x
            return
        }
        thumbMoving = true
        thumb.animate().translationX(x).setDuration(260).setInterpolator(Ui.EASE)
            .withEndAction { thumbMoving = false }.start()
    }
}
