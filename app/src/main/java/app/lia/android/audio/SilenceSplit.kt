package app.lia.android.audio

/**
 * Splitting long audio into pieces (plan section 3.5).
 *
 * The home server re-transcribes its whole buffer after every 0.2 s of quiet
 * and caps a connection at 180 s, so a clip must never exceed ~20 s per
 * connection. Cloud backends are limited instead by Gemini's inline 390 s.
 *
 * Cut at the quietest 30 ms frame inside [target, max]. When nothing in that
 * window is actually quiet, hard-cut at max and start the next piece
 * [overlapS] earlier, so a word straddling the boundary appears whole in the
 * next piece - an occasional doubled word beats a clipped one.
 */
object SilenceSplit {

    data class Profile(
        val thresholdS: Float,
        val targetS: Float,
        val maxS: Float,
        val overlapS: Float,
    )

    val SERVER = Profile(thresholdS = 20.0f, targetS = 13.0f, maxS = 19.0f, overlapS = 1.0f)

    /** Gemini punishes overlap with duplicated text, so the cloud profile has none. */
    val CLOUD = Profile(thresholdS = 390.0f, targetS = 330.0f, maxS = 390.0f, overlapS = 0.0f)

    fun split(audio: FloatArray, profile: Profile): List<FloatArray> {
        val rate = AudioMath.RATE
        if (audio.size / rate.toFloat() <= profile.thresholdS) return listOf(audio)
        val frame = (0.03f * rate).toInt()
        val pieces = ArrayList<FloatArray>()
        var pos = 0
        val n = audio.size
        while (pos < n) {
            val remaining = (n - pos) / rate.toFloat()
            if (remaining <= profile.maxS) {
                pieces.add(audio.copyOfRange(pos, n))
                break
            }
            val lo = pos + (profile.targetS * rate).toInt()
            val hi = minOf(n, pos + (profile.maxS * rate).toInt())
            var bestIndex = -1
            var bestRms = Float.MAX_VALUE
            var i = lo
            while (i + frame <= hi) {
                val r = AudioMath.rms(audio, i, i + frame)
                if (r < bestRms) {
                    bestRms = r
                    bestIndex = i
                }
                i += frame
            }
            val cut: Int
            val quiet: Boolean
            if (bestIndex < 0) {
                cut = hi
                quiet = false
            } else {
                cut = bestIndex + frame / 2
                quiet = bestRms < AudioMath.SILENCE_RMS
            }
            pieces.add(audio.copyOfRange(pos, cut))
            pos = if (quiet) cut else maxOf(pos + 1, cut - (profile.overlapS * rate).toInt())
        }
        return pieces.filter { it.isNotEmpty() }
    }

    fun forServer(audio: FloatArray): List<FloatArray> = split(audio, SERVER)

    fun forCloud(audio: FloatArray): List<FloatArray> = split(audio, CLOUD)
}
