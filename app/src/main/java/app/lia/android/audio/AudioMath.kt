package app.lia.android.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Audio pre-processing, ported constant-for-constant from Lia Desktop
 * (plan section 4). Nothing here is tuned: the numbers are the desktop's.
 *
 * Everything works on 16 kHz mono float samples in [-1, 1].
 */
object AudioMath {

    const val RATE = 16000

    /** 4.1 - below this peak RMS a dictation clip counts as silence. */
    const val SILENCE_RMS = 0.005f

    /** 4.1 - shorter dictation than this is discarded. */
    const val MIN_DICTATION_S = 1.5f

    /** 4.2 */
    const val TRIM_THRESHOLD_DB = -45.0
    const val TRIM_TAIL_MS = 500

    /** 4.4 */
    const val GAIN_TARGET_RMS = 0.06f
    const val GAIN_MAX = 4.0f
    const val GAIN_NEAR_NOISE_RMS = 0.02f
    const val GAIN_NEAR_NOISE_MAX = 3.0f

    /** 4.5 - a vocabulary prompt is only safe on a long, loud enough clip. */
    const val BIAS_MIN_SEC = 5.0f
    const val BIAS_MIN_RMS = 0.02f

    fun seconds(audio: FloatArray): Float = audio.size / RATE.toFloat()

    fun rms(audio: FloatArray, from: Int = 0, until: Int = audio.size): Float {
        if (until <= from) return 0f
        var sum = 0.0
        for (i in from until until) {
            val v = audio[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / (until - from)).toFloat()
    }

    /** 4.1 - highest RMS over a sliding window (300 ms by default). */
    fun peakRms(audio: FloatArray, windowS: Float = 0.3f): Float {
        val win = (windowS * RATE).toInt()
        if (audio.size <= win) return rms(audio)
        val step = maxOf(1, win / 2)
        var best = 0f
        var i = 0
        while (i + win <= audio.size) {
            val r = rms(audio, i, i + win)
            if (r > best) best = r
            i += step
        }
        return best
    }

    /** 4.1 - true when the clip is loud enough to be worth sending. */
    fun isSilent(audio: FloatArray): Boolean = peakRms(audio) < SILENCE_RMS

    fun isTooShort(audio: FloatArray): Boolean = seconds(audio) < MIN_DICTATION_S

    /**
     * 4.2 - cut the quiet tail, keeping [tailMs] of it. Whisper-family models
     * hallucinate "thank you for watching" on trailing silence; GPT-family
     * models do not, and for them this is skipped by the caller.
     */
    fun trimTrailingSilence(
        audio: FloatArray,
        thresholdDb: Double = TRIM_THRESHOLD_DB,
        tailMs: Int = TRIM_TAIL_MS,
    ): FloatArray {
        if (audio.isEmpty()) return audio
        val frame = (0.02f * RATE).toInt()
        val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
        var lastLoud = -1
        var i = 0
        while (i + frame <= audio.size) {
            if (rms(audio, i, i + frame) > threshold) lastLoud = i + frame
            i += frame
        }
        if (lastLoud < 0) return audio
        val keep = min(audio.size, lastLoud + tailMs * RATE / 1000)
        return if (keep >= audio.size) audio else audio.copyOfRange(0, keep)
    }

    /** Result of [applyAutoGain]: the (possibly) amplified clip and the factor used. */
    data class Gained(val audio: FloatArray, val gain: Float) {
        override fun equals(other: Any?): Boolean =
            other is Gained && gain == other.gain && audio.contentEquals(other.audio)

        override fun hashCode(): Int = 31 * audio.contentHashCode() + gain.hashCode()
    }

    /**
     * 4.4 - lift a quiet dictation towards [GAIN_TARGET_RMS]. Never attenuates,
     * never clips, and caps near-noise clips at 3x so a near-silent recording is
     * not blown up into hallucination fuel.
     */
    fun applyAutoGain(audio: FloatArray): Gained {
        val current = rms(audio)
        if (current <= 0f) return Gained(audio, 1f)
        var gain = GAIN_TARGET_RMS / current
        if (gain <= 1f) return Gained(audio, 1f)
        val cap = if (current < GAIN_NEAR_NOISE_RMS) GAIN_NEAR_NOISE_MAX else GAIN_MAX
        gain = min(gain, cap)
        var peak = 0f
        for (v in audio) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 0f) gain = min(gain, 0.97f / peak)
        if (gain <= 1f) return Gained(audio, 1f)
        val out = FloatArray(audio.size) { audio[it] * gain }
        return Gained(out, gain)
    }

    /**
     * 4.5 - may this clip carry a vocabulary prompt? Measured on the clip
     * BEFORE auto-gain: on a short or quiet clip a Latin-heavy prompt makes
     * Whisper copy the prompt instead of transcribing.
     */
    fun biasOk(audio: FloatArray): Boolean =
        seconds(audio) >= BIAS_MIN_SEC && peakRms(audio) >= BIAS_MIN_RMS

    /** PCM16 little-endian bytes -> float samples. */
    fun pcm16ToFloat(bytes: ByteArray, length: Int = bytes.size): FloatArray {
        val n = length / 2
        val out = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val lo = bytes[j].toInt() and 0xFF
            val hi = bytes[j + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768.0f
            j += 2
        }
        return out
    }

    fun shortsToFloat(samples: ShortArray, length: Int = samples.size): FloatArray =
        FloatArray(length) { samples[it] / 32768.0f }

    /** Average interleaved channels down to mono. */
    fun toMono(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            out[i] = sum / channels
        }
        return out
    }
}
