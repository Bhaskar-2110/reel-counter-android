package com.bhaskar.reelcounter.widget

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.drawable.toBitmap
import com.bhaskar.reelcounter.IrisLayout
import com.bhaskar.reelcounter.IrisRenderer
import com.bhaskar.reelcounter.IrisRig
import com.bhaskar.reelcounter.Mood
import com.bhaskar.reelcounter.Platform
import com.bhaskar.reelcounter.R
import kotlin.math.max
import kotlin.math.min

/**
 * Draws pictures for widgets: Iris, a 7-day chart and app icons. Widgets can't run the live
 * animation, so Iris is still frames; widgets with room for it flip between a few frames
 * (looking ahead, left, right) to make her glance around.
 *
 * Everything a widget shows travels to the home screen in one message with a size limit, so
 * pictures are kept small.
 */
object WidgetRenderer {

    /** Where the eyes look in each frame, in flip order. */
    private val GLANCES = floatArrayOf(0f, -0.85f, 0.85f)

    /** One picture per glance, [width] × [height] pixels. */
    fun eyeFrames(ctx: Context, mood: Mood, width: Int = 240, height: Int = 120): List<Bitmap> {
        val renderer = IrisRenderer(ctx)
        return GLANCES.map { look -> stillEyes(ctx, mood, look, width, height, renderer) }
    }

    /**
     * Iris in her dark island, in [mood], looking toward [look] (-1 left … 1 right): the same
     * rig as the app, settled on the mood with the eyes open.
     */
    fun stillEyes(
        ctx: Context,
        mood: Mood,
        look: Float = 0f,
        width: Int = 240,
        height: Int = 120,
        renderer: IrisRenderer = IrisRenderer(ctx),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rig = IrisRig(reduceMotion = true).apply {
            locked = mood
            hold(look, 0f)
            settle()
        }
        // Leave room for the glow around the island.
        val size = min(width, height) * 0.8f
        renderer.draw(Canvas(bitmap), rig, IrisLayout.square(size), width / 2f, height / 2f)
        return bitmap
    }

    /**
     * Bars for the last 7 days of reels (today last, in the accent colour; days over the limit
     * in red) with a dashed line at the limit and day labels underneath.
     */
    fun weekChart(ctx: Context, values: List<Int>, labels: List<String>, limit: Int, width: Int = 440, height: Int = 120): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.getColor(R.color.widget_muted)
            textSize = height * 0.13f
            textAlign = Paint.Align.CENTER
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = height * 0.16f
        val bottom = height - text.textSize * 1.6f
        val scale = max(max(limit, values.maxOrNull() ?: 0), 1) * 1.1f
        val slot = width / values.size.toFloat()
        val barW = min(slot * 0.55f, height * 0.22f)
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2f
            val h = max((bottom - top) * v / scale, if (v > 0) 3f else 1.5f)
            bar.color = ctx.getColor(
                when {
                    i == values.lastIndex -> R.color.accent
                    v >= limit -> R.color.widget_over
                    else -> R.color.widget_track
                },
            )
            if (i != values.lastIndex && v < limit) bar.alpha = 140
            canvas.drawRoundRect(cx - barW / 2f, bottom - h, cx + barW / 2f, bottom, barW / 3f, barW / 3f, bar)
            if (v > 0) canvas.drawText(v.toString(), cx, bottom - h - text.textSize * 0.4f, text)
            canvas.drawText(labels[i], cx, height - text.textSize * 0.3f, text)
        }
        val y = bottom - (bottom - top) * limit / scale
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, height / 100f)
            color = ctx.getColor(R.color.widget_muted)
            pathEffect = DashPathEffect(floatArrayOf(height / 16f, height / 24f), 0f)
        }
        canvas.drawPath(Path().apply { moveTo(0f, y); lineTo(width.toFloat(), y) }, dash)
        return bitmap
    }

    /** [platform]'s app icon at [size] pixels, or null when it isn't installed. */
    fun appIcon(ctx: Context, platform: Platform, size: Int = 64): Bitmap? =
        platform.packages.firstNotNullOfOrNull { pkg ->
            try {
                ctx.packageManager.getApplicationIcon(pkg).toBitmap(size, size)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
}
