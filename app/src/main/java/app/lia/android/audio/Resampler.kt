package app.lia.android.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sample-rate conversion to Lia's 16 kHz working rate.
 *
 * Decoded files arrive at 44.1 or 48 kHz, so downsampling without a low-pass
 * would alias speech energy above 8 kHz back into the band Whisper listens to.
 * [windowedSinc] filters properly; [linear] is the cheap fallback kept for
 * upsampling and for the unit tests that compare the two.
 */
object Resampler {

    /** Windowed-sinc (Blackman) resampler with the cutoff at the lower Nyquist. */
    fun windowedSinc(input: FloatArray, srcRate: Int, dstRate: Int, taps: Int = 16): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLength = floor(input.size / ratio).toInt()
        if (outLength <= 0) return FloatArray(0)
        // Cutoff (normalised to the source rate) at the lower of the two Nyquists.
        val cutoff = 0.5 * min(1.0, dstRate.toDouble() / srcRate)
        val half = max(1, (taps * max(1.0, ratio)).toInt())
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val center = i * ratio
            val first = floor(center).toInt() - half + 1
            val last = floor(center).toInt() + half
            var acc = 0.0
            var norm = 0.0
            for (j in first..last) {
                if (j < 0 || j >= input.size) continue
                val x = j - center
                val w = blackman(x, half.toDouble())
                if (w == 0.0) continue
                val h = 2.0 * cutoff * sinc(2.0 * cutoff * x) * w
                acc += input[j] * h
                norm += h
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /** Linear interpolation - fast, fine for upsampling, aliases when downsampling. */
    fun linear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLength = floor(input.size / ratio).toInt()
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    fun to16k(input: FloatArray, srcRate: Int): FloatArray =
        if (srcRate == AudioMath.RATE) input else windowedSinc(input, srcRate, AudioMath.RATE)

    private fun sinc(x: Double): Double =
        if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

    private fun blackman(x: Double, half: Double): Double {
        if (abs(x) > half) return 0.0
        val t = (x + half) / (2 * half)
        return 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
    }
}
