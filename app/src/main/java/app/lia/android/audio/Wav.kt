package app.lia.android.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16 WAV encoding - the only container the cloud backends get. */
object Wav {

    fun encode(audio: FloatArray, rate: Int = AudioMath.RATE): ByteArray {
        val pcm = ByteArray(audio.size * 2)
        var j = 0
        for (v in audio) {
            val clamped = when {
                v > 1f -> 1f
                v < -1f -> -1f
                else -> v
            }
            val s = (clamped * 32767f).toInt()
            pcm[j] = (s and 0xFF).toByte()
            pcm[j + 1] = ((s shr 8) and 0xFF).toByte()
            j += 2
        }
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)              // PCM
        header.putShort(1)              // mono
        header.putInt(rate)
        header.putInt(rate * 2)         // byte rate
        header.putShort(2)              // block align
        header.putShort(16)             // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    /** float32 little-endian frames - what the home server expects on the wire. */
    fun toFloat32Le(audio: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(audio.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in audio) {
            buf.putFloat(
                when {
                    v > 1f -> 1f
                    v < -1f -> -1f
                    else -> v
                }
            )
        }
        return buf.array()
    }

    /** Minimal reader used by the unit tests and by "share a .wav into Lia". */
    fun decode(bytes: ByteArray): Pair<FloatArray, Int> {
        require(bytes.size > 44) { "not a wav" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a wav" }
        var pos = 12
        var channels = 1
        var rate = AudioMath.RATE
        var bits = 16
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size
                }
            }
            pos = body + size + (size and 1)
            if (dataOffset >= 0 && id == "data") break
        }
        require(dataOffset >= 0 && bits == 16) { "unsupported wav (bits=$bits)" }
        val end = minOf(bytes.size, dataOffset + dataLength)
        val interleaved = AudioMath.pcm16ToFloat(bytes.copyOfRange(dataOffset, end))
        return AudioMath.toMono(interleaved, channels) to rate
    }
}
