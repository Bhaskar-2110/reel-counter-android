package com.reelcounter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.drawable.toBitmap
import com.reelcounter.IrisLayout
import com.reelcounter.IrisRenderer
import com.reelcounter.IrisRig
import com.reelcounter.Mood
import com.reelcounter.Platform
import com.reelcounter.Ui
import kotlin.math.max
import kotlin.math.min

/**
 * Draws pictures for widgets: Iris in her ring (as on Home), the week's bars and app icons.
 * Widgets can't run the live animation, so Iris is a still frame of the same rig, settled on
 * her mood.
 *
 * Everything a widget shows travels to the home screen in one message with a size limit, so
 * pictures are kept small.
 */
object WidgetRenderer {

    /** Home's hero in miniature: the track, the mood-coloured arc for [progress] and Iris inside. */
    fun ringWithIris(ctx: Context, mood: Mood, progress: Float, theme: WidgetTheme, size: Int = 240): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = size * 0.075f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val box = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
        paint.color = theme.track
        canvas.drawArc(box, 0f, 360f, false, paint)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0.003f) {
            paint.color = theme.mood(progress)
            canvas.drawArc(box, -90f, 360f * p, false, paint)
        }
        val rig = IrisRig(reduceMotion = true).apply {
            locked = mood
            hold(0f, 0f)
            settle()
        }
        IrisRenderer(ctx).draw(canvas, rig, IrisLayout.square(size * 0.5f), size / 2f, size / 2f)
        return bitmap
    }

    /** The last 7 days, today last in the mood colour, days over the limit in red, a dashed limit line. */
    fun weekBars(values: List<Int>, limit: Int, theme: WidgetTheme, todayColor: Int, width: Int = 420, height: Int = 110): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val gap = width * 0.03f
        val barW = (width - gap * (values.size - 1)) / values.size
        val scale = max(max(limit, values.maxOrNull() ?: 0), 1) * 1.15f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = min(barW / 2, height * 0.08f)
        values.forEachIndexed { i, v ->
            val h = max(height * v / scale, if (v > 0) 3f else 2f)
            paint.color = when {
                v >= limit -> theme.moods[4]
                i == values.lastIndex -> todayColor
                else -> (theme.moods[1] and 0x00FFFFFF) or (0xBF shl 24)
            }
            val x = i * (barW + gap)
            canvas.drawRoundRect(x, height - h, x + barW, height.toFloat(), r, r, paint)
        }
        val y = height - height * limit / scale
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, height / 60f)
            color = (theme.moods[4] and 0x00FFFFFF) or (0xB3 shl 24)
            pathEffect = DashPathEffect(floatArrayOf(height / 14f, height / 20f), 0f)
        }
        canvas.drawLine(0f, y, width.toFloat(), y, dash)
        return bitmap
    }

    /** [platform]'s app icon at [size] pixels, or null when it isn't installed. */
    fun appIcon(ctx: Context, platform: Platform, size: Int = 56): Bitmap? =
        Ui.appIcon(ctx, platform)?.toBitmap(size, size)
}
