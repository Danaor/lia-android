package app.lia.android

import app.lia.android.audio.AudioMath
import app.lia.android.audio.Resampler
import app.lia.android.audio.SilenceSplit
import app.lia.android.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private fun tone(seconds: Float, hz: Double, amplitude: Float, rate: Int = AudioMath.RATE) =
    FloatArray((seconds * rate).toInt()) {
        (amplitude * sin(2 * PI * hz * it / rate)).toFloat()
    }

private fun silence(seconds: Float, rate: Int = AudioMath.RATE) =
    FloatArray((seconds * rate).toInt())

class AudioMathTest {

    @Test
    fun `the silence gate matches the desktop threshold`() {
        assertTrue(AudioMath.isSilent(tone(2f, 440.0, 0.001f)))
        assertFalse(AudioMath.isSilent(tone(2f, 440.0, 0.05f)))
    }

    @Test
    fun `a clip shorter than a second and a half is rejected`() {
        assertTrue(AudioMath.isTooShort(tone(1.4f, 440.0, 0.1f)))
        assertFalse(AudioMath.isTooShort(tone(1.6f, 440.0, 0.1f)))
    }

    @Test
    fun `auto gain lifts a quiet clip but never past four times`() {
        val quiet = tone(3f, 300.0, 0.02f)          // rms about 0.014
        val gained = AudioMath.applyAutoGain(quiet)
        assertTrue(gained.gain > 1f)
        // rms < 0.02 means the near-noise cap applies.
        assertTrue("gain was ${gained.gain}", gained.gain <= AudioMath.GAIN_NEAR_NOISE_MAX + 1e-3)
    }

    @Test
    fun `auto gain never clips`() {
        val loudPeaks = FloatArray(AudioMath.RATE * 2) { if (it % 1000 == 0) 0.95f else 0.01f }
        val gained = AudioMath.applyAutoGain(loudPeaks)
        assertTrue(gained.audio.all { abs(it) <= 0.9701f })
    }

    @Test
    fun `auto gain never attenuates a loud clip`() {
        val loud = tone(2f, 300.0, 0.5f)
        assertEquals(1f, AudioMath.applyAutoGain(loud).gain, 1e-6f)
    }

    @Test
    fun `the bias gate needs both length and level`() {
        assertFalse("short clip", AudioMath.biasOk(tone(2f, 300.0, 0.2f)))
        assertFalse("quiet clip", AudioMath.biasOk(tone(8f, 300.0, 0.01f)))
        assertTrue("long and loud", AudioMath.biasOk(tone(8f, 300.0, 0.2f)))
    }

    @Test
    fun `trimming keeps half a second of the silent tail`() {
        val clip = tone(3f, 300.0, 0.3f) + silence(5f)
        val trimmed = AudioMath.trimTrailingSilence(clip)
        val seconds = AudioMath.seconds(trimmed)
        assertTrue("kept $seconds s", seconds in 3.4f..3.7f)
    }

    @Test
    fun `trimming leaves a clip that never goes quiet alone`() {
        val clip = tone(3f, 300.0, 0.3f)
        assertEquals(clip.size, AudioMath.trimTrailingSilence(clip).size)
    }
}

class WavTest {

    @Test
    fun `a wav round trip preserves the samples`() {
        val original = tone(0.5f, 440.0, 0.5f)
        val (decoded, rate) = Wav.decode(Wav.encode(original))
        assertEquals(AudioMath.RATE, rate)
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertTrue(abs(original[i] - decoded[i]) < 1e-3f)
        }
    }

    @Test
    fun `float32 frames are little endian and clamped`() {
        val bytes = Wav.toFloat32Le(floatArrayOf(1.5f, -1.5f, 0f))
        assertEquals(12, bytes.size)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.0f, buffer.getFloat(0), 1e-6f)
        assertEquals(-1.0f, buffer.getFloat(4), 1e-6f)
        assertEquals(0f, buffer.getFloat(8), 1e-6f)
    }
}

class ResamplerTest {

    /** Goertzel magnitude at [hz] - enough to find the dominant bin without an FFT. */
    private fun magnitude(signal: FloatArray, hz: Double, rate: Int): Double {
        val w = 2 * PI * hz / rate
        val coeff = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (sample in signal) {
            val s0 = sample + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    @Test
    fun `a one kilohertz tone stays one kilohertz from 48k to 16k`() {
        val source = tone(1f, 1000.0, 0.5f, rate = 48000)
        val out = Resampler.to16k(source, 48000)
        assertEquals(16000, out.size)
        val atTone = magnitude(out, 1000.0, AudioMath.RATE)
        val atOther = magnitude(out, 3000.0, AudioMath.RATE)
        assertTrue("tone $atTone vs other $atOther", atTone > atOther * 50)
    }

    @Test
    fun `the sinc filter suppresses a tone above the new nyquist`() {
        // 12 kHz cannot exist below an 8 kHz Nyquist: it must be attenuated,
        // not folded back into the speech band.
        val source = tone(1f, 12000.0, 0.5f, rate = 48000)
        val out = Resampler.to16k(source, 48000)
        val energy = AudioMath.rms(out)
        assertTrue("residual rms $energy", energy < 0.05f)
    }

    @Test
    fun `same rate is a no-op`() {
        val source = tone(0.2f, 400.0, 0.3f)
        assertTrue(Resampler.to16k(source, AudioMath.RATE) === source)
    }
}

class SilenceSplitTest {

    @Test
    fun `a short clip is not split`() {
        val clip = tone(10f, 300.0, 0.2f)
        assertEquals(1, SilenceSplit.forServer(clip).size)
    }

    @Test
    fun `a long clip is cut at the quiet gaps and every piece fits`() {
        // speech / gap / speech / gap ... 6 x (8 s speech + 1 s silence) = 54 s
        var clip = FloatArray(0)
        repeat(6) { clip += tone(8f, 300.0, 0.3f) + silence(1f) }
        val pieces = SilenceSplit.forServer(clip)
        assertTrue("pieces ${pieces.size}", pieces.size >= 3)
        pieces.forEach {
            assertTrue(
                "piece of ${AudioMath.seconds(it)} s",
                AudioMath.seconds(it) <= SilenceSplit.SERVER.maxS + 0.05f,
            )
        }
    }

    @Test
    fun `continuous speech is hard-cut with an overlap`() {
        val clip = tone(45f, 300.0, 0.3f)          // never quiet
        val pieces = SilenceSplit.forServer(clip)
        val total = pieces.sumOf { it.size.toLong() }
        assertTrue("pieces ${pieces.size}", pieces.size >= 3)
        pieces.forEach {
            assertTrue(AudioMath.seconds(it) <= SilenceSplit.SERVER.maxS + 0.05f)
        }
        // Overlap means the pieces together are LONGER than the original.
        assertTrue("total $total vs ${clip.size}", total > clip.size)
    }

    @Test
    fun `the cloud profile keeps a six minute file in one request`() {
        val clip = tone(300f, 300.0, 0.2f)
        assertEquals(1, SilenceSplit.forCloud(clip).size)
    }

    @Test
    fun `the cloud profile splits past the gemini ceiling`() {
        var clip = FloatArray(0)
        repeat(9) { clip += tone(49f, 300.0, 0.3f) + silence(1f) }   // 450 s
        val pieces = SilenceSplit.forCloud(clip)
        assertEquals(2, pieces.size)
        pieces.forEach {
            assertTrue(AudioMath.seconds(it) <= SilenceSplit.CLOUD.maxS + 0.05f)
        }
    }
}
