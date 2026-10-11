package com.reelcounter

/**
 * Iris's moods. Each one is just a target for the same set of face numbers (see
 * design_iris_docs/IRIS_EYES_SPEC.md, "Mood targets"); [IrisRig] glides between them.
 *
 * Lid values are fractions of eye height:
 * @param open how far the upper lid comes down
 * @param tilt upper lid slant; positive drops the inner corner (angry), negative the outer (sad)
 * @param upC upper lid curve; positive sags it
 * @param low how far the lower lid comes up
 * @param lowC lower lid curve; positive pushes the middle up (a smile)
 * @param sx eye width scale
 * @param sy eye height scale
 * @param pup pupil size
 * @param hl sparkle size
 * @param py added to where the eyes look vertically (positive is down)
 * @param tint the angry red in the eyes
 * @param wet the wet lower rim and tears
 * @param asym one eye bigger and the other squinting (curious)
 * @param fy island y offset, as a fraction of its size
 * @param fr island rotation, in radians
 * @param fs island scale
 * @param color the mood colour (Night Garden)
 */
enum class Mood(
    val open: Float,
    val tilt: Float,
    val upC: Float,
    val low: Float,
    val lowC: Float,
    val sx: Float,
    val sy: Float,
    val pup: Float,
    val hl: Float,
    val py: Float,
    val tint: Float,
    val wet: Float,
    val asym: Float,
    val fy: Float,
    val fr: Float,
    val fs: Float,
    val color: Int,
) {
    HAPPY(.02f, 0f, -.05f, .27f, .32f, 1.06f, .98f, 1.00f, 1.00f, -.25f, 0f, 0f, 0f, 0f, 0f, 1.00f, 0xFF4FE3B0.toInt()),
    CURIOUS(0f, -.06f, 0f, .05f, .10f, 1.00f, 1.05f, 1.06f, 1.00f, 0f, 0f, 0f, .7f, 0f, .07f, 1.01f, 0xFF5CC8FF.toInt()),
    WORRIED(.06f, -.34f, -.02f, .10f, -.05f, .95f, 1.00f, .80f, 1.00f, .05f, 0f, .2f, 0f, .01f, 0f, .99f, 0xFFFFC24D.toInt()),
    SAD(.22f, -.52f, .05f, .08f, -.15f, 1.00f, .93f, .96f, 1.10f, .6f, 0f, .85f, 0f, .045f, 0f, .97f, 0xFFFF8A65.toInt()),
    ANGRY(.16f, .52f, -.04f, .22f, 0f, 1.08f, .88f, .70f, .55f, 0f, 1f, 0f, 0f, -.01f, 0f, 1.04f, 0xFFFF4D5E.toInt()),
    SURPRISED(-.04f, -.08f, 0f, 0f, 0f, 1.00f, 1.20f, .58f, 1.00f, 0f, 0f, 0f, 0f, -.03f, 0f, 1.06f, 0xFF6CF0FF.toInt()),
    ELECTRIC(-.02f, 0f, 0f, 0f, 0f, 1.10f, 1.12f, .40f, .30f, 0f, 0f, 0f, 0f, 0f, 0f, 1.02f, 0xFFFFE45C.toInt()),
    SLEEPY(.56f, -.08f, .10f, .14f, .12f, 1.05f, .92f, .95f, .80f, .35f, 0f, 0f, 0f, .04f, -.05f, .98f, 0xFFA99BFF.toInt()),
    PLEADING(0f, -.26f, 0f, .03f, .05f, 1.08f, 1.10f, 1.45f, 1.75f, -.45f, 0f, .6f, 0f, -.015f, 0f, 1.05f, 0xFFFF7AD9.toInt());

    companion object {
        /** The mood band for how close the user is to their limit (1 = reached), for labels. */
        fun forProgress(progress: Float): Mood = when {
            progress >= 1f -> ANGRY
            progress >= 0.85f -> SAD
            progress >= 0.6f -> WORRIED
            progress >= 0.3f -> CURIOUS
            else -> HAPPY
        }

        /** Mixes two colours; [t] = 0 gives [a], 1 gives [b]. */
        fun blend(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** A mix of moods' numbers: the face's target at one moment. */
class Pose {
    var open = 0f
    var tilt = 0f
    var upC = 0f
    var low = 0f
    var lowC = 0f
    var sx = 1f
    var sy = 1f
    var pup = 1f
    var hl = 1f
    var py = 0f
    var tint = 0f
    var wet = 0f
    var asym = 0f
    var fy = 0f
    var fr = 0f
    var fs = 1f
    var color = Mood.HAPPY.color
    var mood = Mood.HAPPY

    fun set(m: Mood) = mix(m, m, 0f)

    /** Sets this to [a] blended toward [b] by [t]; the mood name is whichever is nearer. */
    fun mix(a: Mood, b: Mood, t: Float) {
        fun l(x: Float, y: Float) = x + (y - x) * t
        open = l(a.open, b.open); tilt = l(a.tilt, b.tilt); upC = l(a.upC, b.upC)
        low = l(a.low, b.low); lowC = l(a.lowC, b.lowC); sx = l(a.sx, b.sx); sy = l(a.sy, b.sy)
        pup = l(a.pup, b.pup); hl = l(a.hl, b.hl); py = l(a.py, b.py); tint = l(a.tint, b.tint)
        wet = l(a.wet, b.wet); asym = l(a.asym, b.asym); fy = l(a.fy, b.fy); fr = l(a.fr, b.fr)
        fs = l(a.fs, b.fs)
        color = Mood.blend(a.color, b.color, t)
        mood = if (t < 0.5f) a else b
    }
}
