package com.reelcounter

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Native haptics for the onboarding lock: the phone's own tick/click primitives where it has
 * them (Android 11+), predefined effects on 10, short one-shots on 8–9. Touch-feedback usage, so
 * Android's "vibration & haptics" settings still apply on 13+.
 */
object Haptics {

    /** Dial strengths (design: hundreds heaviest, ones lightest). Index 0 = hundreds. */
    private val DIAL_SCALE = floatArrayOf(1f, 0.65f, 0.35f)
    private val DIAL_MS = longArrayOf(16, 11, 7)

    private fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }?.takeIf { it.hasVibrator() }

    private fun play(ctx: Context, effect: VibrationEffect) {
        val v = vibrator(ctx) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        } catch (_: Exception) {
            // Some OEM builds throw on primitives they claim to support: haptics are a nicety.
        }
    }

    private fun primitive(ctx: Context, id: Int, scale: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val v = vibrator(ctx) ?: return false
        if (!v.areAllPrimitivesSupported(id)) return false
        play(ctx, VibrationEffect.startComposition().addPrimitive(id, scale.coerceIn(0f, 1f)).compose())
        return true
    }

    private fun oneShot(ctx: Context, ms: Long, amplitude: Int) {
        val v = vibrator(ctx) ?: return
        play(ctx, VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** One number passing on dial [place] (0 hundreds, 1 tens, 2 ones). */
    @android.annotation.SuppressLint("InlinedApi") // primitive ids are constants, only used on 30+
    fun dialTick(ctx: Context, place: Int) {
        val i = place.coerceIn(0, 2)
        if (primitive(ctx, if (i == 0) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK, DIAL_SCALE[i])) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            play(ctx, VibrationEffect.createPredefined(if (i == 0) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK))
            return
        }
        oneShot(ctx, DIAL_MS[i], (255 * DIAL_SCALE[i]).toInt())
    }

    /** A pulse while the hold button fills; stronger as [progress] goes to 1. */
    fun holdPulse(ctx: Context, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (primitive(ctx, VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f + 0.7f * p)) return
        oneShot(ctx, (8 + 18 * p).toLong(), (90 + 165 * p).toInt())
    }

    /** The shackle snapping shut. */
    fun lockThunk(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val v = vibrator(ctx)
            if (v != null && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val c = VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 40)
                } else {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 60)
                }
                play(ctx, c.compose())
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            play(ctx, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            return
        }
        play(ctx, VibrationEffect.createWaveform(longArrayOf(0, 35, 60, 90), -1))
    }

    /** A light tap: unlocking, or a nudge when something needs attention first. */
    fun light(ctx: Context) {
        if (primitive(ctx, VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)) return
        oneShot(ctx, 10, 120)
    }
}
