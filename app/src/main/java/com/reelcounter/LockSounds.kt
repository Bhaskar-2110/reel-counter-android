package com.reelcounter

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The combination lock's sounds, made the same way as design/reel-limit-lock.html: a click is
 * 40 ms of fading noise through a band-pass filter (pitch per dial: hundreds 1500 Hz, tens
 * 2300 Hz, ones 3200 Hz), and locking is a falling triangle "thunk" (180 → 52 Hz) with a click.
 * The sounds are rendered once to small WAV files in the cache and played with [SoundPool].
 *
 * Quiet unless the phone allows it: only with Android's "Touch sounds" on and the ringer on
 * normal (not silent or vibrate), so the haptics carry it otherwise.
 */
class LockSounds(private val ctx: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val loaded = HashSet<Int>()
    private val ids = HashMap<String, Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        try {
            DIAL_FREQ.forEachIndexed { i, f -> load("dial$i") { click(f, 0.45f) } }
            for (step in 0 until PULSE_STEPS) {
                val p = step / (PULSE_STEPS - 1f)
                load("pulse$step") { click(600f + 900f * p, 0.25f + 0.3f * p) }
            }
            load("unlock") { click(2000f, 0.4f) }
            load("thunk") { thunk() }
        } catch (_: Exception) {
            // No cache space or no audio: the lock still works with haptics alone.
        }
    }

    /** A number passing on dial [place] (0 hundreds, 1 tens, 2 ones). */
    fun dial(place: Int) = play("dial${place.coerceIn(0, 2)}")

    /** A pulse while holding; higher and louder as [progress] goes to 1. */
    fun pulse(progress: Float) = play("pulse${(progress.coerceIn(0f, 1f) * (PULSE_STEPS - 1)).roundToInt()}")

    fun lock() = play("thunk")
    fun unlock() = play("unlock")

    fun release() = try {
        pool.release()
    } catch (_: Exception) {
    }

    private fun allowed(): Boolean = try {
        val touchSounds = Settings.System.getInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1
        val audio = ctx.getSystemService(AudioManager::class.java)
        touchSounds && (audio == null || audio.ringerMode == AudioManager.RINGER_MODE_NORMAL)
    } catch (_: Exception) {
        false
    }

    private fun play(name: String) {
        val id = ids[name] ?: return
        if (id !in loaded || !allowed()) return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    private fun load(name: String, make: () -> FloatArray) {
        val dir = File(ctx.cacheDir, "lock_sounds").apply { mkdirs() }
        val file = File(dir, "v1_$name.wav")
        if (!file.exists() || file.length() == 0L) writeWav(file, make())
        ids[name] = pool.load(file.path, 1)
    }

    companion object {
        private const val RATE = 44100
        private const val PULSE_STEPS = 8
        private val DIAL_FREQ = floatArrayOf(1500f, 2300f, 3200f)

        /** design click(): fading noise (envelope (1 − t)^4, 40 ms) through a band-pass, Q 6. */
        fun click(freq: Float, volume: Float): FloatArray {
            val n = (RATE * 0.04).toInt()
            val rnd = java.util.Random(freq.toLong()) // the same click every time
            val noise = FloatArray(n) { i -> (rnd.nextFloat() * 2f - 1f) * (1f - i / n.toFloat()).pow(4) }
            val out = bandPass(noise, freq, 6f)
            return normalize(out, volume)
        }

        /** design thunk(): triangle sliding 180 → 52 Hz over 0.18 s, 8 ms attack, fading by 0.32 s, plus a click. */
        fun thunk(): FloatArray {
            val n = (RATE * 0.35).toInt()
            val out = FloatArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i / RATE.toDouble()
                val f = if (t < 0.18) 180.0 * (52.0 / 180.0).pow(t / 0.18) else 52.0
                phase += f / RATE
                val tri = 4.0 * abs(phase - kotlin.math.floor(phase + 0.5)) - 1.0
                val gain = when {
                    t < 0.008 -> 0.0001 * exp(ln(0.6 / 0.0001) * t / 0.008)
                    t < 0.32 -> 0.6 * exp(ln(0.0001 / 0.6) * (t - 0.008) / (0.32 - 0.008))
                    else -> 0.0
                }
                out[i] = (tri * gain).toFloat()
            }
            val c = click(1200f, 0.9f)
            for (i in c.indices) out[i] = (out[i] + c[i]).coerceIn(-1f, 1f)
            return out
        }

        /** RBJ band-pass biquad (constant 0 dB peak). */
        fun bandPass(x: FloatArray, freq: Float, q: Float): FloatArray {
            val w = 2.0 * PI * freq / RATE
            val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = alpha / a0
            val b2 = -alpha / a0
            val a1 = -2.0 * cos(w) / a0
            val a2 = (1.0 - alpha) / a0
            val y = FloatArray(x.size)
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in x.indices) {
                val v = b0 * x[i] + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x[i].toDouble()
                y2 = y1
                y1 = v
                y[i] = v.toFloat()
            }
            return y
        }

        private fun normalize(x: FloatArray, peak: Float): FloatArray {
            val max = x.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: return x
            val k = peak / max
            return FloatArray(x.size) { x[it] * k }
        }

        /** 16-bit mono PCM WAV. */
        fun wavBytes(samples: FloatArray): ByteArray {
            val data = samples.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
            return b.array()
        }

        private fun writeWav(file: File, samples: FloatArray) = FileOutputStream(file).use { it.write(wavBytes(samples)) }
    }
}
